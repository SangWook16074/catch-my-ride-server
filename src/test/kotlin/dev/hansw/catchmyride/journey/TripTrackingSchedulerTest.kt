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

    private fun scheduler() = TripTrackingScheduler(trips, trains, StationIdCache(), pushTokens, fcm, clock)

    /** 3호선 충무로(331)→교대(340) — 2026-09-21 QA 재현용 구간 (하행 = id 증가) */
    private val line3Legs = listOf(JourneyLeg("SUBWAY", "3호선", "충무로", "교대"))

    private fun line3Row(no: String, station: String, id: Long, heading: Heading, at: String? = null, message: String? = null, arvlCd: String = "99") =
        ApproachingTrain(
            no, line = "3호선", isExpress = false, arvlCd = arvlCd, message = message, secondsToArrival = 60,
            currentStation = at, heading = heading, stationName = station, stationId = id,
            prevStationId = if (heading == Heading.DOWN) id - 1 else id + 1,
            nextStationId = if (heading == Heading.DOWN) id + 1 else id - 1,
        )

    @Test
    fun `반대 방면 후보는 방면이 판정되는 순간 버려지고 그 방면 후보로 다시 잡는다`() {
        // 2026-09-21 QA: 충무로에서 대화 방면(상행) 열차가 유일한 후보로 잡혀 화면이 반대로 흘렀다
        val now = LocalDateTime.now(clock)
        trips.insert(
            Trip(
                tripId = "trip-1", userKey = "dev-user", journeyId = null, legs = line3Legs,
                legIndex = 0, phase = TripPhase.TRACKING, btrainNo = null, candidates = listOf("3300"),
                heading = null, remainingStops = null, realtimeAvailable = true,
                legStartedAt = now, lastSeenAt = null, startedAt = now,
            ),
            now,
        )
        val alightRows = listOf(
            line3Row("3372", "교대", 1003000340, Heading.UP, at = "양재"),
            line3Row("3347", "교대", 1003000340, Heading.DOWN, at = "잠원"),
        )
        trains["교대"] = alightRows
        trains["충무로"] = listOf(
            line3Row("3300", "충무로", 1003000331, Heading.UP),   // 대화 방면 — 반대
            line3Row("3349", "충무로", 1003000331, Heading.DOWN), // 오금 방면 — 교대로 가는 방면
        )
        trains.line("3호선", listOf(
            LineTrain("3300", "종로3가", isExpress = false, heading = Heading.UP, stationId = 1003000329),
            LineTrain("3349", "동대입구", isExpress = false, heading = Heading.DOWN, stationId = 1003000332),
        ))

        scheduler().tick()
        var saved = trips.find("trip-1")!!
        assertEquals(Heading.DOWN, saved.heading)
        assertEquals(listOf("3349"), saved.candidates)      // 반대 방면 3300은 버려진다
        assertEquals("동대입구", saved.currentStop)          // 단일 후보 + 방면 확정 → 위치 표시
        assertNull(saved.btrainNo)

        // 반대 방면 열차가 하차역 전광판에 떠도 특정되지 않는다
        trains["교대"] = alightRows + line3Row("3300", "교대", 1003000340, Heading.UP, message = "[2]번째 전역 (남부터미널)")
        scheduler().tick()
        assertNull(trips.find("trip-1")!!.btrainNo)

        // 우리 방면 후보가 하차역에 나타나면 특정
        trains["교대"] = listOf(line3Row("3349", "교대", 1003000340, Heading.DOWN, message = "[3]번째 전역 (신사)", at = "신사"))
        scheduler().tick()
        saved = trips.find("trip-1")!!
        assertEquals("3349", saved.btrainNo)
        assertEquals(3, saved.remainingStops)
    }

    @Test
    fun `방면을 모르면 단일 후보라도 위치를 보여주지 않는다`() {
        // 역 id 없는 응답(구형·민자 노선) — 방면 필터 없이 자기선택으로 강등하되 반대 방면일 수 있는 위치는 숨긴다
        insertTrip(candidates = listOf("9027"))
        trains.line("9호선", listOf(LineTrain("9027", "샛강", isExpress = true)))
        scheduler().tick()
        val saved = trips.find("trip-1")!!
        assertNull(saved.heading)
        assertNull(saved.currentStop)
        assertEquals(TripPhase.TRACKING, saved.phase)
    }

    private fun insertTrip(candidates: List<String> = listOf("9027"), heading: Heading? = null): Trip {
        val now = LocalDateTime.now(clock)
        val trip = Trip(
            tripId = "trip-1", userKey = "dev-user", journeyId = "j-1", legs = legs,
            legIndex = 0, phase = TripPhase.TRACKING, btrainNo = null, candidates = candidates,
            heading = heading, remainingStops = null, realtimeAvailable = true,
            legStartedAt = now, lastSeenAt = null, startedAt = now,
        )
        trips.insert(trip, now)
        return trip
    }

    private fun train(no: String, arvlCd: String? = "99", message: String? = null, express: Boolean = true, at: String? = null) =
        ApproachingTrain(no, line = "9호선", isExpress = express, arvlCd = arvlCd, message = message, secondsToArrival = null, currentStation = at)

    @Test
    fun `후보 열차가 하차역에 나타나면 특정되고 남은 정거장이 전진한다`() {
        insertTrip()
        pushTokens.upsert("dev-user", "fcm-token", "IOS")

        trains["당산"] = listOf(train("9027", message = "[4]번째 전역 (선유도)", at = "선유도"))
        scheduler().tick()
        var saved = trips.find("trip-1")!!
        assertEquals("9027", saved.btrainNo)
        assertEquals(4, saved.remainingStops)
        assertEquals("선유도", saved.currentStop) // 현재 위치 역명(arvlMsg3) 노출 (§9-3 currentStop)
        assertEquals(TripPhase.TRACKING, saved.phase)
        assertTrue(fcm.sent.isEmpty()) // 아직 예고 구간 아님

        // 2정거장 전 — PRE 1회 (FR-704 ①)
        trains["당산"] = listOf(train("9027", message = "[2]번째 전역 (국회의사당)", at = "국회의사당"))
        scheduler().tick()
        saved = trips.find("trip-1")!!
        assertEquals(2, saved.remainingStops)
        assertEquals("국회의사당", saved.currentStop)
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
        trains["당산"] = listOf(train("9027", message = "[4]번째 전역 (선유도)", at = "선유도"))
        scheduler().tick() // 특정 — remaining 4

        // 실시간 피드 두절 3분 초과 — LOST (2026-09-15 실주행에서 반드시 발생)
        trains["당산"] = emptyList()
        clock.advance(Duration.ofMinutes(4))
        scheduler().tick()
        val lost = trips.find("trip-1")!!
        assertEquals(TripPhase.LOST, lost.phase)
        assertNull(lost.currentStop) // 끊긴 채 낡은 역명을 남기지 않는다 (NFR-03)

        // 재목격 — TRACKING 복구, 남은 정거장도 다시 전진
        trains["당산"] = listOf(train("9027", message = "[3]번째 전역 (국회의사당)", at = "국회의사당"))
        scheduler().tick()
        val recovered = trips.find("trip-1")!!
        assertEquals(TripPhase.TRACKING, recovered.phase)
        assertEquals(3, recovered.remainingStops)
        assertEquals("국회의사당", recovered.currentStop)
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
    fun `시작 때 후보가 없어도 탑승역에 열차가 나타나면 후보로 잡아 특정한다`() {
        // 2026-09-16 저녁 실측: 시작 순간 전광판 공백 → 후보 0개 스냅샷 고정 → 영영 특정 불가·15분 LOST
        insertTrip(candidates = emptyList())
        trains["당산"] = emptyList()
        scheduler().tick() // 아직 아무 열차도 없음 — 위치 확인 중 유지
        assertEquals(TripPhase.TRACKING, trips.find("trip-1")!!.phase)

        // 탑승역에 열차 도착 — 특정 전 재수집으로 후보에 들어간다
        trains["여의도"] = listOf(train("9027", arvlCd = "1"))
        scheduler().tick()

        // 그 열차가 하차역 전광판에 나타나면 특정·카운트다운 시작
        trains["여의도"] = emptyList()
        trains["당산"] = listOf(train("9027", message = "[2]번째 전역 (국회의사당)", at = "국회의사당"))
        scheduler().tick()
        val saved = trips.find("trip-1")!!
        assertEquals("9027", saved.btrainNo)
        assertEquals(2, saved.remainingStops)
        assertEquals("국회의사당", saved.currentStop)
    }

    // ---- 노선 전체 위치(realtimePosition) 보강 — 2026-09-16 출근 실측 개정 ----

    @Test
    fun `특정 전에도 단일 후보는 노선 위치로 현재 역을 보여준다`() {
        insertTrip(heading = Heading.UP) // 후보 9027 하나 + 방면 확정 — "탔어요" 직후의 흔한 상태
        trains["당산"] = emptyList() // 하차역 전광판(방면당 1·2번째)엔 아직 없음
        trains.line("9호선", listOf(LineTrain("9027", "샛강", isExpress = true, heading = Heading.UP)))
        scheduler().tick()
        val saved = trips.find("trip-1")!!
        assertEquals(TripPhase.TRACKING, saved.phase)
        assertNull(saved.btrainNo) // 특정(방향 자기선택)은 여전히 하차역 목격으로만
        assertNull(saved.remainingStops) // 카운트다운도 전광판 목격부터 — 아는 척 금지 (NFR-03)
        assertEquals("샛강", saved.currentStop)
    }

    @Test
    fun `후보가 여럿이면 노선 목격만 하고 위치는 보여주지 않는다`() {
        insertTrip(candidates = listOf("9027", "9028"))
        trains["당산"] = emptyList()
        trains.line("9호선", listOf(LineTrain("9027", "샛강", isExpress = true)))
        scheduler().tick()
        val saved = trips.find("trip-1")!!
        assertEquals(TripPhase.TRACKING, saved.phase)
        assertNull(saved.currentStop) // 유저가 탄 열차를 모른다 (NFR-03)
    }

    @Test
    fun `노선에서 후보가 목격되는 동안은 특정 타임아웃으로 LOST되지 않는다`() {
        insertTrip()
        trains["당산"] = emptyList()
        trains.line("9호선", listOf(LineTrain("9027", "샛강", isExpress = true)))
        clock.advance(Duration.ofMinutes(16)) // IDENTIFY_TIMEOUT은 지났지만 열차가 달리는 게 보인다
        scheduler().tick()
        assertEquals(TripPhase.TRACKING, trips.find("trip-1")!!.phase)

        trains.line("9호선", emptyList()) // 노선에서도 사라짐 — 마지막 목격 기준으로 타임아웃
        clock.advance(Duration.ofMinutes(16))
        scheduler().tick()
        assertEquals(TripPhase.LOST, trips.find("trip-1")!!.phase)
    }

    @Test
    fun `특정 후 전광판에서 밀려나도 노선 목격이 있으면 LOST가 아니다`() {
        insertTrip()
        trains["당산"] = listOf(train("9027", message = "[4]번째 전역 (선유도)", at = "선유도"))
        scheduler().tick() // 특정 — remaining 4

        // 뒤차에 밀려 전광판(방면당 2대)에서 빠짐 — 노선 전체 위치에는 계속 보인다
        trains["당산"] = emptyList()
        trains.line("9호선", listOf(LineTrain("9027", "국회의사당", isExpress = true)))
        clock.advance(Duration.ofMinutes(4)) // LOST_AFTER(3분) 초과여도
        scheduler().tick()
        val saved = trips.find("trip-1")!!
        assertEquals(TripPhase.TRACKING, saved.phase)
        assertEquals("국회의사당", saved.currentStop) // 위치는 노선 피드로 계속 갱신
        assertEquals(4, saved.remainingStops) // 카운트는 마지막 전광판 값 유지
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
    private val byLine = mutableMapOf<String, List<LineTrain>>()
    private val failing = mutableSetOf<String>()

    operator fun set(station: String, value: List<ApproachingTrain>) {
        byStation[station] = value
    }
    fun line(line: String, value: List<LineTrain>) {
        byLine[line] = value
    }
    fun failFor(station: String) {
        failing.add(station)
    }
    fun reset() {
        byStation.clear()
        byLine.clear()
        failing.clear()
    }
    override fun approaching(stationName: String): List<ApproachingTrain> {
        if (stationName in failing) {
            throw IllegalStateException("상류 장애")
        }
        return byStation[stationName].orEmpty()
    }
    override fun onLine(line: String): List<LineTrain> = byLine[line].orEmpty()
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
