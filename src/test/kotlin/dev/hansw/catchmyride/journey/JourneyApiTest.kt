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
 * API.md §9-1 — 여정 CRUD 계약. 검증은 형태·존재 확인까지(역 존재, 노선 경유 여부) —
 * 경로 탐색이 아니다 (§1.4). 카탈로그 실데이터(여의도·당산·강남) 기준.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JourneyApiTest {

    @Autowired lateinit var environment: Environment
    @Autowired lateinit var jdbc: JdbcClient

    @BeforeEach
    fun wipe() {
        jdbc.sql("DELETE FROM trip_push_log").update()
        jdbc.sql("DELETE FROM trip").update()
        jdbc.sql("DELETE FROM journey").update()
    }

    private fun journeyJson(label: String = "회사") = """
        {"label":"$label","repeatDays":["MON","FRI"],
         "legs":[{"type":"SUBWAY","line":"9호선 급행","boardStop":"여의도","alightStop":"당산"}]}
    """.trimIndent()

    @Test
    fun `여정을 만들고 목록으로 조회하고 삭제한다`() {
        val created = request(environment, "POST", "/api/v1/journeys", journeyJson())
        assertEquals(201, created.statusCode(), created.body())
        assertTrue(created.body().contains("\"label\":\"회사\""), created.body())
        assertTrue(created.body().contains("\"lastUsedAt\":null"), created.body())
        val id = Regex("\"id\":\"([^\"]+)\"").find(created.body())!!.groupValues[1]

        val list = request(environment, "GET", "/api/v1/journeys")
        assertEquals(200, list.statusCode())
        assertTrue(list.body().contains(id), list.body())

        val deleted = request(environment, "DELETE", "/api/v1/journeys/$id")
        assertEquals(204, deleted.statusCode())

        val deletedAgain = request(environment, "DELETE", "/api/v1/journeys/$id")
        assertEquals(404, deletedAgain.statusCode())
        assertTrue(deletedAgain.body().contains("SETTING_NOT_FOUND"), deletedAgain.body())
    }

    @Test
    fun `라벨 중복과 11개째는 400이다`() {
        assertEquals(201, request(environment, "POST", "/api/v1/journeys", journeyJson()).statusCode())
        val dup = request(environment, "POST", "/api/v1/journeys", journeyJson())
        assertEquals(400, dup.statusCode(), dup.body())
        assertTrue(dup.body().contains("INVALID_REQUEST"), dup.body())

        for (i in 1 until MAX_JOURNEYS) {
            assertEquals(201, request(environment, "POST", "/api/v1/journeys", journeyJson("여정$i")).statusCode())
        }
        val over = request(environment, "POST", "/api/v1/journeys", journeyJson("초과"))
        assertEquals(400, over.statusCode(), over.body())
        assertTrue(over.body().contains("최대"), over.body())
    }

    @Test
    fun `구간 검증 - 같은 역, 없는 역, 경유하지 않는 노선은 400이다`() {
        val sameStop = """
            {"label":"같은역","repeatDays":[],
             "legs":[{"type":"SUBWAY","line":"9호선 급행","boardStop":"여의도","alightStop":"여의도"}]}
        """.trimIndent()
        assertEquals(400, request(environment, "POST", "/api/v1/journeys", sameStop).statusCode())

        val unknownStop = """
            {"label":"없는역","repeatDays":[],
             "legs":[{"type":"SUBWAY","line":"9호선 급행","boardStop":"이세계","alightStop":"당산"}]}
        """.trimIndent()
        val unknown = request(environment, "POST", "/api/v1/journeys", unknownStop)
        assertEquals(400, unknown.statusCode(), unknown.body())
        assertTrue(unknown.body().contains("알 수 없는 역"), unknown.body())

        // 여의도는 2호선이 지나지 않는다
        val wrongLine = """
            {"label":"엉뚱노선","repeatDays":[],
             "legs":[{"type":"SUBWAY","line":"2호선","boardStop":"여의도","alightStop":"당산"}]}
        """.trimIndent()
        assertEquals(400, request(environment, "POST", "/api/v1/journeys", wrongLine).statusCode())

        // v1은 지하철만
        val bus = """
            {"label":"버스","repeatDays":[],
             "legs":[{"type":"SEOUL_BUS","line":"720","boardStop":"19284","alightStop":"19169"}]}
        """.trimIndent()
        val busResponse = request(environment, "POST", "/api/v1/journeys", bus)
        assertEquals(400, busResponse.statusCode(), busResponse.body())
        assertTrue(busResponse.body().contains("SUBWAY"), busResponse.body())
    }

    @Test
    fun `수정은 라벨과 구간을 갱신하고 없는 여정은 404다`() {
        val created = request(environment, "POST", "/api/v1/journeys", journeyJson())
        val id = Regex("\"id\":\"([^\"]+)\"").find(created.body())!!.groupValues[1]

        val updated = request(
            environment, "PUT", "/api/v1/journeys/$id",
            """
            {"label":"본가","repeatDays":["SAT"],
             "legs":[{"type":"SUBWAY","line":"2호선","boardStop":"당산","alightStop":"강남"}]}
            """.trimIndent(),
        )
        assertEquals(200, updated.statusCode(), updated.body())
        assertTrue(updated.body().contains("\"label\":\"본가\""), updated.body())

        val missing = request(environment, "PUT", "/api/v1/journeys/no-such", journeyJson("다른이름"))
        assertEquals(404, missing.statusCode(), missing.body())
    }
}
