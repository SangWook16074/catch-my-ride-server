package dev.hansw.catchmyride.push

import dev.hansw.catchmyride.arrivals.Arrival
import dev.hansw.catchmyride.arrivals.ArrivalStatus
import dev.hansw.catchmyride.arrivals.ArrivalsResponse
import dev.hansw.catchmyride.arrivals.ArrivalsService
import dev.hansw.catchmyride.commute.CommuteRoute
import dev.hansw.catchmyride.commute.CommuteRouteRepository
import dev.hansw.catchmyride.commute.CommuteSetting
import dev.hansw.catchmyride.commute.CommuteStop
import dev.hansw.catchmyride.commute.CommuteWindow
import dev.hansw.catchmyride.commute.GeoPoint
import dev.hansw.catchmyride.commute.NotificationMode
import dev.hansw.catchmyride.spike.SpikeProperties
import dev.hansw.catchmyride.spike.adapter.GbisBusAdapter
import dev.hansw.catchmyride.spike.adapter.SeoulSubwayAdapter
import dev.hansw.catchmyride.spike.adapter.TopisBusAdapter
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
    @Autowired lateinit var topis: TopisBusAdapter
    @Autowired lateinit var gbis: GbisBusAdapter
    @Autowired lateinit var subway: SeoulSubwayAdapter
    @Autowired lateinit var spikeProps: SpikeProperties

    private val clock = MutableClock()
    private val client = RecordingPushClient()

    @BeforeEach
    fun wipe() {
        // 공유 H2에 다른 테스트가 남긴 경로가 tick() 순회에 섞이지 않게 전부 비운다
        jdbc.sql("DELETE FROM commute_route").update()
        jdbc.sql("DELETE FROM push_log").update()
    }

    private fun scheduler(arrivals: ArrivalsService = arrivalsService) =
        PushNotificationScheduler(
            routes, pushLog, arrivals, DepartureTimingService(), client,
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

    @Test
    fun `추천 모드는 시간대 안이면 리마인드 후에도 다음 차로 새 사이클을 반복한다`() {
        val userKey = "push-test-window-cycles"
        routes.insert(userKey, route("r1", "출근", recommendedSetting(activeDays = listOf("TUE"))))
        val stub = StubArrivalsService()
        val scheduler = scheduler(stub)

        // 사이클 1 — 첫 차: 출발까지 120초(버퍼 3분 안) → PRE, 40초 → REMIND. 도보 8분(480초) 기준
        clock.set("2026-09-01T08:10:00")
        stub.arrivals = listOf(arrival(seconds = 600))
        scheduler.tick()
        clock.set("2026-09-01T08:11:00")
        stub.arrivals = listOf(arrival(seconds = 520))
        scheduler.tick()
        assertEquals(listOf(PushStage.PRE, PushStage.REMIND), client.sends.map { it.stage })

        // REMIND 직후 또 REMIND 조건 — PRE를 거치지 않았으므로 발송 금지 (스팸 방지)
        clock.set("2026-09-01T08:11:30")
        stub.arrivals = listOf(arrival(seconds = 490))
        scheduler.tick()
        assertEquals(2, client.sends.size, "REMIND 연속 발송 금지: ${client.sends}")

        // 다음 차가 아직 여유(출발까지 220초 > 버퍼) — 침묵
        clock.set("2026-09-01T08:15:00")
        stub.arrivals = listOf(arrival(seconds = 700))
        scheduler.tick()
        assertEquals(2, client.sends.size)

        // 사이클 2 — 다음 차가 버퍼 안으로: PRE → REMIND 반복
        clock.set("2026-09-01T08:16:00")
        stub.arrivals = listOf(arrival(seconds = 640))
        scheduler.tick()
        clock.set("2026-09-01T08:17:30")
        stub.arrivals = listOf(arrival(seconds = 530))
        scheduler.tick()
        assertEquals(
            listOf(PushStage.PRE, PushStage.REMIND, PushStage.PRE, PushStage.REMIND),
            client.sends.map { it.stage },
            "시간대 안 사이클 반복 (2026-09-09 FR-403 개정): ${client.sends}",
        )

        // 시간대(09:00) 밖으로 나가면 종료
        clock.set("2026-09-01T09:00:31")
        stub.arrivals = listOf(arrival(seconds = 600))
        scheduler.tick()
        assertEquals(4, client.sends.size, "시간대 밖 발송 금지: ${client.sends}")
    }

    @Test
    fun `정시 모드는 개정 후에도 하루 최대 2회를 유지한다`() {
        val userKey = "push-test-fixed-cap"
        routes.insert(userKey, route("r1", "출근", fixedSetting(activeDays = listOf("TUE"))))
        val scheduler = scheduler()

        clock.set("2026-09-01T08:17:30")
        scheduler.tick() // PRE
        clock.set("2026-09-01T08:19:30")
        scheduler.tick() // REMIND
        clock.set("2026-09-01T08:20:30") // 아직 REMIND 허용 구간이지만 이미 종료
        scheduler.tick()
        assertEquals(2, client.sends.size, "FIXED 하루 2회 상한 유지: ${client.sends}")
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

    private fun recommendedSetting(activeDays: List<String>) = CommuteSetting(
        home = GeoPoint(37.5219, 126.9245),
        stops = listOf(CommuteStop(StopType.SUBWAY, "여의도", "여의도역", listOf("9호선 급행"))),
        walkMinutes = 8,
        notificationMode = NotificationMode.RECOMMENDED,
        fixedDepartureTime = null,
        commuteWindow = CommuteWindow("08:00", "09:00"),
        bufferMinutes = 3,
        activeDays = activeDays,
    )

    private fun arrival(seconds: Int) = Arrival(
        stopDisplayName = "여의도역", routeName = "9호선 급행", direction = "상행",
        secondsToArrival = seconds, remainingStops = 2, isExpress = true,
        boardable = true, status = ArrivalStatus.RELAXED, rawMessage = null,
    )

    /** 공공 API 대신 테스트가 도착 목록을 주입한다 — RECOMMENDED 모드 검증용 */
    private inner class StubArrivalsService : ArrivalsService(routes, topis, gbis, subway, spikeProps) {
        var arrivals: List<Arrival> = emptyList()
        override fun arrivalsFor(setting: CommuteSetting): ArrivalsResponse =
            ArrivalsResponse(fetchedAt = "", realtimeAvailable = true, walkMinutes = setting.walkMinutes, arrivals = arrivals)
    }

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
