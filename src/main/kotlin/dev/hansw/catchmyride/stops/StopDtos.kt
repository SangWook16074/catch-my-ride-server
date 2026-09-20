package dev.hansw.catchmyride.stops

/** API.md §1·§5 — 정류장 종류. stopId 의미: SEOUL_BUS=arsId(5자리) / GYEONGGI_BUS=GBIS stationId / SUBWAY=역명 */
enum class StopType { SEOUL_BUS, GYEONGGI_BUS, SUBWAY }

/**
 * §5-1 검색 결과 한 건. subtitle은 동명 정류장 구분용 보조 정보(정류소 번호·행정구 / 호선) —
 * 클라이언트가 그대로 표시하므로 null/누락 금지, 없으면 빈 문자열 (SERVER_FEEDBACK.md §4).
 */
data class StopSearchResult(
    val type: StopType,
    val stopId: String,
    val displayName: String,
    val subtitle: String,
)

data class StopSearchResponse(val results: List<StopSearchResult>)

/**
 * §5-2 경유 노선 한 건. isExpress는 지하철 급행만 true, 그 외 null.
 * directionLabel(v0.6)은 버스 방면 표기("강남역 방면") — 정류장=방향이라 선택이 아닌 표기로,
 * 반대편 정류장을 고른 유저가 노선 선택 단계에서 알아채게 한다. 지하철·상류 미제공 시 null.
 */
data class RouteResult(val name: String, val isExpress: Boolean?, val directionLabel: String? = null)

data class StopRoutesResponse(val routes: List<RouteResult>)

/**
 * §5-3 지하철 방면 선택지 한 건 (온보딩 방면 선택).
 * key = 상류 updnLine 그대로("상행"/"하행"/"내선"/"외선") — CommuteStop.direction에 저장하는 값.
 * label = 사람이 읽는 표기("당고개 방면") — 실시간 행선지가 없으면 key 그대로.
 */
data class DirectionResult(val key: String, val label: String)

data class StopDirectionsResponse(val directions: List<DirectionResult>)
