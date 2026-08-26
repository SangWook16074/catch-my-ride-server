package dev.hansw.catchmyride.spike.adapter

import dev.hansw.catchmyride.spike.AdapterResult
import dev.hansw.catchmyride.spike.ArrivalInfo
import dev.hansw.catchmyride.spike.SpikeProperties
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 서울 지하철 실시간 도착 (서울열린데이터광장 realtimeStationArrival).
 * 역명 기준 조회. barvlDt(도착까지 초)는 "0"으로 오는 경우가 있어 실측 검증 대상.
 * 급행/일반 구분은 btrainSttus 필드("급행"/"특급"/"일반") — FR-203의 핵심 확인 항목.
 */
@Component
class SeoulSubwayAdapter(
    private val props: SpikeProperties,
    private val objectMapper: ObjectMapper,
) {

    private val restClient = RestClient.create()

    fun fetchArrivals(stationName: String): AdapterResult {
        // 역명은 한글이므로 URI에 넣기 전에 인코딩 필수
        val encodedStation = URLEncoder.encode(stationName, StandardCharsets.UTF_8)
        val url = "http://swopenapi.seoul.go.kr/api/subway/${props.keys.seoulOpenData}" +
            "/json/realtimeStationArrival/0/10/$encodedStation"
        val body = restClient.get().uri(URI.create(url)).retrieve().body(String::class.java).orEmpty()

        val root = objectMapper.readTree(body)
        val arrivals = root.path("realtimeArrivalList").asItemList().map { item ->
            val trainStatus = item.textOrNull("btrainSttus")
            ArrivalInfo(
                source = "SEOUL_SUBWAY",
                stopId = stationName,
                routeName = item.textOrNull("trainLineNm"),
                direction = item.textOrNull("updnLine"),
                predictedSecondsToArrival = item.textOrNull("barvlDt")?.toIntOrNull()?.takeIf { it > 0 },
                remainingStops = null,
                isExpress = trainStatus?.let { it == "급행" || it == "특급" },
                rawMessage = item.textOrNull("arvlMsg2"),
            )
        }
        return AdapterResult(arrivals, body)
    }
}
