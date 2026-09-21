package dev.hansw.catchmyride.journey

import dev.hansw.catchmyride.ApiContractTestSupport.request
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.simple.JdbcClient
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * API.md §9-2/9-3 — 트립 시작·상태·환승 재개·종료 계약.
 * 테스트 환경은 지하철 키가 비어 있어(TrainPositions 빈 목록) 후보 없이 시작된다 —
 * phase TRACKING + remainingStops null(위치 확인 중)이 시작 직후의 정직한 상태다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TripApiTest {

    @Autowired lateinit var environment: Environment
    @Autowired lateinit var jdbc: JdbcClient

    @BeforeEach
    fun wipe() {
        jdbc.sql("DELETE FROM trip_push_log").update()
        jdbc.sql("DELETE FROM trip").update()
        jdbc.sql("DELETE FROM journey").update()
    }

    private fun createJourney(): String {
        val created = request(
            environment, "POST", "/api/v1/journeys",
            """
            {"label":"회사","repeatDays":[],
             "legs":[{"type":"SUBWAY","line":"9호선 급행","boardStop":"여의도","alightStop":"당산"},
                     {"type":"SUBWAY","line":"2호선","boardStop":"당산","alightStop":"강남"}]}
            """.trimIndent(),
        )
        assertEquals(201, created.statusCode(), created.body())
        return Regex("\"id\":\"([^\"]+)\"").find(created.body())!!.groupValues[1]
    }

    @Test
    fun `트립을 시작하면 추적 상태가 조회되고 lastUsedAt이 갱신된다`() {
        val journeyId = createJourney()
        val started = request(environment, "POST", "/api/v1/journeys/$journeyId/trips")
        assertEquals(201, started.statusCode(), started.body())
        val tripId = Regex("\"tripId\":\"([^\"]+)\"").find(started.body())!!.groupValues[1]

        val status = request(environment, "GET", "/api/v1/trips/$tripId")
        assertEquals(200, status.statusCode(), status.body())
        assertTrue(status.body().contains("\"phase\":\"TRACKING\""), status.body())
        assertTrue(status.body().contains("\"eventStop\":\"당산\""), status.body())
        assertTrue(status.body().contains("\"remainingStops\":null"), status.body())

        val list = request(environment, "GET", "/api/v1/journeys")
        assertTrue(!list.body().contains("\"lastUsedAt\":null"), list.body()) // 히스토리 정렬 키 갱신
    }

    @Test
    fun `동시 트립은 1개 - 두 번째 시작은 기존 tripId를 담아 400이다`() {
        val journeyId = createJourney()
        val first = request(environment, "POST", "/api/v1/journeys/$journeyId/trips")
        val tripId = Regex("\"tripId\":\"([^\"]+)\"").find(first.body())!!.groupValues[1]

        val second = request(environment, "POST", "/api/v1/journeys/$journeyId/trips")
        assertEquals(400, second.statusCode(), second.body())
        assertTrue(second.body().contains("진행 중인 트립"), second.body())
        assertTrue(second.body().contains(tripId), second.body())
    }

    // §9-2 `POST /api/v1/trips` — 1회성(여정 비귀속) 트립 시작 (FR-708, 2026-09-21)
    private val quickLegs = """
        {"legs":[{"type":"SUBWAY","line":"9호선 급행","boardStop":"여의도","alightStop":"당산"},
                 {"type":"SUBWAY","line":"2호선","boardStop":"당산","alightStop":"강남"}]}
    """.trimIndent()

    @Test
    fun `여정 없이 인라인 구간으로 트립을 시작하면 추적되고 여정 목록은 그대로다`() {
        val started = request(environment, "POST", "/api/v1/trips", quickLegs)
        assertEquals(201, started.statusCode(), started.body())
        val tripId = Regex("\"tripId\":\"([^\"]+)\"").find(started.body())!!.groupValues[1]

        val status = request(environment, "GET", "/api/v1/trips/$tripId")
        assertEquals(200, status.statusCode(), status.body())
        assertTrue(status.body().contains("\"phase\":\"TRACKING\""), status.body())
        assertTrue(status.body().contains("\"eventStop\":\"당산\""), status.body())

        // 여정 히스토리 비귀속 — 몰래 여정을 만들거나 lastUsedAt을 건드리지 않는다
        val list = request(environment, "GET", "/api/v1/journeys")
        assertTrue(list.body().contains("\"journeys\":[]"), list.body())

        // 환승 후 재개·종료도 저장 여정 트립과 같은 경로
        val ended = request(environment, "DELETE", "/api/v1/trips/$tripId")
        assertEquals(204, ended.statusCode(), ended.body())
        assertEquals(404, request(environment, "GET", "/api/v1/trips/$tripId").statusCode())
    }

    @Test
    fun `1회성 트립도 구간 검증과 동시 1개 규칙은 여정과 동일하다`() {
        val badLine = request(
            environment, "POST", "/api/v1/trips",
            """{"legs":[{"type":"SUBWAY","line":"없는선","boardStop":"여의도","alightStop":"당산"}]}""",
        )
        assertEquals(400, badLine.statusCode(), badLine.body())
        assertTrue(badLine.body().contains("지나지 않는 노선"), badLine.body())

        val empty = request(environment, "POST", "/api/v1/trips", """{"legs":[]}""")
        assertEquals(400, empty.statusCode(), empty.body())

        val journeyId = createJourney()
        val first = request(environment, "POST", "/api/v1/journeys/$journeyId/trips")
        val tripId = Regex("\"tripId\":\"([^\"]+)\"").find(first.body())!!.groupValues[1]
        val second = request(environment, "POST", "/api/v1/trips", quickLegs)
        assertEquals(400, second.statusCode(), second.body())
        assertTrue(second.body().contains(tripId), second.body())
    }

    @Test
    fun `TRANSFER가 아니면 next-leg는 400이고 종료는 멱등이다`() {
        val journeyId = createJourney()
        val started = request(environment, "POST", "/api/v1/journeys/$journeyId/trips")
        val tripId = Regex("\"tripId\":\"([^\"]+)\"").find(started.body())!!.groupValues[1]

        val advance = request(environment, "POST", "/api/v1/trips/$tripId/next-leg")
        assertEquals(400, advance.statusCode(), advance.body())
        assertTrue(advance.body().contains("환승 대기"), advance.body())

        assertEquals(204, request(environment, "DELETE", "/api/v1/trips/$tripId").statusCode())
        assertEquals(204, request(environment, "DELETE", "/api/v1/trips/$tripId").statusCode()) // 멱등
        assertEquals(404, request(environment, "GET", "/api/v1/trips/$tripId").statusCode())
    }

    @Test
    fun `여정을 삭제하면 진행 중 트립도 함께 종료된다`() {
        val journeyId = createJourney()
        val started = request(environment, "POST", "/api/v1/journeys/$journeyId/trips")
        val tripId = Regex("\"tripId\":\"([^\"]+)\"").find(started.body())!!.groupValues[1]

        assertEquals(204, request(environment, "DELETE", "/api/v1/journeys/$journeyId").statusCode())
        assertEquals(404, request(environment, "GET", "/api/v1/trips/$tripId").statusCode())
    }
}
