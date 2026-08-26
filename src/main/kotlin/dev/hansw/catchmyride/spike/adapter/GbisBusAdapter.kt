package dev.hansw.catchmyride.spike.adapter

import dev.hansw.catchmyride.spike.AdapterResult
import dev.hansw.catchmyride.spike.ArrivalInfo
import dev.hansw.catchmyride.spike.SpikeProperties
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI

/**
 * 경기도 버스 도착정보 (공공데이터포털 "경기도_버스도착정보 조회 v2").
 * 정류소(stationId) 기준 전 노선의 1·2번째 도착 예정. predictTime은 '분' 단위.
 * 엔드포인트·필드는 스파이크에서 실측 검증 대상.
 */
@Component
class GbisBusAdapter(
    private val props: SpikeProperties,
    private val objectMapper: ObjectMapper,
) {

    private val restClient = RestClient.create()

    fun fetchArrivals(stationId: String): AdapterResult {
        val url = "https://apis.data.go.kr/6410000/busarrivalservice/v2/getBusArrivalListv2" +
            "?serviceKey=${props.keys.dataGoKr}&stationId=$stationId&format=json"
        val body = restClient.get().uri(URI.create(url)).retrieve().body(String::class.java).orEmpty()

        val root = objectMapper.readTree(body)
        val items = root.path("response").path("msgBody").path("busArrivalList").asItemList()

        val arrivals = items.flatMap { item ->
            val routeName = item.textOrNull("routeName") ?: item.textOrNull("routeId")
            listOfNotNull(
                item.toArrival(stationId, routeName, minuteField = "predictTime1", stopsField = "locationNo1"),
                item.toArrival(stationId, routeName, minuteField = "predictTime2", stopsField = "locationNo2"),
            )
        }
        return AdapterResult(arrivals, body)
    }

    private fun JsonNode.toArrival(stationId: String, routeName: String?, minuteField: String, stopsField: String): ArrivalInfo? {
        val minutes = textOrNull(minuteField)?.toIntOrNull() ?: return null
        return ArrivalInfo(
            source = "GBIS",
            stopId = stationId,
            routeName = routeName,
            direction = null,
            predictedSecondsToArrival = minutes * 60,
            remainingStops = textOrNull(stopsField)?.toIntOrNull(),
            isExpress = null,
            rawMessage = "${minutes}분 후",
        )
    }
}
