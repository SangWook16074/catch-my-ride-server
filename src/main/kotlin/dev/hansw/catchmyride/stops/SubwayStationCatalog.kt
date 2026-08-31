package dev.hansw.catchmyride.stops

import dev.hansw.catchmyride.spike.adapter.asItemList
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * 수도권 지하철 역 목록 — 서버 내장 정적 데이터 (API.md §5: 역 수가 유한해 실시간 API 불필요).
 * 원천: 서울열린데이터광장 SearchSTNBySubwayLineInfo (2026-08-28 기준 655역, GTX-A·신분당선 등 포함).
 * 노선 개편 시 갱신: 같은 API를 다시 받아 data/subway-stations.json 교체.
 */
@Component
class SubwayStationCatalog(objectMapper: ObjectMapper) {

    data class Station(val name: String, val lines: List<String>) {
        /** 화면 표시명 — "서울역"처럼 원래 '역'으로 끝나는 이름엔 다시 붙이지 않는다 */
        val displayName: String = if (name.endsWith("역")) name else "${name}역"
    }

    private val stations: List<Station> =
        ClassPathResource("data/subway-stations.json").inputStream.use { input ->
            objectMapper.readTree(input).asItemList().map { node ->
                Station(
                    name = node.path("name").asString(),
                    lines = node.path("lines").asItemList().map { it.asString() },
                )
            }
        }

    /**
     * 역명 부분 일치 검색 — 유저가 치는 "강남역" 같은 표시명 기준으로 매칭한다
     * (SERVER_FEEDBACK.md 재검증 버그: 내부명 "강남"만 보면 "강남역" 검색이 통째로 빠진다).
     * displayName이 내부명을 항상 포함하므로 표시명 하나로 두 경우가 모두 커버된다.
     * 정확 일치 → 접두 일치 → 포함 순으로 정렬.
     */
    fun search(query: String, limit: Int): List<StopSearchResult> {
        val trimmed = query.trim()
        return stations.asSequence()
            .filter { it.displayName.contains(trimmed) }
            .sortedBy { station ->
                when {
                    station.name == trimmed || station.displayName == trimmed -> 0
                    station.displayName.startsWith(trimmed) -> 1
                    else -> 2
                }
            }
            .take(limit)
            .map { StopSearchResult(StopType.SUBWAY, stopId = it.name, displayName = it.displayName, subtitle = it.lines.joinToString(" · ")) }
            .toList()
    }

    /**
     * 해당 역을 지나는 노선 선택지 (SERVER_FEEDBACK.md §4: 지하철 일반=false, 급행만 true).
     * 급행이 운행되는 노선은 "N호선 급행 / N호선 일반"으로 나눠 내려 유저가 급행만 고를 수 있게 한다 (FR-203).
     * 이 표기는 §2 arrivals의 routeName과 동일해야 한다 — ArrivalsService.match가 같은 규칙으로 매칭.
     */
    fun routes(stationName: String): List<RouteResult>? =
        stations.find { it.name == stationName }?.lines?.flatMap { line ->
            if (line in EXPRESS_CAPABLE_LINES) {
                listOf(RouteResult("$line 급행", isExpress = true), RouteResult("$line 일반", isExpress = false))
            } else {
                listOf(RouteResult(line, isExpress = false))
            }
        }

    companion object {
        /** 실시간 API(btrainSttus)가 급행/특급을 실제로 구분해 주는 노선들 */
        private val EXPRESS_CAPABLE_LINES =
            setOf("1호선", "9호선", "경의선", "공항철도", "수인분당선", "경춘선", "서해선")
    }
}
