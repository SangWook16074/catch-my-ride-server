package dev.hansw.catchmyride.feedback

import dev.hansw.catchmyride.ApiContractTestSupport.request
import dev.hansw.catchmyride.feedback.BufferRecommendationController.Companion.evaluate
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.simple.JdbcClient
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * API.md §3-1 버퍼 추천 규칙 명세:
 * 최근 5건 중 MISSED ≥ 2건이면(표본 ≥ 3건) +5분 제안. 표본이 적으면 제안하지 않는다.
 */
class BufferRecommendationRuleTest {

    @Test
    fun `최근 5건 중 MISSED 2건 이상이면 추천한다`() {
        val result = evaluate(listOf("MISSED", "BOARDED", "MISSED", "BOARDED", "BOARDED"))
        assertTrue(result.recommend)
        assertEquals(2, result.missedCount)
        assertEquals(5, result.sampleSize)
        assertEquals(5, result.suggestedIncrementMinutes)
    }

    @Test
    fun `MISSED가 1건이면 추천하지 않는다`() {
        assertFalse(evaluate(listOf("MISSED", "BOARDED", "BOARDED", "BOARDED", "BOARDED")).recommend)
    }

    @Test
    fun `표본이 3건 미만이면 MISSED 비율이 높아도 추천하지 않는다`() {
        // 이제 막 쓰기 시작한 유저에게 데이터 2건으로 설정 변경을 권하지 않는다
        assertFalse(evaluate(listOf("MISSED", "MISSED")).recommend)
        assertFalse(evaluate(emptyList()).recommend)
    }
}

/** §3-1 API 계약 — 피드백 누적 → 추천 응답. 표본은 최근 5건만 본다(윈도 슬라이딩). */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["DATA_GO_KR_KEY=", "SEOUL_OPEN_DATA_KEY="],
)
class BufferRecommendationApiTest {

    @Autowired
    lateinit var environment: Environment

    @Autowired
    lateinit var jdbc: JdbcClient

    @BeforeEach
    fun wipe() {
        // 공유 H2 — 다른 피드백 테스트의 잔여 데이터가 표본에 섞이지 않게 비운다
        jdbc.sql("DELETE FROM boarding_feedback").update()
    }

    private fun submit(date: String, result: String) {
        val response = request(
            environment, "POST", "/api/v1/boarding-feedback",
            """{"result": "$result", "notifiedDate": "$date"}""",
        )
        assertEquals(201, response.statusCode(), response.body())
    }

    @Test
    fun `놓침이 쌓이면 추천이 켜지고, 이후 탑승이 이어지면 꺼진다`() {
        submit("2026-09-01", "MISSED")
        submit("2026-09-02", "BOARDED")
        val early = request(environment, "GET", "/api/v1/buffer-recommendation")
        assertEquals(200, early.statusCode(), early.body())
        assertTrue(early.body().contains("\"recommend\":false"), "표본 2건은 추천 금지: ${early.body()}")

        submit("2026-09-03", "MISSED")
        val triggered = request(environment, "GET", "/api/v1/buffer-recommendation")
        assertTrue(triggered.body().contains("\"recommend\":true"), triggered.body())
        assertTrue(triggered.body().contains("\"suggestedIncrementMinutes\":5"), triggered.body())

        // 놓침 2건이 최근 5건 창 밖으로 밀려나면 추천이 꺼진다
        for (day in 4..8) {
            submit("2026-09-0$day", "BOARDED")
        }
        val recovered = request(environment, "GET", "/api/v1/buffer-recommendation")
        assertTrue(recovered.body().contains("\"recommend\":false"), recovered.body())
    }
}
