package dev.hansw.catchmyride.stops

import dev.hansw.catchmyride.spike.SpikeProperties
import dev.hansw.catchmyride.spike.adapter.asItemList
import dev.hansw.catchmyride.spike.adapter.textOrNull
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 경기도 버스 정류소 검색·경유 노선 (GBIS "경기도 정류소 조회" v2).
 * 🧑 활용신청 전에는 401 — 서비스 레이어가 소스 단위로 무시한다. 승인 후 응답 필드 실측 검증할 것.
 */
@Component
class GbisStopClient(
    private val props: SpikeProperties,
    private val objectMapper: ObjectMapper,
) {

    private val restClient = RestClient.create()

    fun available() = props.keys.dataGoKr.isNotBlank()

    fun search(query: String, limit: Int): List<StopSearchResult> {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val url = "https://apis.data.go.kr/6410000/busstationservice/v2/getBusStationListv2" +
            "?serviceKey=${props.keys.dataGoKr}&keyword=$encoded&format=json"
        return parseSearch(fetch(url), limit)
    }

    fun routes(stationId: String): List<RouteResult> {
        val url = "https://apis.data.go.kr/6410000/busstationservice/v2/getBusStationViaRouteListv2" +
            "?serviceKey=${props.keys.dataGoKr}&stationId=$stationId&format=json"
        return parseRoutes(fetch(url))
    }

    private fun fetch(url: String): String =
        restClient.get().uri(URI.create(url)).retrieve().body(String::class.java).orEmpty()

    internal fun parseSearch(body: String, limit: Int): List<StopSearchResult> =
        objectMapper.readTree(body).path("response").path("msgBody").path("busStationList").asItemList()
            .mapNotNull { item ->
                val name = item.textOrNull("stationName") ?: return@mapNotNull null
                val stationId = item.textOrNull("stationId") ?: return@mapNotNull null
                // 실측: mobileNo가 " 19152"처럼 앞 공백을 달고 온다 — trim 필수
                val subtitle = listOfNotNull(item.textOrNull("regionName")?.trim(), item.textOrNull("mobileNo")?.trim())
                    .filter { it.isNotEmpty() }
                    .joinToString(" · ")
                StopSearchResult(StopType.GYEONGGI_BUS, stopId = stationId, displayName = name, subtitle = subtitle)
            }
            .take(limit)

    internal fun parseRoutes(body: String): List<RouteResult> =
        objectMapper.readTree(body).path("response").path("msgBody").path("busRouteList").asItemList()
            .mapNotNull { it.textOrNull("routeName") }
            .distinct()
            .map { RouteResult(name = it, isExpress = null) }
}
