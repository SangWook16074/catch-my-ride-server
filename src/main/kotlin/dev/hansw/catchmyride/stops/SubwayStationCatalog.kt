package dev.hansw.catchmyride.stops

import dev.hansw.catchmyride.spike.adapter.asItemList
import dev.hansw.catchmyride.spike.adapter.textOrNull
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * 수도권 지하철 역 목록 — 서버 내장 정적 데이터 (API.md §5: 역 수가 유한해 실시간 API 불필요).
 * 원천: 서울열린데이터광장 SearchSTNBySubwayLineInfo (2026-08-28 기준 655역, GTX-A·신분당선 등 포함).
 * 좌표(lat/lng)는 같은 곳의 subwayStationMaster(역사마스터, BLDN_NM·LAT·LOT)를 역명으로 합친 값 —
 * 같은 역명이 여러 노선에 있으면 평균(§9-2 "중간 시작" 판정용, 역간 거리 대비 오차가 작다).
 * 마스터에 없는 신설역 7개는 좌표 null — 위치 기반 판정에서 조용히 빠진다 (NFR-03).
 * 노선 개편 시 갱신: 두 API를 다시 받아 data/subway-stations.json 교체.
 */
@Component
class SubwayStationCatalog(objectMapper: ObjectMapper) {

    data class Station(
        val name: String,
        val lines: List<String>,
        val lat: Double? = null,
        val lng: Double? = null,
    ) {
        /** 화면 표시명 — "서울역"처럼 원래 '역'으로 끝나는 이름엔 다시 붙이지 않는다 */
        val displayName: String = if (name.endsWith("역")) name else "${name}역"
    }

    private val stations: List<Station> =
        ClassPathResource("data/subway-stations.json").inputStream.use { input ->
            objectMapper.readTree(input).asItemList().map { node ->
                Station(
                    name = node.path("name").asString(),
                    lines = node.path("lines").asItemList().map { it.asString() },
                    lat = node.textOrNull("lat")?.toDoubleOrNull(),
                    lng = node.textOrNull("lng")?.toDoubleOrNull(),
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

    /**
     * 해당 노선에서 좌표가 가장 가까운 역 — §9-2 "중간 시작"(이미 탄 뒤 하차 알림 시작) 판정용.
     * 반경 밖·좌표 미보유·노선 불일치면 null — 모르면 위치 없이 시작한 것과 같게 강등한다 (NFR-03).
     */
    fun nearest(line: String, lat: Double, lng: Double, withinMeters: Double): String? =
        stations.asSequence()
            .filter { station -> station.lines.any { it == line } }
            .mapNotNull { station ->
                val sLat = station.lat ?: return@mapNotNull null
                val sLng = station.lng ?: return@mapNotNull null
                station.name to distanceMeters(lat, lng, sLat, sLng)
            }
            .filter { it.second <= withinMeters }
            .minByOrNull { it.second }
            ?.first

    /** 그 노선 역의 좌표 — 없으면 null (§9-2 재수집 기준점) */
    fun coordinatesOf(line: String, stationName: String): Pair<Double, Double>? {
        val station = stations.firstOrNull { it.name == stationName && it.lines.any { l -> l == line } } ?: return null
        val lat = station.lat ?: return null
        val lng = station.lng ?: return null
        return lat to lng
    }

    /**
     * 그 노선의 특정 역과 좌표 사이 거리(m) — §9-2 "중간 시작"에서 (1) 유저가 탑승역에서
     * 확실히 멀어졌는지, (2) 노선 위치 피드의 열차가 유저 근처인지 재는 데 쓴다.
     * 역명을 모르거나 좌표가 없으면 null — 모르면 위치를 쓰지 않는다 (NFR-03).
     */
    fun distanceTo(line: String, stationName: String, lat: Double, lng: Double): Double? {
        val station = stations.firstOrNull { it.name == stationName && it.lines.any { l -> l == line } } ?: return null
        val sLat = station.lat ?: return null
        val sLng = station.lng ?: return null
        return distanceMeters(lat, lng, sLat, sLng)
    }

    companion object {
        /** 하버사인 거리(m) — 역 간 비교에만 쓰므로 구면 근사로 충분하다 */
        fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLng = Math.toRadians(lng2 - lng1)
            val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLng / 2) * Math.sin(dLng / 2)
            return 2 * 6_371_000.0 * Math.asin(Math.min(1.0, Math.sqrt(a)))
        }

        /** 실시간 API(btrainSttus)가 급행/특급을 실제로 구분해 주는 노선들 */
        private val EXPRESS_CAPABLE_LINES =
            setOf("1호선", "9호선", "경의선", "공항철도", "수인분당선", "경춘선", "서해선")
    }
}
