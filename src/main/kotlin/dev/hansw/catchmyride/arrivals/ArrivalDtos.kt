package dev.hansw.catchmyride.arrivals

/** API.md §2 응답 — 필드 의미는 openapi.yaml/API.md 참조 */
data class ArrivalsResponse(
    val fetchedAt: String,          // ISO-8601 (+09:00) — 화면 "마지막 갱신 시각"
    val realtimeAvailable: Boolean, // false = 공공 API 전체 장애 → "실시간 정보 없음" 표시 (NFR-03)
    val walkMinutes: Int,
    val arrivals: List<Arrival>,
)

data class Arrival(
    val stopDisplayName: String,
    val routeName: String,          // 통근 설정에 저장된 표기 그대로
    val secondsToArrival: Int?,     // null = 실시간 정보 없음
    val remainingStops: Int?,
    val isExpress: Boolean?,        // 지하철만, 버스는 null (FR-203)
    val boardable: Boolean,         // secondsToArrival ≥ 도보 시간 (FR-303/304)
    val status: ArrivalStatus,
    val rawMessage: String?,
)

enum class ArrivalStatus { RELAXED, HURRY, MISSED }
