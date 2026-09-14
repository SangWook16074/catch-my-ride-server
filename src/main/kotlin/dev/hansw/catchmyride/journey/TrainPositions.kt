package dev.hansw.catchmyride.journey

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
 * §9 추적용 열차 스냅샷 — 역 기준 접근 열차 목록 (테스트는 fake로 대체).
 * 역 순서 데이터 없이도 추적이 되는 이유: realtimeStationArrival이 열차별로
 * "그 역까지 몇 정거장 전인지"(arvlCd·arvlMsg2)와 열차 번호(btrainNo)를 준다.
 */
interface TrainPositions {
    /** 해당 역으로 접근 중인 열차들. 상류 오류는 예외 — 호출부가 강등(FR-706) */
    fun approaching(stationName: String): List<ApproachingTrain>
}

data class ApproachingTrain(
    val trainNo: String,
    val line: String?,        // "9호선" — SubwayStationCatalog 표기
    val isExpress: Boolean?,  // btrainSttus 급행/특급
    val arvlCd: String?,      // 0접근 1도착 2출발 3전역출발 4전역진입 5전역도착 99운행중
    val message: String?,     // arvlMsg2 — "[3]번째 전역 (홍제)" 등
    val secondsToArrival: Int?,
) {
    /**
     * 이 역(하차역)까지 남은 정거장 — arvlCd 우선, 운행중(99)은 메시지의 "N번째 전역" 파싱.
     * 모르면 null — 아는 척하지 않는다 (NFR-03)
     */
    val stationsAway: Int? get() = when (arvlCd) {
        "0", "1", "2" -> 0
        "3", "4", "5" -> 1
        else -> STATIONS_AWAY.find(message ?: "")?.groupValues?.get(1)?.toIntOrNull()
    }

    companion object {
        private val STATIONS_AWAY = Regex("""\[?(\d+)]?\s*번째 전역""")
    }
}

/**
 * 서울열린데이터광장 realtimeStationArrival 구현 — SeoulSubwayAdapter와 같은 상류지만
 * 추적에 필요한 필드(btrainNo·arvlCd)를 별도로 파싱한다. 키 미설정이면 빈 목록(강등 규칙).
 */
@Component
class SeoulTrainPositions(
    private val props: SpikeProperties,
    private val objectMapper: ObjectMapper,
) : TrainPositions {

    private val restClient = RestClient.create()

    override fun approaching(stationName: String): List<ApproachingTrain> {
        val key = props.keys.seoulOpenData
        if (key.isBlank()) {
            return emptyList() // 키 미설정 — dry 강등 (FcmPushClient와 동일 규칙)
        }
        val encoded = URLEncoder.encode(stationName, StandardCharsets.UTF_8)
        val url = "http://swopenapi.seoul.go.kr/api/subway/$key/json/realtimeStationArrival/0/20/$encoded"
        val body = restClient.get().uri(URI.create(url)).retrieve().body(String::class.java).orEmpty()
        val root = objectMapper.readTree(body)
        val errorCode = root.path("code").asString("")
        if (errorCode.startsWith("ERROR")) {
            throw IllegalStateException("서울 지하철 API $errorCode: ${root.path("message").asString("")}".trim())
        }
        return root.path("realtimeArrivalList").asItemList().mapNotNull { item ->
            val trainNo = item.textOrNull("btrainNo") ?: return@mapNotNull null
            val trainStatus = item.textOrNull("btrainSttus")
            ApproachingTrain(
                trainNo = trainNo,
                line = item.textOrNull("subwayId")?.let { LINE_BY_SUBWAY_ID[it] },
                isExpress = trainStatus?.let { it == "급행" || it == "특급" },
                arvlCd = item.textOrNull("arvlCd"),
                message = item.textOrNull("arvlMsg2"),
                secondsToArrival = item.textOrNull("barvlDt")?.toIntOrNull()?.takeIf { it > 0 },
            )
        }
    }

    companion object {
        /** subwayId → 호선명 — SeoulSubwayAdapter와 동일 표 (카탈로그 노선 표기 기준) */
        private val LINE_BY_SUBWAY_ID = mapOf(
            "1001" to "1호선", "1002" to "2호선", "1003" to "3호선", "1004" to "4호선",
            "1005" to "5호선", "1006" to "6호선", "1007" to "7호선", "1008" to "8호선",
            "1009" to "9호선", "1032" to "GTX-A", "1063" to "경의선", "1065" to "공항철도",
            "1067" to "경춘선", "1075" to "수인분당선", "1077" to "신분당선", "1081" to "경강선",
            "1092" to "우이신설경전철", "1093" to "서해선", "1094" to "신림선",
        )
    }
}

/** 구간 노선("9호선 급행")과 열차의 노선·급행 여부 매칭 — ArrivalsService와 같은 규칙 */
fun ApproachingTrain.matchesLine(legLine: String): Boolean {
    val base = legLine.removeSuffix(" 급행").removeSuffix(" 일반")
    if (line != base) {
        return false
    }
    return when {
        legLine.endsWith("급행") -> isExpress == true
        legLine.endsWith("일반") -> isExpress != true
        else -> true
    }
}
