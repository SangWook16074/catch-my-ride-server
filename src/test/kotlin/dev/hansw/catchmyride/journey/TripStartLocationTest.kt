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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * API.md §9-2 — 위치 기반 "중간 시작" 시드 명세.
 *
 * 오너 실주행 시나리오(2026-09-24 제보 → 2026-09-29 재제보): 수유에서 타고 **중간쯤**에서 하차
 * 알림을 시작하면, 탑승역(수유) 전광판에 있는 건 유저 **뒤에** 오는 열차다. 그걸 잡으면 하차 푸시가
 * 내릴 역을 지난 뒤에 온다.
 *
 * 첫 구현(2026-09-24)이 실주행에서 안 걸린 이유가 이 파일의 1·2번 케이스다:
 * ① 후보 창을 "학습된 역 id"로 만들어, 유저가 선 역에 마침 열차가 없으면(= 막 떠났으면) 후보가 0대,
 * ② 지하 기지국 측위의 ±1~2km 오차를 1km 상한으로 통째로 버렸다.
 */
@SpringBootTest
class TripStartLocationTest {

    @Autowired lateinit var trips: TripRepository
    @Autowired lateinit var journeys: JourneyRepository
    @Autowired lateinit var stations: SubwayStationCatalog
    @Autowired lateinit var legValidator: JourneyLegValidator
    @Autowired lateinit var userKeys: UserKeyResolver
    @Autowired lateinit var surfaceUpdater: TripSurfaceUpdater
    @Autowired lateinit var clock: Clock
    @Autowired lateinit var jdbc: JdbcClient

    private val trains = StubTrains()

    /** 미아사거리 좌표 (카탈로그 값) — 탑승역 수유에서 2.8km, 두 정거장 지난 지점 */
    private val miaSageori = TripController.LocationRequest(lat = 37.613292, lng = 127.030053, accuracy = 30.0)

    @BeforeEach
    fun wipe() {
        jdbc.sql("DELETE FROM trip_push_log").update()
        jdbc.sql("DELETE FROM trip").update()
        trains.reset()
        // 탑승역·하차역 전광판 — 방면 판정(수유 414 → 충무로 423 = 하행)과 뒤차 후보의 출처
        trains["수유"] = listOf(
            row(BEHIND, "수유", SUYU, Heading.DOWN, arvlCd = "1"), // 유저 뒤에 오는 열차 (도착 중)
            row("4901", "수유", SUYU, Heading.UP, arvlCd = "1"),   // 반대 방면 — 후보가 아니다
        )
        trains["충무로"] = listOf(row("4444", "충무로", CHUNGMURO, Heading.DOWN))
    }

    private fun startSuyuToChungmuro(location: TripController.LocationRequest?): Trip {
        val stationIds = StationIdCache()
        val controller = TripController(
            trips, journeys, trains, stationIds,
            RidingSeedResolver(stations, stationIds),
            stations, legValidator, userKeys, surfaceUpdater, clock,
        )
        val started = controller.quickStart(
            auth = null,
            request = TripController.QuickStartRequest(
                legs = listOf(LegRequest("SUBWAY", "4호선", "수유", "충무로")),
                location = location,
            ),
        )
        return trips.find(started.tripId)!!
    }

    @Test
    fun `이미 탄 상태로 시작하면 탑승역 뒤차가 아니라 유저가 탄 열차를 잡는다`() {
        // 유저 열차는 미아사거리를 막 떠나 다음 역(길음)으로 보고돼 있다 — 첫 구현이 놓친 바로 그 상황
        trains.line("4호선", listOf(
            lineTrain(BEHIND, "수유", SUYU, Heading.DOWN),   // 뒤차 — 탑승역에 있다
            lineTrain(RIDDEN, "길음", GILEUM, Heading.DOWN), // 유저가 탄 열차
            lineTrain("4902", "길음", GILEUM, Heading.UP),   // 반대 방면 — 후보가 아니다
        ))

        val trip = startSuyuToChungmuro(miaSageori)

        assertEquals("미아사거리", trip.seedStop)
        assertEquals(Heading.DOWN, trip.heading)
        assertEquals(listOf(RIDDEN), trip.candidates)
        assertNull(trip.btrainNo)
    }

    @Test
    fun `지하 기지국 측위 오차 ±1800m도 쓴다 — 1km 상한이 실주행에서 좌표를 다 버렸다`() {
        trains.line("4호선", listOf(
            lineTrain(BEHIND, "수유", SUYU, Heading.DOWN),
            lineTrain(RIDDEN, "길음", GILEUM, Heading.DOWN),
        ))

        val trip = startSuyuToChungmuro(miaSageori.copy(accuracy = 1_800.0))

        assertEquals("미아사거리", trip.seedStop)
        assertEquals(listOf(RIDDEN), trip.candidates)
    }

    @Test
    fun `낡은 좌표는 버린다 — 달리는 열차에선 뒤차를 자신 있게 고르게 된다`() {
        trains.line("4호선", listOf(lineTrain(RIDDEN, "길음", GILEUM, Heading.DOWN)))

        val trip = startSuyuToChungmuro(miaSageori.copy(ageSeconds = 120))

        assertNull(trip.seedStop)
        assertEquals(listOf(BEHIND), trip.candidates) // 기존 동작 — 탑승역 전광판
    }

    @Test
    fun `탑승역에서 오차 반경만큼도 떨어지지 않았으면 아직 안 탔다고 본다`() {
        trains.line("4호선", listOf(lineTrain(RIDDEN, "미아사거리", MIA_SAGEORI, Heading.DOWN)))

        // 미아(수유에서 1.27km)인데 오차가 ±1.5km — 아직 수유 플랫폼일 수 있다
        val trip = startSuyuToChungmuro(TripController.LocationRequest(37.62667, 127.025983, accuracy = 1_500.0))

        assertNull(trip.seedStop)
        assertEquals(listOf(BEHIND), trip.candidates)
    }

    @Test
    fun `탑승역 뒤쪽 좌표는 구간 밖 — 위치를 쓰지 않는다 (NFR-03)`() {
        // 쌍문(413)은 수유 이전 역 — 유저가 아직 안 지난 쪽이라 중간 시작이 아니다
        trains.line("4호선", listOf(lineTrain("4111", "쌍문", SSANGMUN, Heading.DOWN)))

        val trip = startSuyuToChungmuro(TripController.LocationRequest(37.648627, 127.034709, accuracy = 30.0))

        assertNull(trip.seedStop)
        assertEquals(listOf(BEHIND), trip.candidates)
    }

    @Test
    fun `유저 주변 구간에 열차가 한 대도 없으면 탑승역 시드로 돌아간다`() {
        // 노선은 돌고 있는데(뒤차만 보인다) 유저 주변엔 없다 = 아직 탄 게 아니다(집·역 밖·엉뚱한 좌표).
        // 멀쩡한 "타기 전 시작"을 LOST로 만들지 않는다
        trains.line("4호선", listOf(lineTrain(BEHIND, "수유", SUYU, Heading.DOWN)))

        val trip = startSuyuToChungmuro(miaSageori)

        assertNull(trip.seedStop)
        assertEquals(listOf(BEHIND), trip.candidates)
    }

    @Test
    fun `노선 위치를 못 주는 노선이면 위치 역을 기억한 채 후보 0 — 뒤차를 따라가지 않는다 (FR-706)`() {
        // realtimePosition 미지원(민자 등)·상류 장애 — 추적 엔진이 60초 창에서 재수집, 못 잡으면 LOST
        val trip = startSuyuToChungmuro(miaSageori)

        assertEquals("미아사거리", trip.seedStop)
        assertTrue(trip.candidates.isEmpty())
    }

    @Test
    fun `위치가 없으면 예전처럼 탑승역 전광판에서 후보를 잡는다`() {
        val trip = startSuyuToChungmuro(null)

        assertNull(trip.seedStop)
        assertEquals(listOf(BEHIND), trip.candidates)
    }

    /** 전광판 행 — 방면별 이전/다음 역 id가 방면 판정(Heading.kt)의 근거다 */
    private fun row(no: String, station: String, id: Long, heading: Heading, arvlCd: String = "99") =
        ApproachingTrain(
            no, line = "4호선", isExpress = false, arvlCd = arvlCd, message = null, secondsToArrival = null,
            heading = heading, stationName = station, stationId = id,
            prevStationId = if (heading == Heading.DOWN) id - 1 else id + 1,
            nextStationId = if (heading == Heading.DOWN) id + 1 else id - 1,
        )

    private fun lineTrain(no: String, station: String, id: Long, heading: Heading) =
        LineTrain(no, station = station, isExpress = false, heading = heading, stationId = id)

    private companion object {
        const val BEHIND = "4123" // 유저 뒤에 오는 열차 (탑승역 전광판에 있는 것)
        const val RIDDEN = "4117" // 유저가 타고 있는 열차
        const val SSANGMUN = 1004000413L
        const val SUYU = 1004000414L
        const val MIA_SAGEORI = 1004000416L
        const val GILEUM = 1004000417L
        const val CHUNGMURO = 1004000423L
    }
}

private class StubTrains : TrainPositions {
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
