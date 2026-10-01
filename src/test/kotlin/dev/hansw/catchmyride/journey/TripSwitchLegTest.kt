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
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * API.md §9-3 `POST /trips/{tripId}/switch-leg` (v0.10, 2026-10-01 오너 결정) — 시작 구간 자동
 * 판정이 틀렸거나 좌표 없이 시작돼 0번 구간에 묶였을 때 유저가 직접 구간을 고르는 출구.
 */
@SpringBootTest
class TripSwitchLegTest {

    @Autowired lateinit var trips: TripRepository
    @Autowired lateinit var journeys: JourneyRepository
    @Autowired lateinit var stations: SubwayStationCatalog
    @Autowired lateinit var legValidator: JourneyLegValidator
    @Autowired lateinit var userKeys: UserKeyResolver
    @Autowired lateinit var surfaceUpdater: TripSurfaceUpdater
    @Autowired lateinit var clock: Clock
    @Autowired lateinit var jdbc: JdbcClient

    private val trains = SwitchLegStubTrains()

    private val legs = listOf(
        JourneyLeg("SUBWAY", "4호선", "수유", "충무로"),
        JourneyLeg("SUBWAY", "3호선", "충무로", "교대"),
        JourneyLeg("SUBWAY", "2호선", "교대", "강남"),
    )

    @BeforeEach
    fun wipe() {
        jdbc.sql("DELETE FROM trip_push_log").update()
        jdbc.sql("DELETE FROM trip").update()
        trains.reset()
    }

    private fun controller(): TripController {
        val stationIds = StationIdCache()
        return TripController(
            trips, journeys, trains, stationIds,
            RidingSeedResolver(stations, stationIds),
            stations, legValidator, userKeys, surfaceUpdater, clock,
        )
    }

    private fun tracking(legIndex: Int = 0, phase: TripPhase = TripPhase.TRACKING, switchCount: Int = 0): Trip {
        val now = LocalDateTime.now(clock)
        val trip = Trip(
            tripId = "trip-switch", userKey = "dev-user", journeyId = null, legs = legs,
            legIndex = legIndex, phase = phase, btrainNo = "9999", candidates = listOf("9999"),
            remainingStops = 3, currentStop = "어딘가", realtimeAvailable = true,
            legStartedAt = now, lastSeenAt = now, startedAt = now, switchCount = switchCount,
        )
        trips.insert(trip, now)
        return trip
    }

    @Test
    fun `뒤 구간으로 바꾸면 초기화되고 그 구간 발송 기록도 비운다`() {
        tracking(legIndex = 0)
        trips.recordPush("trip-switch", 1, TripPushStage.PRE, delivered = true, now = LocalDateTime.now(clock))

        val status = controller().switchLeg(
            auth = null, tripId = "trip-switch",
            request = TripController.SwitchLegRequest(legIndex = 1, location = null),
        )

        assertEquals("TRACKING", status.phase)
        assertEquals(1, status.legIndex)
        assertNull(status.remainingStops)
        assertNull(status.currentStop)
        val saved = trips.find("trip-switch")!!
        assertEquals(1, saved.switchCount)
        assertTrue(saved.rejectedTrains.isEmpty())
        assertNull(saved.btrainNo)
        assertFalse(trips.pushLogged("trip-switch", 1, TripPushStage.PRE))
    }

    @Test
    fun `앞 구간으로도 바꿀 수 있다`() {
        tracking(legIndex = 2)

        val status = controller().switchLeg(
            auth = null, tripId = "trip-switch",
            request = TripController.SwitchLegRequest(legIndex = 0, location = null),
        )

        assertEquals(0, status.legIndex)
    }

    @Test
    fun `TRANSFER 상태에서도 허용 — 환승역에서 다음이 아닌 구간으로 갈 수 있다`() {
        tracking(legIndex = 0, phase = TripPhase.TRANSFER)

        val status = controller().switchLeg(
            auth = null, tripId = "trip-switch",
            request = TripController.SwitchLegRequest(legIndex = 2, location = null),
        )

        assertEquals(2, status.legIndex)
        assertEquals("TRACKING", status.phase)
    }

    @Test
    fun `같은 구간으로는 바꿀 수 없다 — 400`() {
        tracking(legIndex = 1)

        val error = runCatching {
            controller().switchLeg(auth = null, tripId = "trip-switch", request = TripController.SwitchLegRequest(legIndex = 1))
        }.exceptionOrNull()

        assertTrue(error is ApiException)
    }

    @Test
    fun `범위 밖 legIndex는 400`() {
        tracking(legIndex = 0)

        val error = runCatching {
            controller().switchLeg(auth = null, tripId = "trip-switch", request = TripController.SwitchLegRequest(legIndex = 99))
        }.exceptionOrNull()

        assertTrue(error is ApiException)
    }

    @Test
    fun `DONE이면 400`() {
        tracking(legIndex = 2, phase = TripPhase.DONE)

        val error = runCatching {
            controller().switchLeg(auth = null, tripId = "trip-switch", request = TripController.SwitchLegRequest(legIndex = 0))
        }.exceptionOrNull()

        assertTrue(error is ApiException)
    }

    @Test
    fun `트립당 3회를 넘기면 400 — 다시 시작해주세요`() {
        tracking(legIndex = 0, switchCount = 3)

        val error = runCatching {
            controller().switchLeg(auth = null, tripId = "trip-switch", request = TripController.SwitchLegRequest(legIndex = 1))
        }.exceptionOrNull()

        assertTrue(error is ApiException, error.toString())
        assertTrue(error.message.contains("다시 시작"), error.message)
    }
}

private class SwitchLegStubTrains : TrainPositions {
    private val byStation = mutableMapOf<String, List<ApproachingTrain>>()
    private val byLine = mutableMapOf<String, List<LineTrain>>()

    fun reset() {
        byStation.clear()
        byLine.clear()
    }

    override fun approaching(stationName: String): List<ApproachingTrain> = byStation[stationName].orEmpty()
    override fun onLine(line: String): List<LineTrain> = byLine[line].orEmpty()
}
