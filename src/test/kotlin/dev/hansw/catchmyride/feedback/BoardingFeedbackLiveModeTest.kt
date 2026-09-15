package dev.hansw.catchmyride.feedback

import dev.hansw.catchmyride.ApiContractTestSupport.request
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import kotlin.test.assertEquals

/**
 * API.md §3 개정(2026-09-15) — 라이브 발송 상태에서도 알림 발송 이력과 무관하게 기록한다.
 * (구 계약 "그날 발송 이력이 없으면 400"은 "탔어요"가 하차 알림 브리지의 진입점이 되면서 제거.)
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["DATA_GO_KR_KEY=", "SEOUL_OPEN_DATA_KEY=", "push.keystore-path=test-live-keystore.p12"],
)
class BoardingFeedbackLiveModeTest {

    @Autowired lateinit var environment: Environment

    @Test
    fun `라이브 모드에서도 발송 이력 없이 201 — 알림 여부와 무관하게 기록한다`() {
        val body = """{"result": "BOARDED", "notifiedDate": "2026-09-02"}"""
        val accepted = request(environment, "POST", "/api/v1/boarding-feedback", body)
        assertEquals(201, accepted.statusCode(), accepted.body())
    }
}
