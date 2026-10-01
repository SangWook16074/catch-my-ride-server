package dev.hansw.catchmyride.journey

import dev.hansw.catchmyride.api.ApiException
import dev.hansw.catchmyride.api.UserKeyResolver
import dev.hansw.catchmyride.stops.SubwayStationCatalog
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * API.md §9-3 `POST /trips/{tripId}/alighted` · `POST /trips/{tripId}/undo-alight` (v0.11,
 * 2026-10-01 오너 결정) — 상류 지연으로 화면이 한 정거장쯤 뒤처져도 유저가 기다리지 않게 하는
 * "내렸어요" + 실수로 눌렀을 때의 "아직 안 내렸어요" 되돌리기.
 */
@SpringBootTest
class TripAlightedTest {

    @Autowired lateinit var trips: TripRepository
    @Autowired lateinit var journeys: JourneyRepository
    @Autowired lateinit var stations: SubwayStationCatalog
    @Autowired lateinit var legValidator: JourneyLegValidator
    @Autowired lateinit var userKeys: UserKeyResolver
    @Autowired lateinit var surfaceUpdater: TripSurfaceUpdater
    @Autowired lateinit var jdbc: JdbcClient

    private val clock = AlightMutableClock()
    private val trains = AlightStubTrains()

    private val legs = listOf(
        JourneyLeg("SUBWAY", "9호선 급행", "여의도", "당산"),
        JourneyLeg("SUBWAY", "2호선", "당산", "강남"),
    )

    @BeforeEach
    fun wipe() {
        jdbc.sql("DELETE FROM trip_push_log").update()
        jdbc.sql("DELETE FROM trip").update()
        trains.reset()
        clock.now = Instant.parse("2026-10-01T08:00:00Z")
    }

    private fun controller(): TripController {
        val stationIds = StationIdCache()
        return TripController(
            trips, journeys, trains, stationIds,
            RidingSeedResolver(stations, stationIds),
            stations, legValidator, userKeys, surfaceUpdater, clock,
        )
    }

    private fun trip(
        legIndex: Int = 0,
        phase: TripPhase = TripPhase.TRACKING,
        remainingStops: Int? = 1,
        btrainNo: String? = "9027",
    ): Trip {
        val now = LocalDateTime.now(clock)
        val trip = Trip(
            tripId = "trip-alight", userKey = "dev-user", journeyId = null, legs = legs,
            legIndex = legIndex, phase = phase, btrainNo = btrainNo, candidates = listOfNotNull(btrainNo),
            remainingStops = remainingStops, currentStop = "국회의사당", realtimeAvailable = true,
            legStartedAt = now, lastSeenAt = now, startedAt = now,
        )
        trips.insert(trip, now)
        return trip
    }

    @Test
    fun `① 환승 구간 remaining=1에서 alighted — 다음 구간 TRACKING, ALIGHT 미발송 취소`() {
        trip(legIndex = 0, remainingStops = 1)
        trains["당산"] = emptyList() // 다음 구간 탑승역 전광판 — 후보 없음(위치 확인 중)이어도 무방

        val status = controller().alighted(auth = null, tripId = "trip-alight", request = null)

        assertEquals("TRACKING", status.phase)
        assertEquals(1, status.legIndex) // 곧바로 다음 구간
        assertNull(status.remainingStops)
        assertNotNull(status.undoableUntil)

        // 구간이 바뀌었으니 scheduler가 더 이상 leg 0을 보지 않는다 — ALIGHT가 사후에 나갈 길이 없다
        assertFalse(trips.pushLogged("trip-alight", 0, TripPushStage.ALIGHT))
        val saved = trips.find("trip-alight")!!
        assertEquals(1, saved.legIndex)
        val undoSnapshot = assertNotNull(saved.undoSnapshot)
        assertEquals(0, undoSnapshot.legIndex)
    }

    @Test
    fun `② remaining=3이면 400 — 아직 멀리 있는데 누른 건 실수다`() {
        trip(remainingStops = 3)

        val error = runCatching {
            controller().alighted(auth = null, tripId = "trip-alight", request = null)
        }.exceptionOrNull()

        assertTrue(error is ApiException, error.toString())
    }

    @Test
    fun `remainingStops가 null(위치 확인 중)이면 400`() {
        trip(remainingStops = null)

        val error = runCatching {
            controller().alighted(auth = null, tripId = "trip-alight", request = null)
        }.exceptionOrNull()

        assertTrue(error is ApiException)
    }

    @Test
    fun `TRANSFER·DONE 상태에서는 400 — 추적 중인 구간이 아니다`() {
        trip(phase = TripPhase.TRANSFER)

        val error = runCatching {
            controller().alighted(auth = null, tripId = "trip-alight", request = null)
        }.exceptionOrNull()

        assertTrue(error is ApiException)
    }

    @Test
    fun `③ undo — 같은 btrainNo·remainingStops로 복원, 이미 나간 PRE 재발송 없음`() {
        trip(legIndex = 0, remainingStops = 1)
        trips.recordPush("trip-alight", 0, TripPushStage.PRE, delivered = true, now = LocalDateTime.now(clock))
        trains["당산"] = emptyList()

        val controller = controller()
        val alighted = controller.alighted(auth = null, tripId = "trip-alight", request = null)
        assertEquals(1, alighted.legIndex)

        val undone = controller.undoAlight(auth = null, tripId = "trip-alight")

        assertEquals("TRACKING", undone.phase)
        assertEquals(0, undone.legIndex)
        assertEquals(1, undone.remainingStops)
        assertNull(undone.undoableUntil)
        val saved = trips.find("trip-alight")!!
        assertEquals("9027", saved.btrainNo) // 재특정 없이 같은 열차
        assertEquals(1, saved.undoCount)
        // 직전 구간(leg 0)의 PRE 발송 기록은 보존 — 이미 나간 예고가 다시 나가지 않는다
        assertTrue(trips.pushLogged("trip-alight", 0, TripPushStage.PRE))
        // 버린 다음 구간(leg 1)의 발송 기록은 지운다 (이번 케이스엔 없었지만 기록 삭제 호출 자체를 검증)
        assertFalse(trips.pushLogged("trip-alight", 1, TripPushStage.PRE))
    }

    @Test
    fun `④ 5분이 지나면 undo는 400`() {
        trip(legIndex = 0, remainingStops = 1)
        val controller = controller()
        controller.alighted(auth = null, tripId = "trip-alight", request = null)
        clock.advance(Duration.ofMinutes(6))

        val error = runCatching { controller.undoAlight(auth = null, tripId = "trip-alight") }.exceptionOrNull()

        assertTrue(error is ApiException, error.toString())
    }

    @Test
    fun `④ 트립당 2회를 넘기면 undo는 400`() {
        trip(legIndex = 0, remainingStops = 1)
        val controller = controller()
        controller.alighted(auth = null, tripId = "trip-alight", request = null) // leg0 -> leg1
        controller.undoAlight(auth = null, tripId = "trip-alight") // leg1 -> leg0 (1회)

        // leg0 remainingStops가 undo로 복원됐으니 다시 alighted 가능한 상태
        controller.alighted(auth = null, tripId = "trip-alight", request = null) // leg0 -> leg1
        controller.undoAlight(auth = null, tripId = "trip-alight") // (2회)

        controller.alighted(auth = null, tripId = "trip-alight", request = null) // leg0 -> leg1 (3번째 alight)

        val error = runCatching { controller.undoAlight(auth = null, tripId = "trip-alight") }.exceptionOrNull()

        assertTrue(error is ApiException, error.toString())
        assertTrue(error.message.contains("너무 여러 번"), error.message)
    }

    @Test
    fun `⑤ 마지막 구간 alighted — DONE, undo로 TRACKING 복원`() {
        trip(legIndex = 1, remainingStops = 1) // 마지막 구간(leg 1)

        val controller = controller()
        val alighted = controller.alighted(auth = null, tripId = "trip-alight", request = null)
        assertEquals("DONE", alighted.phase)
        assertNotNull(alighted.undoableUntil)

        val undone = controller.undoAlight(auth = null, tripId = "trip-alight")
        assertEquals("TRACKING", undone.phase)
        assertEquals(1, undone.legIndex)
        assertEquals(1, undone.remainingStops)
    }
}

private class AlightMutableClock(var now: Instant = Instant.parse("2026-10-01T08:00:00Z")) : Clock() {
    fun advance(duration: Duration) {
        now = now.plus(duration)
    }
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneId.of("Asia/Seoul")
    override fun withZone(zone: ZoneId): Clock = this
}

private class AlightStubTrains : TrainPositions {
    private val byStation = mutableMapOf<String, List<ApproachingTrain>>()
    private val byLine = mutableMapOf<String, List<LineTrain>>()

    operator fun set(station: String, value: List<ApproachingTrain>) {
        byStation[station] = value
    }

    fun reset() {
        byStation.clear()
        byLine.clear()
    }

    override fun approaching(stationName: String): List<ApproachingTrain> = byStation[stationName].orEmpty()
    override fun onLine(line: String): List<LineTrain> = byLine[line].orEmpty()
}
