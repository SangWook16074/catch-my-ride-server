package dev.hansw.catchmyride.journey

import dev.hansw.catchmyride.api.ApiException
import dev.hansw.catchmyride.stops.SubwayStationCatalog
import org.springframework.stereotype.Component

/** 구간 입력 (§9 Journey.legs[] — 저장 여정·1회성 트립 시작 공용) */
data class LegRequest(val type: String?, val line: String?, val boardStop: String?, val alightStop: String?)

/**
 * §9 구간(legs) 검증 — 저장 여정(§9-1)과 여정 비귀속 트립 시작(§9-2 `POST /api/v1/trips`, FR-708)이
 * 같은 규칙을 쓴다. 검증은 "탐색"이 아니라 형태·존재 확인까지만: 역이 카탈로그에 있는가,
 * 노선이 그 역을 지나는가. 방면·도달 순서는 추적 단계에서 열차가 자기선택한다.
 */
@Component
class JourneyLegValidator(private val catalog: SubwayStationCatalog) {

    fun validate(legRequests: List<LegRequest>?): List<JourneyLeg> {
        val legs = legRequests.orEmpty()
        if (legs.isEmpty() || legs.size > MAX_JOURNEY_LEGS) {
            throw ApiException.invalidRequest("구간은 1~${MAX_JOURNEY_LEGS}개여야 합니다")
        }
        return legs.map { toLeg(it) }
    }

    private fun toLeg(request: LegRequest): JourneyLeg {
        if (request.type != "SUBWAY") {
            throw ApiException.invalidRequest("v1은 지하철(SUBWAY) 구간만 지원합니다")
        }
        val line = request.line?.trim().orEmpty()
        val board = request.boardStop?.trim().orEmpty()
        val alight = request.alightStop?.trim().orEmpty()
        if (line.isEmpty() || board.isEmpty() || alight.isEmpty()) {
            throw ApiException.invalidRequest("구간의 노선·탑승 역·하차 역을 모두 입력해야 합니다")
        }
        if (board == alight) {
            throw ApiException.invalidRequest("탑승 역과 하차 역이 같습니다: $board")
        }
        val boardRoutes = catalog.routes(board)
            ?: throw ApiException.invalidRequest("알 수 없는 역입니다: $board")
        if (boardRoutes.none { it.name == line }) {
            throw ApiException.invalidRequest("$board 역을 지나지 않는 노선입니다: $line")
        }
        val alightRoutes = catalog.routes(alight)
            ?: throw ApiException.invalidRequest("알 수 없는 역입니다: $alight")
        if (alightRoutes.none { it.name == line }) {
            throw ApiException.invalidRequest("이 노선으로 갈 수 없는 구간입니다: $board → $alight ($line)")
        }
        return JourneyLeg(type = "SUBWAY", line = line, boardStop = board, alightStop = alight)
    }
}
