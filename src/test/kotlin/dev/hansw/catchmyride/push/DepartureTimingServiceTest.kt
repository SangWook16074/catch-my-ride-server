package dev.hansw.catchmyride.push

import dev.hansw.catchmyride.arrivals.Arrival
import dev.hansw.catchmyride.arrivals.ArrivalStatus
import dev.hansw.catchmyride.commute.CommuteSetting
import dev.hansw.catchmyride.commute.CommuteWindow
import dev.hansw.catchmyride.commute.GeoPoint
import dev.hansw.catchmyride.commute.CommuteStop
import dev.hansw.catchmyride.commute.NotificationMode
import dev.hansw.catchmyride.stops.StopType
import org.junit.jupiter.api.Test
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * S-4 출발 타이밍 계산 명세 (TDD 수칙 — 이 테스트가 FR-301/401의 명세다).
 * 시각 경계: PRE = [출발−버퍼, 출발−1분), REMIND = [출발−1분, 출발+1분) — 30초 틱 지연 흡수.
 */
class DepartureTimingServiceTest {

    private val service = DepartureTimingService()

    // --- FIXED (ⓐ 정시, FR-401) — 출발 08:20, 버퍼 3분 ---

    @Test
    fun `FIXED - 버퍼 진입 전에는 알림 없음`() {
        assertNull(service.decide(fixedSetting(), LocalTime.of(8, 16, 59), emptyList()))
    }

    @Test
    fun `FIXED - 출발-버퍼부터 출발-1분 전까지는 PRE`() {
        assertEquals(PushStage.PRE, service.decide(fixedSetting(), LocalTime.of(8, 17, 0), emptyList())?.stage)
        assertEquals(PushStage.PRE, service.decide(fixedSetting(), LocalTime.of(8, 18, 59), emptyList())?.stage)
    }

    @Test
    fun `FIXED - 출발-1분부터는 REMIND, 출발+1분이 지나면 종료`() {
        assertEquals(PushStage.REMIND, service.decide(fixedSetting(), LocalTime.of(8, 19, 0), emptyList())?.stage)
        assertEquals(PushStage.REMIND, service.decide(fixedSetting(), LocalTime.of(8, 20, 59), emptyList())?.stage)
        assertNull(service.decide(fixedSetting(), LocalTime.of(8, 21, 0), emptyList()))
    }

    @Test
    fun `FIXED - 실시간 정보가 없어도 발송된다, 노선은 설정값 폴백`() {
        val decision = service.decide(fixedSetting(), LocalTime.of(8, 19, 30), emptyList())!!
        assertEquals("9호선 급행", decision.routeName)
        assertNull(decision.minutesToArrival)
        assertEquals(LocalTime.of(8, 20), decision.departureTime)
    }

    @Test
    fun `FIXED - 도착 정보가 있으면 탑승 가능한 최근접 차량이 템플릿 변수가 된다`() {
        val arrivals = listOf(arrival("720", 900), arrival("9호선 급행", 600), arrival("261", 300)) // 300s는 도보 480s 미달 → 탑승 불가
        val decision = service.decide(fixedSetting(), LocalTime.of(8, 17, 30), arrivals)!!
        assertEquals("9호선 급행", decision.routeName)
        assertEquals(10, decision.minutesToArrival)
    }

    // --- RECOMMENDED (ⓑ 추천, FR-301) — 시간대 07:30~09:00, 도보 8분, 버퍼 3분 ---

    @Test
    fun `RECOMMENDED - 출근 시간대 밖에서는 알림 없음`() {
        val arrivals = listOf(arrival("720", 520))
        assertNull(service.decide(recommendedSetting(), LocalTime.of(7, 29, 59), arrivals))
        assertNull(service.decide(recommendedSetting(), LocalTime.of(9, 0, 1), arrivals))
    }

    @Test
    fun `RECOMMENDED - 아직 여유가 버퍼보다 크면 알림 없음`() {
        // 900s 도착 − 480s 도보 = 420s 여유 > 버퍼 180s
        assertNull(service.decide(recommendedSetting(), LocalTime.of(8, 0), listOf(arrival("720", 900))))
    }

    @Test
    fun `RECOMMENDED - 여유가 버퍼 이내면 PRE, 출발 시각은 역산된다`() {
        // 600s 도착 − 480s 도보 = 120s 여유 ≤ 180s
        val decision = service.decide(recommendedSetting(), LocalTime.of(8, 0), listOf(arrival("720", 600)))!!
        assertEquals(PushStage.PRE, decision.stage)
        assertEquals(LocalTime.of(8, 2), decision.departureTime)
        assertEquals("720", decision.routeName)
        assertEquals(10, decision.minutesToArrival)
    }

    @Test
    fun `RECOMMENDED - 여유 1분 이내면 REMIND`() {
        // 540s 도착 − 480s 도보 = 60s
        assertEquals(PushStage.REMIND, service.decide(recommendedSetting(), LocalTime.of(8, 0), listOf(arrival("720", 540)))?.stage)
    }

    @Test
    fun `RECOMMENDED - 탑승 가능한 차량이 없으면 알림 없음 (아는 척 금지)`() {
        val arrivals = listOf(arrival("720", 300), arrival("261", null)) // 놓침 + 정보 없음
        assertNull(service.decide(recommendedSetting(), LocalTime.of(8, 0), arrivals))
    }

    @Test
    fun `RECOMMENDED - 탑승 가능 중 가장 가까운 차량 기준으로 판단한다`() {
        val decision = service.decide(recommendedSetting(), LocalTime.of(8, 0), listOf(arrival("720", 600), arrival("261", 520)))!!
        assertEquals("261", decision.routeName)
    }

    // --- 헬퍼 ---

    private fun fixedSetting(departure: String = "08:20", buffer: Int = 3) = setting(
        mode = NotificationMode.FIXED, fixedDepartureTime = departure, window = null, buffer = buffer,
    )

    private fun recommendedSetting(buffer: Int = 3) = setting(
        mode = NotificationMode.RECOMMENDED, fixedDepartureTime = null,
        window = CommuteWindow("07:30", "09:00"), buffer = buffer,
    )

    private fun setting(mode: NotificationMode, fixedDepartureTime: String?, window: CommuteWindow?, buffer: Int) =
        CommuteSetting(
            home = GeoPoint(37.5219, 126.9245),
            stops = listOf(CommuteStop(StopType.SUBWAY, "여의도", "여의도역", listOf("9호선 급행"))),
            walkMinutes = 8,
            notificationMode = mode,
            fixedDepartureTime = fixedDepartureTime,
            commuteWindow = window,
            bufferMinutes = buffer,
            activeDays = listOf("MON", "TUE", "WED", "THU", "FRI"),
        )

    /** walkMinutes=8(480s) 기준 boardable을 계산해 만든 도착 정보 */
    private fun arrival(route: String, seconds: Int?) = Arrival(
        stopDisplayName = "여의도역",
        routeName = route,
        secondsToArrival = seconds,
        remainingStops = null,
        isExpress = null,
        boardable = seconds != null && seconds >= 480,
        status = ArrivalStatus.RELAXED,
        rawMessage = null,
    )
}
