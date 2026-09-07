package dev.hansw.catchmyride.push

import dev.hansw.catchmyride.arrivals.ArrivalsService
import dev.hansw.catchmyride.commute.CommuteRoute
import dev.hansw.catchmyride.commute.CommuteRouteRepository
import dev.hansw.catchmyride.commute.CommuteSetting
import dev.hansw.catchmyride.commute.CommuteStop
import dev.hansw.catchmyride.commute.GeoPoint
import dev.hansw.catchmyride.commute.NotificationMode
import dev.hansw.catchmyride.stops.StopType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S-5 스케줄러 불변 조건 명세 (TDD 수칙):
 * 경로 1회당 최대 2회(FR-403) · 같은 스테이지 재발송 불가 · 미적용 요일 미발송(FR-405) · 실패 시 다음 틱 재시도.
 * 다중 경로: 경로마다 독립 발송, enabled=false 경로는 제외.
 *
 * 2026-09-01(화) 고정 시계 + 공공 API 키 없음(도착 정보 빈 배열) — FIXED 모드는 시각만으로 발송된다.
 */
@SpringBootTest(properties = ["DATA_GO_KR_KEY=", "SEOUL_OPEN_DATA_KEY="])
class PushNotificationSchedulerTest {

    @Autowired lateinit var routes: CommuteRouteRepository
    @Autowired lateinit var pushLog: PushLogRepository
    @Autowired lateinit var arrivalsService: ArrivalsService
    @Autowired lateinit var jdbc: JdbcClient

    private val clock = MutableClock()
    private val client = RecordingPushClient()

    @BeforeEach
    fun wipe() {
        // 공유 H2에 다른 테스트가 남긴 경로가 tick() 순회에 섞이지 않게 전부 비운다
        jdbc.sql("DELETE FROM commute_route").update()
        jdbc.sql("DELETE FROM push_log").update()
    }

    private fun scheduler() =
        PushNotificationScheduler(
            routes, pushLog, arrivalsService, DepartureTimingService(), client,
            HolidayCalendar(PushProperties()), clock,
        )

    @Test
    fun `경로 1회당 PRE·REMIND 각 1회, 총 2회를 넘지 않는다`() {
        val userKey = "push-test-invariant"
        routes.insert(userKey, route("r1", "출근", fixedSetting(activeDays = listOf("TUE"))))
        val scheduler = scheduler()

        clock.set("2026-09-01T08:16:30") // 버퍼(3분) 진입 전
        scheduler.tick()
        assertEquals(0, client.sends.size)

        clock.set("2026-09-01T08:17:30") // PRE 구간
        scheduler.tick()
        scheduler.tick() // 같은 구간 재틱 — 중복 발송 금지
        assertEquals(1, client.sends.size)
        assertEquals(PushStage.PRE, client.sends[0].stage)

        clock.set("2026-09-01T08:19:30") // REMIND 구간
        scheduler.tick()
        scheduler.tick()
        clock.set("2026-09-01T08:20:30")
        scheduler.tick()
        assertEquals(2, client.sends.size, "경로 1회 최대 2회 (FR-403): ${client.sends}")
        assertEquals(PushStage.REMIND, client.sends[1].stage)

        // dry-run이어도 발송 이력은 남는다 (§3 피드백 검증·North Star 측정의 근거)
        assertTrue(pushLog.hasAny(userKey, clock.today()))
        assertEquals(setOf(PushStage.PRE, PushStage.REMIND), pushLog.sentStages(userKey, "r1", clock.today()))
    }

    @Test
    fun `경로가 여러 개면 각 경로가 독립으로 최대 2회씩 발송된다`() {
        val userKey = "push-test-multi-route"
        routes.insert(userKey, route("r-go", "출근", fixedSetting(activeDays = listOf("TUE"), departure = "08:20")))
        routes.insert(userKey, route("r-back", "퇴근", fixedSetting(activeDays = listOf("TUE"), departure = "18:30")))
        val scheduler = scheduler()

        clock.set("2026-09-01T08:19:30") // 출근 REMIND 구간 — 퇴근은 시간대 밖
        scheduler.tick()
        assertEquals(1, client.sends.size)
        assertEquals(setOf(PushStage.REMIND), pushLog.sentStages(userKey, "r-go", clock.today()))
        assertEquals(emptySet<PushStage>(), pushLog.sentStages(userKey, "r-back", clock.today()))

        clock.set("2026-09-01T18:29:30") // 퇴근 REMIND 구간 — 출근이 이미 발송된 날이어도 독립 발송
        scheduler.tick()
        scheduler.tick() // 재틱 중복 금지도 경로 단위
        assertEquals(2, client.sends.size, "경로별 독립 발송: ${client.sends}")
        assertEquals(setOf(PushStage.REMIND), pushLog.sentStages(userKey, "r-back", clock.today()))
    }

    @Test
    fun `enabled=false 경로는 발송하지 않는다`() {
        routes.insert(
            "push-test-disabled",
            route("r1", "출근", fixedSetting(activeDays = listOf("TUE")), enabled = false),
        )
        clock.set("2026-09-01T08:19:30")
        scheduler().tick()
        assertEquals(0, client.sends.size, "중지된 경로 발송 금지: ${client.sends}")
    }

    @Test
    fun `activeDays에 없는 요일은 발송하지 않는다`() {
        routes.insert("push-test-day-off", route("r1", "출근", fixedSetting(activeDays = listOf("MON")))) // 화요일 제외
        clock.set("2026-09-01T08:19:30")
        scheduler().tick()
        assertEquals(0, client.sends.size, "FR-405 위반: ${client.sends}")
    }

    @Test
    fun `평일만 출근하는 경로는 공휴일에 발송하지 않는다`() {
        // 2026-10-05(월)은 개천절 대체공휴일 — activeDays에 MON이 있어도 쉰다 (FR-405 확장)
        routes.insert("push-test-holiday", route("r1", "출근", fixedSetting(activeDays = listOf("MON"))))
        clock.set("2026-10-05T08:19:30")
        scheduler().tick()
        assertEquals(0, client.sends.size, "공휴일 미발송 위반: ${client.sends}")
    }

    @Test
    fun `주말이 포함된 경로는 공휴일에도 발송한다`() {
        // 교대 근무처럼 달력을 따르지 않는 경로 — 공휴일 스킵을 적용하지 않는다
        routes.insert("push-test-holiday-shift", route("r1", "출근", fixedSetting(activeDays = listOf("MON", "SAT"))))
        clock.set("2026-10-05T08:19:30")
        scheduler().tick()
        assertEquals(1, client.sends.size, "주말 포함 경로는 공휴일에도 발송: ${client.sends}")
    }

    @Test
    fun `발송 실패 시 이력이 롤백돼 다음 틱에 재시도한다`() {
        val userKey = "push-test-retry"
        routes.insert(userKey, route("r1", "출근", fixedSetting(activeDays = listOf("TUE"))))
        val scheduler = scheduler()
        clock.set("2026-09-01T08:19:30")

        client.failNext = true
        scheduler.tick()
        assertEquals(0, client.sends.size)
        assertEquals(emptySet<PushStage>(), pushLog.sentStages(userKey, "r1", clock.today()))

        scheduler.tick() // 다음 틱 — 성공
        assertEquals(1, client.sends.size)
        assertEquals(setOf(PushStage.REMIND), pushLog.sentStages(userKey, "r1", clock.today()))
    }

    // --- 헬퍼 ---

    private fun route(id: String, label: String, setting: CommuteSetting, enabled: Boolean = true) =
        CommuteRoute(id = id, label = label, enabled = enabled, setting = setting)

    private fun fixedSetting(activeDays: List<String>, departure: String = "08:20") = CommuteSetting(
        home = GeoPoint(37.5219, 126.9245),
        stops = listOf(CommuteStop(StopType.SUBWAY, "여의도", "여의도역", listOf("9호선 급행"))),
        walkMinutes = 8,
        notificationMode = NotificationMode.FIXED,
        fixedDepartureTime = departure,
        commuteWindow = null,
        bufferMinutes = 3,
        activeDays = activeDays,
    )

    private inner class RecordingPushClient : AppsInTossPushClient(PushProperties()) {
        val sends = mutableListOf<PushDecision>()
        var failNext = false
        override fun send(userKey: String, decision: PushDecision): Boolean {
            if (failNext) {
                failNext = false
                throw RuntimeException("발송 실패(테스트)")
            }
            sends += decision
            return false // dry-run과 동일 — delivered=false 기록
        }
    }

    /** Asia/Seoul 고정 가변 시계 — 테스트가 시각을 전진시키며 틱을 재현한다 */
    private class MutableClock : Clock() {
        private val zone = ZoneId.of("Asia/Seoul")
        private var current: Instant = Instant.parse("2026-09-01T00:00:00Z")
        fun set(seoulLocal: String) {
            current = ZonedDateTime.of(java.time.LocalDateTime.parse(seoulLocal), zone).toInstant()
        }
        fun today(): java.time.LocalDate = ZonedDateTime.ofInstant(current, zone).toLocalDate()
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = current
    }
}
