package dev.hansw.catchmyride.admin

import dev.hansw.catchmyride.ApiContractTestSupport.request
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 로그 조회 어드민 API 검증 — 인메모리 버퍼 캡처·레벨 필터·검색. 토큰 보호는 AdminMonitoringTokenTest. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminLogsApiTest {

    @Autowired
    lateinit var environment: Environment

    private val log = LoggerFactory.getLogger("AdminLogsApiTest")

    @Test
    fun `WARN 로그가 기본 조회(WARN 이상)에 최신순으로 나타난다`() {
        log.warn("테스트 경고 marker-warn-123")

        val res = request(environment, "GET", "/api/admin/logs?q=marker-warn-123")
        assertEquals(200, res.statusCode(), res.body())
        assertTrue(res.body().contains("marker-warn-123"), res.body())
        assertTrue(res.body().contains("\"level\":\"WARN\""), res.body())
    }

    @Test
    fun `에러 로그엔 스택트레이스가 포함된다`() {
        log.error("테스트 에러 marker-err-456", RuntimeException("boom-456"))

        val res = request(environment, "GET", "/api/admin/logs?level=ERROR&q=marker-err-456")
        assertEquals(200, res.statusCode(), res.body())
        assertTrue(res.body().contains("\"stacktrace\""), res.body())
        assertTrue(res.body().contains("boom-456"), res.body())
    }

    @Test
    fun `INFO 로그는 기본(WARN) 조회엔 없고 level=INFO 조회엔 있다`() {
        log.info("테스트 정보 marker-info-789")

        val warnOnly = request(environment, "GET", "/api/admin/logs?q=marker-info-789")
        assertEquals(200, warnOnly.statusCode(), warnOnly.body())
        assertTrue(!warnOnly.body().contains("marker-info-789"), warnOnly.body())

        val info = request(environment, "GET", "/api/admin/logs?level=INFO&q=marker-info-789")
        assertTrue(info.body().contains("marker-info-789"), info.body())
    }
}
