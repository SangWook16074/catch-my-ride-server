package dev.hansw.catchmyride.commute

import dev.hansw.catchmyride.api.ApiException
import dev.hansw.catchmyride.stops.StopType

/**
 * API.md §1 CommuteSetting — 유저당 1개 (MVP).
 * 검증 규칙·문구는 클라이언트 참고 구현(app/src/api/mock.ts)과 동일하게 유지한다.
 */
data class CommuteSetting(
    val home: GeoPoint,
    val stops: List<CommuteStop>,
    val walkMinutes: Int,
    val notificationMode: NotificationMode,
    val fixedDepartureTime: String?,
    val commuteWindow: CommuteWindow?,
    val bufferMinutes: Int,
    val activeDays: List<String>,
) {
    /** 평일 출근 경로 판단 — 공휴일 미발송(FR-405 확장)은 달력대로 일하는 경로에만 적용한다 */
    val weekdaysOnly: Boolean
        get() = activeDays.none { it == "SAT" || it == "SUN" }

    fun validate() {
        if (stops.isEmpty()) throw ApiException.invalidRequest("정류장을 1개 이상 등록해야 합니다")
        stops.firstOrNull { it.routes.isEmpty() }
            ?.let { throw ApiException.invalidRequest("${it.displayName}: 노선을 1개 이상 선택해야 합니다") }
        if (walkMinutes <= 0) throw ApiException.invalidRequest("도보 시간은 1분 이상이어야 합니다")
        if (bufferMinutes < 0) throw ApiException.invalidRequest("여유 버퍼는 0분 이상이어야 합니다")
        if (activeDays.isEmpty()) throw ApiException.invalidRequest("적용 요일을 1개 이상 선택해야 합니다")
        activeDays.firstOrNull { it !in DAYS }
            ?.let { throw ApiException.invalidRequest("요일 표기가 잘못됐습니다: $it") }
        when (notificationMode) {
            NotificationMode.FIXED ->
                if (fixedDepartureTime == null || !TIME_PATTERN.matches(fixedDepartureTime)) {
                    throw ApiException.invalidRequest("정시 모드는 출발 시각(HH:mm)이 필요합니다")
                }
            NotificationMode.RECOMMENDED ->
                if (commuteWindow == null || !TIME_PATTERN.matches(commuteWindow.start) || !TIME_PATTERN.matches(commuteWindow.end)) {
                    throw ApiException.invalidRequest("추천 모드는 출근 시간대(HH:mm~HH:mm)가 필요합니다")
                }
        }
    }

    companion object {
        val TIME_PATTERN = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
        private val DAYS = setOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
    }
}

data class GeoPoint(val latitude: Double, val longitude: Double)

data class CommuteStop(
    val type: StopType,
    val stopId: String,      // §5 검색 결과의 stopId 그대로 (SEOUL_BUS=arsId / GYEONGGI_BUS=stationId / SUBWAY=역명)
    val displayName: String,
    val routes: List<String>, // §5-2 routes[].name 문자열 그대로 — §2 arrivals의 routeName과 같은 표기
)

data class CommuteWindow(val start: String, val end: String)

enum class NotificationMode { FIXED, RECOMMENDED }
