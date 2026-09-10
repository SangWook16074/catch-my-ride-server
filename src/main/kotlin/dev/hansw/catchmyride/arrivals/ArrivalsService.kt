package dev.hansw.catchmyride.arrivals

import dev.hansw.catchmyride.api.ApiException
import dev.hansw.catchmyride.commute.CommuteRouteRepository
import dev.hansw.catchmyride.commute.CommuteStop
import dev.hansw.catchmyride.spike.ArrivalInfo
import dev.hansw.catchmyride.spike.SpikeProperties
import dev.hansw.catchmyride.spike.adapter.GbisBusAdapter
import dev.hansw.catchmyride.spike.adapter.SeoulSubwayAdapter
import dev.hansw.catchmyride.spike.adapter.TopisBusAdapter
import dev.hansw.catchmyride.stops.StopType
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * API.md §2 — 라이브 뷰 도착 정보. 저장된 통근 설정의 정류장×노선을 공공 API로 조회해
 * "탑승 가능/상태"를 계산한다 (FR-303/304/502 — 상태 계산은 mock.ts의 statusOf와 동일).
 *
 * 소스 실패는 해당 정류장만 빠지고, 전부 실패하면 realtimeAvailable=false로 정직하게 알린다 (NFR-03).
 */
@Service
class ArrivalsService(
    private val routes: CommuteRouteRepository,
    private val topis: TopisBusAdapter,
    private val gbis: GbisBusAdapter,
    private val subway: SeoulSubwayAdapter,
    private val props: SpikeProperties,
    private val clock: java.time.Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 정류장 단위 공유 캐시 (2026-09-10 지하철 쿼터 1,000건/일 소진 사고 대응) —
     * 라이브 뷰 폴링(유저별)과 푸시 스케줄러(경로별)가 같은 정류장을 각자 호출하던 것을
     * 정류장당 TTL 1회로 합친다. 실패도 짧게 캐시해(네거티브) 장애·쿼터 소진 중 연타를 막는다.
     */
    private data class CacheEntry(val at: java.time.Instant, val infos: List<ArrivalInfo>?, val error: String? = null)

    private val cache = java.util.concurrent.ConcurrentHashMap<Pair<StopType, String>, CacheEntry>()

    /** routeId 생략 시 첫 경로 — 레거시 클라이언트(§1)와 단일 경로 유저의 기본 동작 */
    fun arrivals(userKey: String, routeId: String? = null): ArrivalsResponse {
        val route = when (routeId) {
            null -> routes.list(userKey).firstOrNull()
            else -> routes.find(userKey, routeId)
        } ?: throw ApiException.settingNotFound()
        return arrivalsFor(route.setting)
    }

    /** 푸시 스케줄러(S-5)가 설정을 이미 들고 순회하므로 설정 기반 진입점을 분리 */
    fun arrivalsFor(setting: dev.hansw.catchmyride.commute.CommuteSetting): ArrivalsResponse {
        val walkSeconds = setting.walkMinutes * 60
        val bufferSeconds = setting.bufferMinutes * 60

        var anySourceOk = false
        val arrivals = mutableListOf<Arrival>()
        for (stop in setting.stops) {
            val infos = try {
                fetch(stop) ?: continue // 키 미설정 소스는 건너뜀
            } catch (e: Exception) {
                log.warn("도착 정보 조회 실패 — {}({}) 제외: {}", stop.displayName, stop.type, e.message)
                continue
            }
            anySourceOk = true
            arrivals += match(stop, infos, walkSeconds, bufferSeconds)
        }

        return ArrivalsResponse(
            fetchedAt = OffsetDateTime.now(SEOUL).truncatedTo(ChronoUnit.SECONDS).toString(),
            realtimeAvailable = anySourceOk,
            walkMinutes = setting.walkMinutes,
            arrivals = arrivals.sortedWith(compareBy(nullsLast()) { it.secondsToArrival }),
        )
    }

    /** 키가 없어 호출 자체가 불가능한 소스는 null (테스트·키 미발급 환경에서 네트워크를 타지 않게). */
    private fun fetch(stop: CommuteStop): List<ArrivalInfo>? {
        val call: () -> List<ArrivalInfo> = when (stop.type) {
            StopType.SEOUL_BUS ->
                if (props.keys.dataGoKr.isBlank()) return null else ({ topis.fetchArrivals(stop.stopId).arrivals })
            StopType.GYEONGGI_BUS ->
                if (props.keys.dataGoKr.isBlank()) return null else ({ gbis.fetchArrivals(stop.stopId).arrivals })
            StopType.SUBWAY ->
                if (props.keys.seoulOpenData.isBlank()) return null else ({ subway.fetchArrivals(stop.stopId).arrivals })
        }
        val key = stop.type to stop.stopId
        val now = clock.instant()
        cache[key]?.let { entry ->
            val ttl = if (entry.error == null) SUCCESS_TTL else FAILURE_TTL
            if (java.time.Duration.between(entry.at, now) < ttl) {
                entry.error?.let { throw IllegalStateException(it) } // 실패 캐시 — 상류 연타 금지
                return entry.infos
            }
        }
        return try {
            call().also { cache[key] = CacheEntry(now, it) }
        } catch (e: Exception) {
            cache[key] = CacheEntry(now, null, e.message ?: "도착 정보 조회 실패")
            throw e
        }
    }

    /**
     * 저장된 노선명과 실시간 도착을 매칭한다. 응답의 routeName은 저장된 표기 그대로 돌려줘
     * 클라이언트 매칭(SERVER_FEEDBACK.md §4)을 보장한다.
     * - 버스: rtNm/routeName 문자열 일치
     * - 지하철: "9호선 급행"/"9호선 일반"/"5호선" → 호선(line) + 급행 여부로 매칭
     */
    private fun match(stop: CommuteStop, infos: List<ArrivalInfo>, walkSeconds: Int, bufferSeconds: Int): List<Arrival> =
        stop.routes.flatMap { route ->
            val matched = when (stop.type) {
                StopType.SUBWAY -> {
                    val line = route.removeSuffix(" 급행").removeSuffix(" 일반")
                    val wantExpress = when {
                        route.endsWith("급행") -> true
                        route.endsWith("일반") -> false
                        else -> null // "5호선"처럼 급행 구분 없는 노선 — 전부 매칭
                    }
                    infos.filter { it.line == line && (wantExpress == null || it.isExpress == wantExpress) }
                }
                else -> infos.filter { it.routeName == route }
            }
            matched.map { toArrival(stop.displayName, route, it, walkSeconds, bufferSeconds) }
        }

    private fun toArrival(stopName: String, route: String, info: ArrivalInfo, walkSeconds: Int, bufferSeconds: Int): Arrival {
        val seconds = info.predictedSecondsToArrival
        return Arrival(
            stopDisplayName = stopName,
            routeName = route,
            direction = info.direction,
            secondsToArrival = seconds,
            remainingStops = info.remainingStops,
            isExpress = info.isExpress,
            boardable = seconds != null && seconds >= walkSeconds,
            status = status(seconds, walkSeconds, bufferSeconds),
            rawMessage = info.rawMessage,
        )
    }

    companion object {
        private val SEOUL = ZoneId.of("Asia/Seoul")

        /** 라이브 뷰 폴링 20~30초와 맞물리는 공유 TTL — 유저·스케줄러가 정류장당 이 주기로만 상류를 탄다 */
        private val SUCCESS_TTL: java.time.Duration = java.time.Duration.ofSeconds(25)

        /** 실패(쿼터 초과·장애) 네거티브 캐시 — 복구 확인은 1분에 한 번이면 충분하다 */
        private val FAILURE_TTL: java.time.Duration = java.time.Duration.ofSeconds(60)

        /** mock.ts statusOf와 동일: 여유 = 도착까지 − 도보. 음수면 놓침, 버퍼 이내면 서두르세요. */
        fun status(secondsToArrival: Int?, walkSeconds: Int, bufferSeconds: Int): ArrivalStatus {
            if (secondsToArrival == null) return ArrivalStatus.MISSED // 정보 없음 — 클라이언트는 null 초를 "정보 없음"으로 표시
            val margin = secondsToArrival - walkSeconds
            return when {
                margin < 0 -> ArrivalStatus.MISSED
                margin <= bufferSeconds -> ArrivalStatus.HURRY
                else -> ArrivalStatus.RELAXED
            }
        }
    }
}
