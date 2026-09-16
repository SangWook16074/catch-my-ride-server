package dev.hansw.catchmyride.feedback

import dev.hansw.catchmyride.ApiContractTestSupport.request
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** API.md §3 계약 검증 — 같은 날짜 재제출은 갱신(에러 아님). */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["DATA_GO_KR_KEY=", "SEOUL_OPEN_DATA_KEY="],
)
class BoardingFeedbackApiTest {

    @Autowired
    lateinit var environment: Environment

    @Test
    fun `정상 제출은 201 recorded true, 같은 날짜 재제출도 성공`() {
        val body = """{"result": "BOARDED", "notifiedDate": "2026-08-28"}"""
        val first = request(environment, "POST", "/api/v1/boarding-feedback", body)
        assertEquals(201, first.statusCode(), first.body())
        assertTrue(first.body().contains("\"recorded\":true"), first.body())

        val again = request(environment, "POST", "/api/v1/boarding-feedback", """{"result": "MISSED", "notifiedDate": "2026-08-28"}""")
        assertEquals(201, again.statusCode(), "재제출은 마지막 값으로 갱신: ${again.body()}")
    }

    @Test
    fun `이력 조회는 notifiedDate 내림차순, limit 적용 (§3-2)`() {
        for ((date, result) in listOf("2026-09-01" to "BOARDED", "2026-09-02" to "MISSED", "2026-09-03" to "BOARDED")) {
            val posted = request(environment, "POST", "/api/v1/boarding-feedback", """{"result": "$result", "notifiedDate": "$date"}""")
            assertEquals(201, posted.statusCode(), posted.body())
        }

        val history = request(environment, "GET", "/api/v1/boarding-feedback/history")
        assertEquals(200, history.statusCode(), history.body())
        val body = history.body()
        assertTrue(body.contains("""{"date":"2026-09-03","result":"BOARDED"}"""), body)
        assertTrue(body.contains("""{"date":"2026-09-02","result":"MISSED"}"""), body)
        assertTrue(
            body.indexOf("2026-09-03") < body.indexOf("2026-09-02") && body.indexOf("2026-09-02") < body.indexOf("2026-09-01"),
            "내림차순이어야 한다: $body",
        )

        val limited = request(environment, "GET", "/api/v1/boarding-feedback/history?limit=1")
        assertEquals(200, limited.statusCode(), limited.body())
        assertTrue(limited.body().contains("2026-09-03"), limited.body())
        assertTrue(!limited.body().contains("2026-09-02"), "limit=1이면 최신 1건만: ${limited.body()}")
    }

    @Test
    fun `잘못된 result·날짜는 400 INVALID_REQUEST`() {
        val badResult = request(environment, "POST", "/api/v1/boarding-feedback", """{"result": "MAYBE", "notifiedDate": "2026-08-28"}""")
        assertEquals(400, badResult.statusCode())
        assertTrue(badResult.body().contains("BOARDED 또는 MISSED"), badResult.body())

        val badDate = request(environment, "POST", "/api/v1/boarding-feedback", """{"result": "BOARDED", "notifiedDate": "28-08-2026"}""")
        assertEquals(400, badDate.statusCode())
        assertTrue(badDate.body().contains("YYYY-MM-DD"), badDate.body())
    }
}
