package dev.hansw.catchmyride.stops

import dev.hansw.catchmyride.spike.SpikeProperties
import dev.hansw.catchmyride.spike.adapter.asItemList
import dev.hansw.catchmyride.spike.adapter.textOrNull
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.dataformat.xml.XmlMapper
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 서울 버스 정류소 검색·경유 노선 (TOPIS "서울특별시_정류소정보조회 서비스").
 * 활용신청 승인 전에는 401이 온다 — 서비스 레이어가 소스 단위로 무시하고 나머지 소스만 응답한다.
 */
@Component
class SeoulBusStopClient(private val props: SpikeProperties) {

    private val restClient = RestClient.create()
    private val xmlMapper = XmlMapper.builder().build()

    fun available() = props.keys.dataGoKr.isNotBlank()

    fun search(query: String, limit: Int): List<StopSearchResult> {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val url = "http://ws.bus.go.kr/api/rest/stationinfo/getStationByName" +
            "?serviceKey=${props.keys.dataGoKr}&stSrch=$encoded"
        return parseSearch(fetch(url), limit)
    }

    fun routes(arsId: String): List<RouteResult> {
        val url = "http://ws.bus.go.kr/api/rest/stationinfo/getStationByUid" +
            "?serviceKey=${props.keys.dataGoKr}&arsId=$arsId"
        return parseRoutes(fetch(url))
    }

    private fun fetch(url: String): String =
        restClient.get().uri(URI.create(url)).retrieve().body(String::class.java).orEmpty()

    internal fun parseSearch(body: String, limit: Int): List<StopSearchResult> =
        xmlMapper.readTree(body).path("msgBody").path("itemList").asItemList()
            .mapNotNull { item ->
                val name = item.textOrNull("stNm") ?: return@mapNotNull null
                // arsId "0"은 가상 정류장(미사용) — 도착 조회가 안 되므로 제외
                val arsId = item.textOrNull("arsId")?.takeIf { it != "0" } ?: return@mapNotNull null
                StopSearchResult(StopType.SEOUL_BUS, stopId = arsId, displayName = name, subtitle = "서울 · $arsId")
            }
            .take(limit)

    internal fun parseRoutes(body: String): List<RouteResult> =
        xmlMapper.readTree(body).path("msgBody").path("itemList").asItemList()
            .mapNotNull { it.textOrNull("rtNm") }
            .distinct()
            .map { RouteResult(name = it, isExpress = null) }
}
