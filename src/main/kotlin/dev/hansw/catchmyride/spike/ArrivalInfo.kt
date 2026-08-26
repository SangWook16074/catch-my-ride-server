package dev.hansw.catchmyride.spike

/**
 * 데이터 소스와 무관한 공통 도착 정보. 본편 서버 어댑터 계층의 초안이기도 하다.
 * 스파이크 단계에서는 필드 대부분이 nullable — 소스별로 어떤 필드가 실제로
 * 채워지는지 확인하는 것 자체가 실측 목표이기 때문.
 */
data class ArrivalInfo(
    val source: String,                     // TOPIS | GBIS | SEOUL_SUBWAY
    val stopId: String,                     // arsId / stationId / 역명
    val routeName: String?,                 // 예: "720", "방화행 - 마천방면"
    val direction: String?,                 // 지하철 상행/하행
    val predictedSecondsToArrival: Int?,    // 도착까지 남은 초 (API 제공 시)
    val remainingStops: Int?,               // 남은 정류장 수 (버스)
    val isExpress: Boolean?,                // 급행 여부 (지하철)
    val rawMessage: String?,                // "3분후[2번째 전]" 같은 원문 도착 문구
)

/** 어댑터 호출 1회의 결과. rawBody는 파싱 누락 검증용으로 함께 보존한다. */
data class AdapterResult(
    val arrivals: List<ArrivalInfo>,
    val rawBody: String,
)

/** JSONL 한 줄 = 폴링 1회(소스×정류장)의 스냅샷. */
data class ArrivalLogRecord(
    val collectedAt: String, // ISO-8601 (Asia/Seoul offset 포함)
    val source: String,
    val stopId: String,
    val ok: Boolean,
    val error: String?,
    val arrivals: List<ArrivalInfo>,
    val rawBody: String?,
)
