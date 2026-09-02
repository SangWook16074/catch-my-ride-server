package dev.hansw.catchmyride.push

import dev.hansw.catchmyride.arrivals.ArrivalsService
import dev.hansw.catchmyride.commute.CommuteSetting
import dev.hansw.catchmyride.commute.CommuteSettingRepository
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
 * 출근 1회당 최대 2회(FR-403) · 같은 스테이지 재발송 불가 · 미적용 요일 미발송(FR-405) · 실패 시 다음 틱 재시도.
 *
 * 2026-09-01(화) 고정 시계 + 공공 API 키 없음(도착 정보 빈 배열) — FIXED 모드는 시각만으로 발송된다.
 */
@SpringBootTest(properties = ["DATA_GO_KR_KEY=", "SEOUL_OPEN_DATA_KEY="])
class PushNotificationSchedulerTest {

    @Autowired lateinit var settings: CommuteSettingRepository
    @Autowired lateinit var pushLog: PushLogRepository
    @Autowired lateinit var arrivalsService: ArrivalsService
    @Autowired lateinit var jdbc: JdbcClient

    private val clock = MutableClock()
    private val client = RecordingPushClient()

    @BeforeEach
    fun wipe() {
        // 공유 H2에 다른 테스트가 남긴 설정이 tick() 순회에 섞이지 않게 전부 비운다
        jdbc.sql("DELETE FROM commute_setting").update()
        jdbc.sql("DELETE FROM push_log").update()
    }

    private fun scheduler() =
        PushNotificationScheduler(settings, pushLog, arrivalsService, DepartureTimingService(), client, clock)

    @Test
    fun `출근 1회당 PRE·REMIND 각 1회, 총 2회를 넘지 않는다`() {
        val userKey = "push-test-invariant"
        settings.upsert(userKey, fixedSetting(activeDays = listOf("TUE")))
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
        assertEquals(2, client.sends.size, "출근 1회 최대 2회 (FR-403): ${client.sends}")
        assertEquals(PushStage.REMIND, client.sends[1].stage)

        // dry-run이어도 발송 이력은 남는다 (§3 피드백 검증·North Star 측정의 근거)
        assertTrue(pushLog.hasAny(userKey, clock.today()))
        assertEquals(setOf(PushStage.PRE, PushStage.REMIND), pushLog.sentStages(userKey, clock.today()))
    }

    @Test
    fun `activeDays에 없는 요일은 발송하지 않는다`() {
        settings.upsert("push-test-day-off", fixedSetting(activeDays = listOf("MON"))) // 화요일 제외
        clock.set("2026-09-01T08:19:30")
        scheduler().tick()
        assertEquals(0, client.sends.size, "FR-405 위반: ${client.sends}")
    }

    @Test
    fun `발송 실패 시 이력이 롤백돼 다음 틱에 재시도한다`() {
        val userKey = "push-test-retry"
        settings.upsert(userKey, fixedSetting(activeDays = listOf("TUE")))
        val scheduler = scheduler()
        clock.set("2026-09-01T08:19:30")

        client.failNext = true
        scheduler.tick()
        assertEquals(0, client.sends.size)
        assertEquals(emptySet<PushStage>(), pushLog.sentStages(userKey, clock.today()))

        scheduler.tick() // 다음 틱 — 성공
        assertEquals(1, client.sends.size)
        assertEquals(setOf(PushStage.REMIND), pushLog.sentStages(userKey, clock.today()))
    }

    // --- 헬퍼 ---

    private fun fixedSetting(activeDays: List<String>) = CommuteSetting(
        home = GeoPoint(37.5219, 126.9245),
        stops = listOf(CommuteStop(StopType.SUBWAY, "여의도", "여의도역", listOf("9호선 급행"))),
        walkMinutes = 8,
        notificationMode = NotificationMode.FIXED,
        fixedDepartureTime = "08:20",
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
