package dev.hansw.catchmyride.spike.adapter

import dev.hansw.catchmyride.spike.AdapterResult
import dev.hansw.catchmyride.spike.ArrivalInfo
import dev.hansw.catchmyride.spike.SpikeProperties
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.dataformat.xml.XmlMapper
import java.net.URI

/**
 * 서울 버스 도착정보 (TOPIS / 공공데이터포털 "서울특별시_정류소정보조회 서비스").
 * 정류소(arsId) 기준으로 해당 정류소를 지나는 전 노선의 1·2번째 도착 예정을 조회한다.
 * 엔드포인트·필드는 스파이크에서 실측 검증 대상.
 */
@Component
class TopisBusAdapter(private val props: SpikeProperties) {

    private val restClient = RestClient.create()
    private val xmlMapper = XmlMapper.builder().build()

    fun fetchArrivals(arsId: String): AdapterResult {
        // 공공데이터포털 Encoding 키를 그대로 삽입하므로 재인코딩을 피하기 위해 URI.create 사용
        val url = "http://ws.bus.go.kr/api/rest/stationinfo/getStationByUid" +
            "?serviceKey=${props.keys.dataGoKr}&arsId=$arsId"
        val body = restClient.get().uri(URI.create(url)).retrieve().body(String::class.java).orEmpty()

        val root = xmlMapper.readTree(body)
        val items = root.path("msgBody").path("itemList").asItemList()

        val arrivals = items.flatMap { item ->
            val routeName = item.textOrNull("rtNm")
            listOfNotNull(
                item.toArrival(arsId, routeName, msgField = "arrmsg1", timeField = "traTime1"),
                item.toArrival(arsId, routeName, msgField = "arrmsg2", timeField = "traTime2"),
            )
        }
        return AdapterResult(arrivals, body)
    }

    private fun JsonNode.toArrival(arsId: String, routeName: String?, msgField: String, timeField: String): ArrivalInfo? {
        val message = textOrNull(msgField) ?: return null
        return ArrivalInfo(
            source = "TOPIS",
            stopId = arsId,
            routeName = routeName,
            direction = null,
            predictedSecondsToArrival = textOrNull(timeField)?.toIntOrNull(),
            remainingStops = REMAINING_STOPS.find(message)?.groupValues?.get(1)?.toIntOrNull(),
            isExpress = null,
            rawMessage = message,
        )
    }

    companion object {
        // arrmsg 예: "3분후[2번째 전]" — 대괄호 안 남은 정류장 수 추출
        private val REMAINING_STOPS = Regex("""\[(\d+)번째 전]""")
    }
}

/** itemList가 1건이면 XML 특성상 배열이 아닌 단일 객체로 파싱된다. */
internal fun JsonNode.asItemList(): List<JsonNode> = when {
    isMissingNode || isNull -> emptyList()
    isArray -> toList()
    else -> listOf(this)
}

internal fun JsonNode.textOrNull(field: String): String? =
    path(field).asString("").ifBlank { null }
