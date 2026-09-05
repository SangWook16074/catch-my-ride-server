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

    // --- RECOMMENDED 배차 외삽 (도보 > 실시간 수평선 — 지하철 피드는 약 10분 이내 열차만 준다) ---

    @Test
    fun `RECOMMENDED - 도보가 길어 탑승 가능 차가 없으면 같은 방면 배차 간격으로 다음 차를 추정한다`() {
        // 도보 20분(1200s), 상행 4분·8분 → 배차 240s → 외삽 12·16·20분 → 20분(1200s) 차가 첫 탑승 가능
        val arrivals = listOf(
            arrival("9호선 급행", 240, direction = "상행", walkSeconds = 1200),
            arrival("9호선 급행", 480, direction = "상행", walkSeconds = 1200),
        )
        val decision = service.decide(recommendedSetting(walk = 20), LocalTime.of(8, 0), arrivals)!!
        assertEquals(PushStage.REMIND, decision.stage) // 여유 0초 — 지금 나가면 딱 맞는다
        assertEquals("9호선 급행", decision.routeName)
        assertEquals(20, decision.minutesToArrival)
        assertEquals(true, decision.estimated)
    }

    @Test
    fun `RECOMMENDED - 추정 차까지의 여유가 버퍼 이내면 PRE`() {
        // 도보 10분(600s), 상행 3분·9분 → 배차 360s → 외삽 15분(900s) → 여유 300s ≤ 버퍼 300s
        val arrivals = listOf(
            arrival("9호선 급행", 180, direction = "상행", walkSeconds = 600),
            arrival("9호선 급행", 540, direction = "상행", walkSeconds = 600),
        )
        val decision = service.decide(recommendedSetting(buffer = 5, walk = 10), LocalTime.of(8, 0), arrivals)!!
        assertEquals(PushStage.PRE, decision.stage)
        assertEquals(LocalTime.of(8, 5), decision.departureTime)
        assertEquals(15, decision.minutesToArrival)
        assertEquals(true, decision.estimated)
    }

    @Test
    fun `RECOMMENDED - 방면 정보가 없으면 추정하지 않는다 (틀린 확신보다 침묵)`() {
        val arrivals = listOf(
            arrival("9호선 급행", 240, walkSeconds = 1200),
            arrival("9호선 급행", 480, walkSeconds = 1200),
        )
        assertNull(service.decide(recommendedSetting(walk = 20), LocalTime.of(8, 0), arrivals))
    }

    @Test
    fun `RECOMMENDED - 같은 방면 열차가 1대뿐이면 배차를 알 수 없어 추정하지 않는다`() {
        val arrivals = listOf(
            arrival("9호선 급행", 240, direction = "상행", walkSeconds = 1200),
            arrival("9호선 급행", 480, direction = "하행", walkSeconds = 1200),
        )
        assertNull(service.decide(recommendedSetting(walk = 20), LocalTime.of(8, 0), arrivals))
    }

    @Test
    fun `RECOMMENDED - 탑승 가능한 차가 있으면 추정 없이 실시간 값을 쓴다`() {
        val decision = service.decide(recommendedSetting(), LocalTime.of(8, 0), listOf(arrival("720", 600)))!!
        assertEquals(false, decision.estimated)
    }

    // --- 헬퍼 ---

    private fun fixedSetting(departure: String = "08:20", buffer: Int = 3) = setting(
        mode = NotificationMode.FIXED, fixedDepartureTime = departure, window = null, buffer = buffer,
    )

    private fun recommendedSetting(buffer: Int = 3, walk: Int = 8) = setting(
        mode = NotificationMode.RECOMMENDED, fixedDepartureTime = null,
        window = CommuteWindow("07:30", "09:00"), buffer = buffer, walk = walk,
    )

    private fun setting(mode: NotificationMode, fixedDepartureTime: String?, window: CommuteWindow?, buffer: Int, walk: Int = 8) =
        CommuteSetting(
            home = GeoPoint(37.5219, 126.9245),
            stops = listOf(CommuteStop(StopType.SUBWAY, "여의도", "여의도역", listOf("9호선 급행"))),
            walkMinutes = walk,
            notificationMode = mode,
            fixedDepartureTime = fixedDepartureTime,
            commuteWindow = window,
            bufferMinutes = buffer,
            activeDays = listOf("MON", "TUE", "WED", "THU", "FRI"),
        )

    /** walkSeconds 기준 boardable을 계산해 만든 도착 정보 (기본 도보 8분=480s) */
    private fun arrival(route: String, seconds: Int?, direction: String? = null, walkSeconds: Int = 480) = Arrival(
        stopDisplayName = "여의도역",
        routeName = route,
        direction = direction,
        secondsToArrival = seconds,
        remainingStops = null,
        isExpress = null,
        boardable = seconds != null && seconds >= walkSeconds,
        status = ArrivalStatus.RELAXED,
        rawMessage = null,
    )
}
