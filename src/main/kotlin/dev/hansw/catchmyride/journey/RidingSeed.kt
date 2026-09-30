package dev.hansw.catchmyride.journey

import dev.hansw.catchmyride.stops.SubwayStationCatalog
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * API.md §9-2 "중간 시작" — 출발지를 이미 지나 **탄 상태**로 하차 알림을 시작한 트립의 시드.
 *
 * 탑승역 전광판을 쓰면 안 된다: 유저가 이미 지난 역에 있는 건 **뒤에 오는 열차**라, 그걸 잡으면
 * 하차 푸시가 내릴 역을 지난 뒤에 온다 (2026-09-24 오너 제보, 2026-09-29 재제보 — 수유에서 출발해
 * 중간에 시작한 트립이 여전히 수유 기준으로 잡혔다).
 *
 * 규칙 (2026-09-29 개정 — 첫 구현이 실주행에서 안 걸렸던 이유를 정면으로 고친다):
 * - **기준점은 좌표 자신**이다. 첫 구현은 "좌표 → 가장 가까운 역 → 그 역의 **학습된 역 id**로
 *   {그 역, 다음 역} 창"이었는데, 역 id는 두 상류 피드에 **열차가 지금 서 있는 역**으로만 실려 온다.
 *   유저가 선 역에 마침 열차가 없으면 id를 몰라 창이 그 역 이름 하나로 쪼그라들고, 그 역에 열차가
 *   없으니 후보는 언제나 0대 — 60초 뒤 LOST였다. 이제 창은 정적 카탈로그 좌표로 잰다.
 * - 후보는 **기준점에서 가장 가까운 "열차가 있는 역"의 우리 방면 열차**다. 노선 위치 피드
 *   (realtimePosition)는 노선의 모든 열차를 싣고 오므로 유저가 탄 열차는 반드시 그 안에 있고,
 *   자기가 탄 열차만큼 자기 좌표에 가까운 열차는 없다. 열차가 막 떠난 다음 역으로 보고돼도 잡힌다.
 * - 열차의 현재 역 id가 탑승역~하차역 **사이**가 아니면 버린다 — 탑승역에 있는 뒤차가 여기서 걸린다.
 * - 측위 오차가 크면 기준점이 한 역쯤 밀릴 수 있으므로, **탑승역에서 오차 반경보다 확실히 멀 때만**
 *   중간 시작으로 본다. 애매하면 위치를 쓰지 않고 기존(탑승역 전광판) 시드로 강등한다 (NFR-03).
 * - 좌표는 판정에만 쓰고 저장하지 않는다 — 상시 추적이 아니라 시작 1회다 (NFR-05).
 */
@Component
class RidingSeedResolver(
    private val stations: SubwayStationCatalog,
    private val stationIds: StationIdCache,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 시작 시점 좌표 1회 — 오차 반경·측위 나이는 선택(모르면 null) */
    data class Fix(val lat: Double, val lng: Double, val accuracyMeters: Double?, val ageSeconds: Long?)

    /**
     * 좌표가 가리키는 "지금 있는 역" — 중간 시작으로 볼 수 있을 때만. 아니면 null(기존 시드로 강등).
     * 낡은 좌표는 버린다: 달리는 열차에서 1분 전 좌표는 한두 정거장 뒤를 가리켜 **뒤차를 고르게 한다**.
     */
    fun locate(leg: JourneyLeg, fix: Fix): String? {
        val age = fix.ageSeconds
        if (age != null && age > MAX_FIX_AGE_SECONDS) {
            log.info("측위가 낡음({}초) — 위치 무시 (달리는 열차에선 뒤차를 고르게 된다)", age)
            return null
        }
        val accuracy = fix.accuracyMeters
        if (accuracy != null && accuracy > MAX_ACCURACY_METERS) {
            log.info("측위 정확도 부족(±{}m) — 위치 무시", accuracy.toInt())
            return null
        }
        val line = lineBase(leg.line)
        val slack = accuracy ?: 0.0
        val here = stations.nearest(line, fix.lat, fix.lng, NEAR_STATION_METERS + slack) ?: return null
        if (here == leg.boardStop || here == leg.alightStop) {
            return null // 탑승역에서 시작 = 기존 경로 / 하차역에 이미 도착 = 위치로 할 게 없다
        }
        // 탑승역에서 "확실히" 멀어야 한다 — 오차 반경 안이면 아직 탑승역 플랫폼일 수 있다
        val fromBoard = stations.distanceTo(line, leg.boardStop, fix.lat, fix.lng)
        if (fromBoard == null || fromBoard <= maxOf(slack, MIN_BOARD_CLEARANCE_METERS)) {
            log.info("탑승역({})에서 충분히 멀지 않음(±{}m) — 위치 무시", leg.boardStop, accuracy?.toInt() ?: 0)
            return null
        }
        val boardId = stationIds.get(leg.line, leg.boardStop)
        val hereId = stationIds.get(leg.line, here)
        val alightId = stationIds.get(leg.line, leg.alightStop)
        if (boardId != null && hereId != null && alightId != null && !isOnWay(leg.line, boardId, hereId, alightId)) {
            log.info("위치 역이 구간 밖 — here={} ({}→{}): 위치 무시", here, leg.boardStop, leg.alightStop)
            return null
        }
        return here
    }

    /** 시작 시드 — 좌표 자신을 기준점으로 (가장 정확한 순간이다) */
    fun candidatesNear(leg: JourneyLeg, fix: Fix, heading: Heading?, lineTrains: List<LineTrain>): List<String> =
        candidatesAround(leg, fix.lat, fix.lng, heading, lineTrains)

    /**
     * 특정 전 재수집 시드 — 좌표를 저장하지 않으므로(NFR-05) 저장된 위치 역(seedStop) 좌표를 기준점으로.
     * 같은 규칙이라 시작과 결과가 어긋나지 않는다
     */
    fun candidatesAround(leg: JourneyLeg, refStop: String, heading: Heading?, lineTrains: List<LineTrain>): List<String> {
        val line = lineBase(leg.line)
        val ref = stations.coordinatesOf(line, refStop) ?: return emptyList()
        return candidatesAround(leg, ref.first, ref.second, heading, lineTrains)
    }

    private fun candidatesAround(
        leg: JourneyLeg,
        lat: Double,
        lng: Double,
        heading: Heading?,
        lineTrains: List<LineTrain>,
    ): List<String> {
        val line = lineBase(leg.line)
        val boardId = stationIds.get(leg.line, leg.boardStop)
        val alightId = stationIds.get(leg.line, leg.alightStop)
        // (열차, 열차가 있는 역, 기준점까지 거리) — 역을 카탈로그에서 못 찾으면(표기 차이) 버린다
        val placed = lineTrains
            .filter { it.matchesExpress(leg.line) && it.matchesHeading(heading) }
            .filter { onWayOrUnknown(leg, boardId, it.stationId, alightId) }
            .mapNotNull { train ->
                val station = train.station ?: return@mapNotNull null
                val distance = stations.distanceTo(line, station, lat, lng) ?: return@mapNotNull null
                Triple(train.trainNo, station, distance)
            }
            .filter { it.third <= RIDING_WINDOW_METERS }
        // 기준점에 가장 가까운 역의 열차들 — 유저가 탄 열차만큼 유저에게 가까운 열차는 없다.
        // 같은 역에 두 대가 보고되면(급행 교차 등) 둘 다 후보로 두고 하차역 목격으로 자기선택한다
        val nearestStop = placed.minByOrNull { it.third }?.second ?: return emptyList()
        return placed.filter { it.second == nearestStop }.map { it.first }.distinct()
    }

    /** 열차의 현재 역이 탑승역~하차역 사이인지 — 탑승역의 뒤차가 여기서 걸린다. 모르면 통과 (NFR-03) */
    private fun onWayOrUnknown(leg: JourneyLeg, boardId: Long?, trainStationId: Long?, alightId: Long?): Boolean {
        if (boardId == null || alightId == null || trainStationId == null) {
            return true
        }
        return isOnWay(leg.line, boardId, trainStationId, alightId)
    }

    companion object {
        /**
         * 이보다 낡은 좌표는 쓰지 않는다 — 열차는 1분에 한두 정거장을 간다. 낡은 좌표는 "없음"보다
         * 나쁘다: 유저 뒤 열차를 자신 있게 고르게 한다 (2026-09-29)
         */
        private const val MAX_FIX_AGE_SECONDS = 90L

        /**
         * 이보다 오차가 큰 좌표는 쓰지 않는다. 지하·터널에선 기지국 측위로 ±1~2km가 흔해 처음 1km로
         * 잡았던 상한은 **실주행에서 좌표를 거의 다 버렸다**(중간 시작이 안 걸린 주된 이유).
         * 한 역이 아니라 "탑승역에서 몇 정거장 지났는지"를 가리기만 해도 뒤차는 걸러진다 (2026-09-29 완화)
         */
        private const val MAX_ACCURACY_METERS = 2_500.0

        /** 이 반경(+오차) 안에 그 노선 역이 없으면 "역 근처가 아니다"로 본다 (역간 거리 대비 여유) */
        private const val NEAR_STATION_METERS = 1_500.0

        /** 탑승역에서 최소 이만큼은 떨어져 있어야 "이미 지났다"로 본다 (역 구내·플랫폼 여유) */
        private const val MIN_BOARD_CLEARANCE_METERS = 700.0

        /**
         * 기준점에서 이 거리 안의 열차만 후보로 본다 — 서울 도심 역간 거리가 1.2~1.5km라
         * 앞뒤 한 정거장 남짓이다. 열차가 막 떠난 다음 역으로 보고돼도 들어오고, 그보다 멀리 있는
         * 열차(뒤차·앞차)는 들어오지 않는다
         */
        private const val RIDING_WINDOW_METERS = 2_000.0
    }
}
