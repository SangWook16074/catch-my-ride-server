package dev.hansw.catchmyride.journey

import dev.hansw.catchmyride.push.FcmPushClient
import dev.hansw.catchmyride.push.PushProperties
import dev.hansw.catchmyride.push.PushToken
import dev.hansw.catchmyride.push.PushTokenRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §9 추적 엔진 불변 조건 명세:
 * 후보 매칭으로 열차 특정(방향 자기선택) · 남은 정거장 전진 · PRE(2정거장)-ALIGHT(직전 역)
 * 이벤트당 각 1회(FR-704) · 도착 시 TRANSFER/DONE · 타임아웃·두절은 LOST(FR-706) ·
 * 상류 장애는 LOST가 아니라 realtimeAvailable=false (NFR-03).
 */
@SpringBootTest
class TripTrackingSchedulerTest {

    @Autowired lateinit var trips: TripRepository
    @Autowired lateinit var pushTokens: PushTokenRepository
    @Autowired lateinit var jdbc: JdbcClient

    private val clock = MutableClock()
    private val trains = FakeTrains()
    private val fcm by lazy { RecordingFcm(pushTokens) }

    private val legs = listOf(
        JourneyLeg("SUBWAY", "9호선 급행", "여의도", "당산"),
        JourneyLeg("SUBWAY", "2호선", "당산", "강남"),
    )

    @BeforeEach
    fun wipe() {
        jdbc.sql("DELETE FROM trip_push_log").update()
        jdbc.sql("DELETE FROM trip").update()
        jdbc.sql("DELETE FROM push_token").update()
        trains.reset()
        clock.now = Instant.parse("2026-09-14T08:00:00Z")
    }

    private fun scheduler() = TripTrackingScheduler(trips, trains, pushTokens, fcm, clock)

    private fun insertTrip(candidates: List<String> = listOf("9027")): Trip {
        val now = LocalDateTime.now(clock)
        val trip = Trip(
            tripId = "trip-1", userKey = "dev-user", journeyId = "j-1", legs = legs,
            legIndex = 0, phase = TripPhase.TRACKING, btrainNo = null, candidates = candidates,
            remainingStops = null, realtimeAvailable = true,
            legStartedAt = now, lastSeenAt = null, startedAt = now,
        )
        trips.insert(trip, now)
        return trip
    }

    private fun train(no: String, arvlCd: String? = "99", message: String? = null, express: Boolean = true) =
        ApproachingTrain(no, line = "9호선", isExpress = express, arvlCd = arvlCd, message = message, secondsToArrival = null)

    @Test
    fun `후보 열차가 하차역에 나타나면 특정되고 남은 정거장이 전진한다`() {
        insertTrip()
        pushTokens.upsert("dev-user", "fcm-token", "IOS")

        trains["당산"] = listOf(train("9027", message = "[4]번째 전역 (선유도)"))
        scheduler().tick()
        var saved = trips.find("trip-1")!!
        assertEquals("9027", saved.btrainNo)
        assertEquals(4, saved.remainingStops)
        assertEquals(TripPhase.TRACKING, saved.phase)
        assertTrue(fcm.sent.isEmpty()) // 아직 예고 구간 아님

        // 2정거장 전 — PRE 1회 (FR-704 ①)
        trains["당산"] = listOf(train("9027", message = "[2]번째 전역 (국회의사당)"))
        scheduler().tick()
        saved = trips.find("trip-1")!!
        assertEquals(2, saved.remainingStops)
        assertEquals(listOf("PRE"), fcm.sent.map { it.first })

        // 같은 상태 반복 폴링 — 재발송 없음
        scheduler().tick()
        assertEquals(1, fcm.sent.size)

        // 직전 역 — ARRIVING + ALIGHT 1회 (FR-704 ②, 환승 안내 포함)
        trains["당산"] = listOf(train("9027", arvlCd = "3"))
        scheduler().tick()
        saved = trips.find("trip-1")!!
        assertEquals(TripPhase.ARRIVING, saved.phase)
        assertEquals(listOf("PRE", "ALIGHT"), fcm.sent.map { it.first })
        assertTrue(fcm.sent.last().second.contains("2호선"), fcm.sent.last().second)

        // 하차역 도착 — 다음 구간이 있으니 TRANSFER (FR-703 수동 재개 대기)
        trains["당산"] = listOf(train("9027", arvlCd = "1"))
        scheduler().tick()
        assertEquals(TripPhase.TRANSFER, trips.find("trip-1")!!.phase)
        assertEquals(2, fcm.sent.size) // 사후 발송 없음
    }

    @Test
    fun `마지막 구간 도착은 DONE이고 도착 후 목록에서 사라져도 도착으로 처리한다`() {
        val now = LocalDateTime.now(clock)
        val trip = Trip(
            tripId = "trip-1", userKey = "dev-user", journeyId = "j-1", legs = legs,
            legIndex = 1, phase = TripPhase.ARRIVING, btrainNo = "2101", candidates = emptyList(),
            remainingStops = 1, realtimeAvailable = true,
            legStartedAt = now, lastSeenAt = now, startedAt = now,
        )
        trips.insert(trip, now)

        // 직전 역까지 왔던 열차가 목록에서 사라짐 = 하차역 도착·통과
        trains["강남"] = emptyList()
        scheduler().tick()
        val saved = trips.find("trip-1")!!
        assertEquals(TripPhase.DONE, saved.phase)
        assertEquals(0, saved.remainingStops)
    }

    @Test
    fun `특정 타임아웃과 목격 두절은 LOST다`() {
        insertTrip(candidates = emptyList()) // 후보 없음 — 특정 불가
        trains["당산"] = emptyList()
        clock.advance(Duration.ofMinutes(16))
        scheduler().tick()
        val lost = trips.find("trip-1")!!
        assertEquals(TripPhase.LOST, lost.phase)
        assertNull(lost.remainingStops)
    }

    @Test
    fun `목격 두절 LOST는 재목격되면 TRACKING으로 복구된다`() {
        insertTrip()
        trains["당산"] = listOf(train("9027", message = "[4]번째 전역 (선유도)"))
        scheduler().tick() // 특정 — remaining 4

        // 실시간 피드 두절 3분 초과 — LOST (2026-09-15 실주행에서 반드시 발생)
        trains["당산"] = emptyList()
        clock.advance(Duration.ofMinutes(4))
        scheduler().tick()
        assertEquals(TripPhase.LOST, trips.find("trip-1")!!.phase)

        // 재목격 — TRACKING 복구, 남은 정거장도 다시 전진
        trains["당산"] = listOf(train("9027", message = "[3]번째 전역 (국회의사당)"))
        scheduler().tick()
        val recovered = trips.find("trip-1")!!
        assertEquals(TripPhase.TRACKING, recovered.phase)
        assertEquals(3, recovered.remainingStops)
    }

    @Test
    fun `특정 실패 LOST도 후보가 하차역에 나타나면 특정되어 복구된다`() {
        insertTrip() // 후보 9027 — 아직 하차역 조회 범위 밖
        trains["당산"] = emptyList()
        clock.advance(Duration.ofMinutes(16)) // IDENTIFY_TIMEOUT 초과
        scheduler().tick()
        assertEquals(TripPhase.LOST, trips.find("trip-1")!!.phase)

        trains["당산"] = listOf(train("9027", message = "[4]번째 전역 (선유도)"))
        scheduler().tick()
        val recovered = trips.find("trip-1")!!
        assertEquals(TripPhase.TRACKING, recovered.phase)
        assertEquals("9027", recovered.btrainNo)
        assertEquals(4, recovered.remainingStops)
    }

    @Test
    fun `복구되지 못한 LOST는 updated_at이 묶여 있어 자동 정리된다`() {
        insertTrip(candidates = emptyList())
        trains["당산"] = emptyList()
        clock.advance(Duration.ofMinutes(16))
        scheduler().tick() // LOST

        // LOST로 머무는 동안 폴링이 계속 돌아도 updated_at을 갱신하지 않는다
        clock.advance(Duration.ofHours(3))
        scheduler().tick()
        assertEquals(TripPhase.LOST, trips.find("trip-1")!!.phase)

        clock.advance(Duration.ofHours(4)) // 두절 시점 기준 STALE_AFTER(6시간) 초과
        scheduler().tick()
        assertNull(trips.find("trip-1"))
    }

    @Test
    fun `상류 장애는 LOST가 아니라 실시간 정보 없음이다`() {
        insertTrip()
        trains.failFor("당산")
        scheduler().tick()
        val saved = trips.find("trip-1")!!
        assertEquals(TripPhase.TRACKING, saved.phase)
        assertEquals(false, saved.realtimeAvailable)
    }

    @Test
    fun `토큰 없는 유저는 dry-run 기록만 남고 상태는 전진한다`() {
        insertTrip() // push_token 미등록
        trains["당산"] = listOf(train("9027", message = "[2]번째 전역 (국회의사당)"))
        scheduler().tick()
        assertTrue(fcm.sent.isEmpty())
        assertTrue(trips.pushLogged("trip-1", 0, TripPushStage.PRE)) // 기록은 남아 중복 방지
        assertEquals(2, trips.find("trip-1")!!.remainingStops)
    }
}

private class MutableClock(var now: Instant = Instant.parse("2026-09-14T08:00:00Z")) : Clock() {
    fun advance(duration: Duration) {
        now = now.plus(duration)
    }
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneId.of("Asia/Seoul")
    override fun withZone(zone: ZoneId): Clock = this
}

private class FakeTrains : TrainPositions {
    private val byStation = mutableMapOf<String, List<ApproachingTrain>>()
    private val failing = mutableSetOf<String>()

    operator fun set(station: String, value: List<ApproachingTrain>) {
        byStation[station] = value
    }
    fun failFor(station: String) {
        failing.add(station)
    }
    fun reset() {
        byStation.clear()
        failing.clear()
    }
    override fun approaching(stationName: String): List<ApproachingTrain> {
        if (stationName in failing) {
            throw IllegalStateException("상류 장애")
        }
        return byStation[stationName].orEmpty()
    }
}

/** stage → body 기록 — live=false라 원본 send는 dry-run이지만, 여기선 발송 자체를 가로챈다 */
private class RecordingFcm(tokens: PushTokenRepository) : FcmPushClient(PushProperties(), tokens) {
    val sent = mutableListOf<Pair<String, String>>()
    override fun sendMessage(userKey: String, token: PushToken, title: String, body: String, link: String): Boolean {
        val stage = if (title.contains("다음 역")) "ALIGHT" else "PRE"
        sent.add(stage to body)
        return true
    }
}
