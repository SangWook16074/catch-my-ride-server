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
 * API.md §9-3 "다시 잡기" — 서버가 유저가 탄 열차가 아닌 차량을 추적할 때 (오너 요청 2026-09-30).
 *
 * 유저는 화면의 "현재 ○○ 부근"으로 이걸 제일 먼저 알아챈다. 트립을 버리고 새로 시작하게 하면
 * 1회성 구간·저장 흐름을 다시 타야 하므로, 그 자리에서 물린 열차를 빼고 다시 잡는다.
 */
@SpringBootTest
class TripReIdentifyTest {

    @Autowired lateinit var trips: TripRepository
    @Autowired lateinit var journeys: JourneyRepository
    @Autowired lateinit var stations: SubwayStationCatalog
    @Autowired lateinit var legValidator: JourneyLegValidator
    @Autowired lateinit var userKeys: UserKeyResolver
    @Autowired lateinit var clock: Clock
    @Autowired lateinit var jdbc: JdbcClient

    private val trains = ReIdentifyStubTrains()

    /** 미아사거리 좌표 — 수유에서 두 정거장 지난 지점 (§9-2 중간 시작과 같은 사례) */
    private val here = TripController.LocationRequest(37.613292, 127.030053, accuracy = 30.0)

    private val leg = JourneyLeg("SUBWAY", "4호선", "수유", "충무로")

    @BeforeEach
    fun wipe() {
        jdbc.sql("DELETE FROM trip_push_log").update()
        jdbc.sql("DELETE FROM trip").update()
        trains.reset()
        trains["수유"] = listOf(row(BEHIND, SUYU, Heading.DOWN, arvlCd = "1"))
        trains["충무로"] = listOf(row("4444", CHUNGMURO, Heading.DOWN))
    }

    private fun controller(): TripController {
        val stationIds = StationIdCache()
        return TripController(
            trips, journeys, trains, stationIds,
            RidingSeedResolver(stations, stationIds),
            legValidator, userKeys, clock,
        )
    }

    private fun tracking(
        btrainNo: String? = WRONG,
        phase: TripPhase = TripPhase.TRACKING,
        rejected: List<String> = emptyList(),
    ): Trip {
        val now = LocalDateTime.now(clock)
        val trip = Trip(
            tripId = "trip-reid", userKey = "dev-user", journeyId = null, legs = listOf(leg),
            legIndex = 0, phase = phase, btrainNo = btrainNo, candidates = listOfNotNull(btrainNo),
            rejectedTrains = rejected, seedStop = null, heading = Heading.DOWN,
            remainingStops = 4, currentStop = "쌍문", realtimeAvailable = true,
            legStartedAt = now, lastSeenAt = now, startedAt = now,
        )
        trips.insert(trip, now)
        return trip
    }

    @Test
    fun `물린 열차를 빼고 위치로 다시 잡는다`() {
        tracking()
        // 잘못 잡힌 열차(WRONG)는 유저 뒤에 있고, 유저가 탄 열차(RIDDEN)는 길음으로 보고돼 있다
        trains.line("4호선", listOf(
            lineTrain(WRONG, "쌍문", SSANGMUN),
            lineTrain(RIDDEN, "길음", GILEUM),
        ))

        val status = controller().reIdentify(auth = null, tripId = "trip-reid", request = TripController.StartRequest(here))

        assertEquals("TRACKING", status.phase)
        assertNull(status.remainingStops) // 다시 위치 확인 중 — 낡은 카운트를 이어서 보여주지 않는다
        assertNull(status.currentStop)
        val saved = trips.find("trip-reid")!!
        assertNull(saved.btrainNo)
        assertEquals(listOf(WRONG), saved.rejectedTrains)
        assertEquals(listOf(RIDDEN), saved.candidates) // 물린 열차는 후보로 돌아오지 않는다
        assertEquals("미아사거리", saved.seedStop)
        assertEquals(Heading.DOWN, saved.heading) // 같은 구간 — 방면은 그대로
    }

    @Test
    fun `물린 열차가 탑승역 전광판에 있어도 후보로 잡지 않는다`() {
        tracking()
        trains["수유"] = listOf(row(WRONG, SUYU, Heading.DOWN, arvlCd = "1")) // 위치 없이 다시 잡는 경우

        controller().reIdentify(auth = null, tripId = "trip-reid", request = null)

        val saved = trips.find("trip-reid")!!
        assertTrue(saved.candidates.isEmpty())
        assertEquals(listOf(WRONG), saved.rejectedTrains)
    }

    @Test
    fun `잘못 잡은 열차로 나간 예고는 무효 — 이 구간 발송 기록을 지운다 (FR-704 재계산)`() {
        tracking()
        trips.recordPush("trip-reid", 0, TripPushStage.PRE, delivered = true, now = LocalDateTime.now(clock))
        assertTrue(trips.pushLogged("trip-reid", 0, TripPushStage.PRE))

        controller().reIdentify(auth = null, tripId = "trip-reid", request = TripController.StartRequest(here))

        assertFalse(trips.pushLogged("trip-reid", 0, TripPushStage.PRE))
    }

    @Test
    fun `되돌리기는 구간당 3회까지 — 넘으면 다시 시작이 맞다 (푸시 재개방 제한)`() {
        tracking(rejected = listOf("4101", "4102", "4103"))

        val error = runCatching {
            controller().reIdentify(auth = null, tripId = "trip-reid", request = TripController.StartRequest(here))
        }.exceptionOrNull()

        assertTrue(error is ApiException, error.toString())
        assertTrue(error.message!!.contains("다시 시작"), error.message!!)
    }

    @Test
    fun `환승 대기·완료 상태에서는 400 — 그건 next-leg와 재시작의 일이다`() {
        tracking(phase = TripPhase.TRANSFER)

        val error = runCatching {
            controller().reIdentify(auth = null, tripId = "trip-reid", request = null)
        }.exceptionOrNull()

        assertTrue(error is ApiException, error.toString())
    }

    @Test
    fun `아직 특정 전이면 물릴 열차가 없다 — 후보만 다시 잡는다`() {
        tracking(btrainNo = null)
        trains.line("4호선", listOf(lineTrain(RIDDEN, "길음", GILEUM)))

        controller().reIdentify(auth = null, tripId = "trip-reid", request = TripController.StartRequest(here))

        val saved = trips.find("trip-reid")!!
        assertTrue(saved.rejectedTrains.isEmpty())
        assertEquals(listOf(RIDDEN), saved.candidates)
    }

    private fun row(no: String, id: Long, heading: Heading, arvlCd: String = "99") = ApproachingTrain(
        no, line = "4호선", isExpress = false, arvlCd = arvlCd, message = null, secondsToArrival = null,
        heading = heading, stationName = if (id == SUYU) "수유" else "충무로", stationId = id,
        prevStationId = id - 1, nextStationId = id + 1,
    )

    private fun lineTrain(no: String, station: String, id: Long) =
        LineTrain(no, station = station, isExpress = false, heading = Heading.DOWN, stationId = id)

    private companion object {
        const val WRONG = "4199"  // 서버가 잘못 잡은 열차 (유저 뒤차)
        const val RIDDEN = "4117" // 유저가 실제로 탄 열차
        const val BEHIND = "4123"
        const val SSANGMUN = 1004000413L
        const val SUYU = 1004000414L
        const val GILEUM = 1004000417L
        const val CHUNGMURO = 1004000423L
    }
}

private class ReIdentifyStubTrains : TrainPositions {
    private val byStation = mutableMapOf<String, List<ApproachingTrain>>()
    private val byLine = mutableMapOf<String, List<LineTrain>>()

    operator fun set(station: String, value: List<ApproachingTrain>) {
        byStation[station] = value
    }

    fun line(line: String, value: List<LineTrain>) {
        byLine[line] = value
    }

    fun reset() {
        byStation.clear()
        byLine.clear()
    }

    override fun approaching(stationName: String): List<ApproachingTrain> = byStation[stationName].orEmpty()
    override fun onLine(line: String): List<LineTrain> = byLine[line].orEmpty()
}
