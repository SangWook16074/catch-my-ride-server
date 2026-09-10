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
        // 쿼터 초과(ERROR-337) 등 에러 응답은 realtimeArrivalList가 없어 "빈 목록"으로 보였다 —
        // 2026-09-10 전면 미표시 사고. 에러는 던져서 realtimeAvailable=false로 정직하게 알린다 (NFR-03).
        // INFO-200(해당 데이터 없음)은 정상적인 빈 결과라 그대로 통과.
        val errorCode = root.path("code").asString("")
        if (errorCode.startsWith("ERROR")) {
            throw IllegalStateException("서울 지하철 API $errorCode: ${root.path("message").asString("")}".trim())
        }
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
                line = item.textOrNull("subwayId")?.let { LINE_BY_SUBWAY_ID[it] },
            )
        }
        return AdapterResult(arrivals, body)
    }

    companion object {
        /** subwayId → 호선명 — SubwayStationCatalog(data/subway-stations.json)의 노선 표기와 일치시킨다. */
        private val LINE_BY_SUBWAY_ID = mapOf(
            "1001" to "1호선", "1002" to "2호선", "1003" to "3호선", "1004" to "4호선",
            "1005" to "5호선", "1006" to "6호선", "1007" to "7호선", "1008" to "8호선",
            "1009" to "9호선", "1032" to "GTX-A", "1063" to "경의선", "1065" to "공항철도",
            "1067" to "경춘선", "1075" to "수인분당선", "1077" to "신분당선", "1081" to "경강선",
            "1092" to "우이신설경전철", "1093" to "서해선", "1094" to "신림선",
        )
    }
}
