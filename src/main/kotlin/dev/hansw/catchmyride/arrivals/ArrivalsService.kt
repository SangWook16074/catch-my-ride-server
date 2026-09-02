package dev.hansw.catchmyride.arrivals

import dev.hansw.catchmyride.api.ApiException
import dev.hansw.catchmyride.commute.CommuteSettingRepository
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
    private val settings: CommuteSettingRepository,
    private val topis: TopisBusAdapter,
    private val gbis: GbisBusAdapter,
    private val subway: SeoulSubwayAdapter,
    private val props: SpikeProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun arrivals(userKey: String): ArrivalsResponse =
        arrivalsFor(settings.find(userKey) ?: throw ApiException.settingNotFound())

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
    private fun fetch(stop: CommuteStop): List<ArrivalInfo>? = when (stop.type) {
        StopType.SEOUL_BUS -> if (props.keys.dataGoKr.isBlank()) null else topis.fetchArrivals(stop.stopId).arrivals
        StopType.GYEONGGI_BUS -> if (props.keys.dataGoKr.isBlank()) null else gbis.fetchArrivals(stop.stopId).arrivals
        StopType.SUBWAY -> if (props.keys.seoulOpenData.isBlank()) null else subway.fetchArrivals(stop.stopId).arrivals
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
