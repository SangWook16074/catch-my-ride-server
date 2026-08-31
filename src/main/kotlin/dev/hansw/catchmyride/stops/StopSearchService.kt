package dev.hansw.catchmyride.stops

import dev.hansw.catchmyride.api.ApiException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * §5 검색 통합 서비스 — 세 소스(서울 버스·경기 버스·지하철)를 조회해 합친다.
 *
 * 소스 단위 격리 원칙: 한 소스가 실패(키 미승인 401, 장애, 타임아웃)해도 나머지 소스 결과는 내려준다.
 * 지하철은 서버 내장 데이터라 항상 성공 — 검색이 완전히 죽는 경우는 사실상 없다.
 */
@Service
class StopSearchService(
    private val seoulBus: SeoulBusStopClient,
    private val gbis: GbisStopClient,
    private val subway: SubwayStationCatalog,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun search(query: String): List<StopSearchResult> {
        val trimmed = query.trim()
        if (trimmed.length < 2) throw ApiException.invalidRequest("query는 2자 이상이어야 합니다")

        val results = buildList {
            addAll(subway.search(trimmed, PER_SOURCE_LIMIT))
            if (seoulBus.available()) addAll(safely("SEOUL_BUS") { seoulBus.search(trimmed, PER_SOURCE_LIMIT) })
            if (gbis.available()) addAll(safely("GYEONGGI_BUS") { gbis.search(trimmed, PER_SOURCE_LIMIT) })
        }
        // 통합 정렬: 정확 일치 → 접두 일치 → 포함 (API.md §5-1)
        return results.sortedBy { r ->
            when {
                r.displayName == trimmed || r.stopId == trimmed -> 0
                r.displayName.startsWith(trimmed) -> 1
                else -> 2
            }
        }
    }

    fun routes(type: StopType, stopId: String): List<RouteResult> = when (type) {
        StopType.SUBWAY -> subway.routes(stopId)
            ?: throw ApiException.invalidRequest("없는 역명입니다: $stopId")
        StopType.SEOUL_BUS -> upstream("서울 버스") { seoulBus.routes(stopId) }
        StopType.GYEONGGI_BUS -> upstream("경기 버스") { gbis.routes(stopId) }
    }

    /** 검색용 — 실패한 소스는 빈 결과로 대체하고 로그만 남긴다. */
    private fun safely(source: String, fetch: () -> List<StopSearchResult>): List<StopSearchResult> =
        try {
            fetch()
        } catch (e: Exception) {
            log.warn("정류장 검색 소스 {} 실패 — 해당 소스 제외하고 응답: {}", source, e.message)
            emptyList()
        }

    /** 노선 조회용 — 단일 소스라 실패를 숨기지 않고 503으로 알린다 (NFR-03: 아는 척 금지). */
    private fun upstream(source: String, fetch: () -> List<RouteResult>): List<RouteResult> =
        try {
            fetch()
        } catch (e: Exception) {
            log.warn("{} 노선 조회 실패: {}", source, e.message)
            throw ApiException.upstreamUnavailable("$source 정보를 지금 가져올 수 없습니다")
        }

    companion object {
        const val PER_SOURCE_LIMIT = 10
    }
}
