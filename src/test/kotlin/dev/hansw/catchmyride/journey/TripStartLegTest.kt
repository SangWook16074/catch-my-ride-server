package dev.hansw.catchmyride.journey

import dev.hansw.catchmyride.api.UserKeyResolver
import dev.hansw.catchmyride.stops.SubwayStationCatalog
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock
import kotlin.test.assertEquals

/**
 * API.md §9-2 "시작 구간 판정" (v0.10, 2026-10-01 오너 결정) — 구간이 여러 개인 여정에서 중간
 * 구간부터 시작한 유저가 항상 0번 구간(첫 구간)부터 추적당하던 문제의 수정.
 *
 * SERVER_FEEDBACK.md 2026-10-01 "여러 구간 여정" 절의 수유→충무로(4호선)→교대(3호선) 시나리오 —
 * 충무로가 두 구간의 환승역(leg0 하차역 = leg1 탑승역)이다.
 */
@SpringBootTest
class TripStartLegTest {

    @Autowired lateinit var trips: TripRepository
    @Autowired lateinit var journeys: JourneyRepository
    @Autowired lateinit var stations: SubwayStationCatalog
    @Autowired lateinit var legValidator: JourneyLegValidator
    @Autowired lateinit var userKeys: UserKeyResolver
    @Autowired lateinit var surfaceUpdater: TripSurfaceUpdater
    @Autowired lateinit var clock: Clock
    @Autowired lateinit var jdbc: JdbcClient

    private val trains = StartLegStubTrains()

    private val legs = listOf(
        LegRequest("SUBWAY", "4호선", "수유", "충무로"),
        LegRequest("SUBWAY", "3호선", "충무로", "교대"),
    )

    /** 카탈로그 실좌표 — 두 구간의 노선이 갈리므로 "가장 가까운 역"이 구간마다 다르게 잡힌다 */
    private val suyu = TripController.LocationRequest(37.638052, 127.025732, accuracy = 30.0)
    private val chungmuro = TripController.LocationRequest(37.561318, 126.994076, accuracy = 30.0)
    private val yaksu = TripController.LocationRequest(37.554565, 127.010449, accuracy = 30.0) // 충무로→교대 사이

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

    private fun start(location: TripController.LocationRequest?): TripController.StartResponse =
        controller().quickStart(auth = null, request = TripController.QuickStartRequest(legs = legs, location = location))

    @Test
    fun `① 3호선(충무로-교대) 주행 중 좌표 — leg 1 + 탄 열차`() {
        // 교대(하차역) 전광판·충무로(leg1 탑승역) 전광판 — 방면 판정(충무로 331 → 교대 340 = 하행) 근거
        trains["충무로"] = listOf(row("3399", "충무로", CHUNGMURO_3, Heading.DOWN, arvlCd = "1"))
        trains["교대"] = listOf(row("4444", "교대", GYODAE, Heading.DOWN))
        // 유저가 탄 열차 — 약수(충무로·교대 사이)에서 목격, 노선 전체 위치로만 보인다
        trains.line("3호선", listOf(LineTrain("3349", "약수", isExpress = false, heading = Heading.DOWN, stationId = YAKSU)))

        val started = start(yaksu)

        assertEquals(1, started.legIndex)
        val trip = trips.find(started.tripId)!!
        assertEquals("약수", trip.seedStop)
        assertEquals(listOf("3349"), trip.candidates)
    }

    @Test
    fun `② 충무로역 좌표(타기 전) — leg 1 탑승역 시드`() {
        // 환승역(충무로)에 서 있는 상태 — leg0 하차역이자 leg1 탑승역이라 둘 다 "가깝다"지만
        // leg0 쪽은 이미 하차역 도착(위치로 할 게 없다)이라 걸러지고 leg1(탑승 직전)만 남는다
        trains["충무로"] = listOf(row("3399", "충무로", CHUNGMURO_3, Heading.DOWN, arvlCd = "1"))
        trains["교대"] = listOf(row("4444", "교대", GYODAE, Heading.DOWN))

        val started = start(chungmuro)

        assertEquals(1, started.legIndex)
        val trip = trips.find(started.tripId)!!
        assertEquals(null, trip.seedStop) // 탑승역 시드 — 위치가 아니라 전광판에서 후보를 잡는다
        assertEquals(listOf("3399"), trip.candidates) // 충무로 전광판의 도착 중 열차가 후보
    }

    @Test
    fun `③ 수유역 좌표 — leg 0`() {
        val started = start(suyu)

        assertEquals(0, started.legIndex)
    }

    @Test
    fun `④ 좌표 없음 — leg 0`() {
        val started = start(null)

        assertEquals(0, started.legIndex)
    }

    @Test
    fun `구간이 1개면 판정 없이 항상 0번`() {
        val started = controller().quickStart(
            auth = null,
            request = TripController.QuickStartRequest(
                legs = listOf(LegRequest("SUBWAY", "4호선", "수유", "충무로")),
                location = chungmuro, // leg1이 없으니 판정 자체를 하지 않는다
            ),
        )
        assertEquals(0, started.legIndex)
    }

    private fun row(no: String, station: String, id: Long, heading: Heading, arvlCd: String = "99") = ApproachingTrain(
        no, line = if (id == CHUNGMURO_3 || id == GYODAE) "3호선" else "4호선", isExpress = false, arvlCd = arvlCd,
        message = null, secondsToArrival = null, heading = heading, stationName = station, stationId = id,
        prevStationId = if (heading == Heading.DOWN) id - 1 else id + 1,
        nextStationId = if (heading == Heading.DOWN) id + 1 else id - 1,
    )

    private companion object {
        const val CHUNGMURO_3 = 1003000331L
        const val YAKSU = 1003000334L
        const val GYODAE = 1003000340L
    }
}

private class StartLegStubTrains : TrainPositions {
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
