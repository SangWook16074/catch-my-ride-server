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

/** §5-2 경유 노선 한 건. isExpress는 지하철 급행만 true, 그 외 null */
data class RouteResult(val name: String, val isExpress: Boolean?)

data class StopRoutesResponse(val routes: List<RouteResult>)
