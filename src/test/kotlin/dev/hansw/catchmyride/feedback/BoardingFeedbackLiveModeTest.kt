package dev.hansw.catchmyride.feedback

import dev.hansw.catchmyride.ApiContractTestSupport.request
import dev.hansw.catchmyride.push.PushLogRepository
import dev.hansw.catchmyride.push.PushStage
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * API.md §3 — 라이브 발송 상태(push.api-key 설정)에서는 "그날 알림 발송 이력이 없으면 400".
 * dry-run 상태의 무검증 동작은 BoardingFeedbackApiTest가 보장한다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["DATA_GO_KR_KEY=", "SEOUL_OPEN_DATA_KEY=", "push.api-key=test-live-key"],
)
class BoardingFeedbackLiveModeTest {

    @Autowired lateinit var environment: Environment
    @Autowired lateinit var pushLog: PushLogRepository

    @Test
    fun `발송 이력이 없는 날짜는 400, 이력이 생기면 201`() {
        val body = """{"result": "BOARDED", "notifiedDate": "2026-09-02"}"""
        val rejected = request(environment, "POST", "/api/v1/boarding-feedback", body)
        assertEquals(400, rejected.statusCode(), rejected.body())
        assertTrue(rejected.body().contains("INVALID_REQUEST"), rejected.body())

        pushLog.record("dev-user", LocalDate.parse("2026-09-02"), PushStage.REMIND, "9호선 급행", delivered = true)
        val accepted = request(environment, "POST", "/api/v1/boarding-feedback", body)
        assertEquals(201, accepted.statusCode(), accepted.body())
    }
}
