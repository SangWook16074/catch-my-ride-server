package dev.hansw.catchmyride.admin

import dev.hansw.catchmyride.ApiContractTestSupport.request
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 모니터링 어드민 API + 대시보드 정적 페이지 검증. 토큰 보호는 AdminMonitoringTokenTest. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminMonitoringApiTest {

    @Autowired
    lateinit var environment: Environment

    @Test
    fun `실요청 후 조회하면 해당 엔드포인트의 횟수와 응답 시간 통계가 온다`() {
        // 미터를 만들 실요청 — 지하철 카탈로그는 내장 데이터라 외부 의존 없음
        request(environment, "GET", "/api/v1/stops/search?query=여의도")

        val res = request(environment, "GET", "/api/admin/monitoring")
        assertEquals(200, res.statusCode(), res.body())
        assertTrue(res.body().contains("/api/v1/stops/search"), res.body())
        assertTrue(res.body().contains("\"totalRequests\""), res.body())
        assertTrue(res.body().contains("\"p95Ms\""), res.body())
    }

    @Test
    fun `기본 응답에선 감시 트래픽(actuator·어드민)이 숨고 all=true면 보인다`() {
        request(environment, "GET", "/actuator/health")
        request(environment, "GET", "/api/admin/monitoring") // 어드민 호출 자신도 미터에 남는다

        val default = request(environment, "GET", "/api/admin/monitoring")
        assertTrue(!default.body().contains("/actuator/health"), default.body())
        assertTrue(!default.body().contains("\"uri\":\"/api/admin/monitoring\""), default.body())

        val all = request(environment, "GET", "/api/admin/monitoring?all=true")
        assertTrue(all.body().contains("/actuator/health"), all.body())
    }

    @Test
    fun `모니터링 대시보드 페이지가 서빙된다`() {
        val res = request(environment, "GET", "/monitoring.html")
        assertEquals(200, res.statusCode())
        assertTrue(res.body().contains("서버 모니터링"), "대시보드 HTML이어야 함")
    }
}

/** ADMIN_TOKEN이 설정된 배포 환경의 보호 동작 검증. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["admin.token=test-admin-token"],
)
class AdminMonitoringTokenTest {

    @Autowired
    lateinit var environment: Environment

    @Test
    fun `토큰 없이는 401, 헤더나 쿼리로 일치하면 200`() {
        val noToken = request(environment, "GET", "/api/admin/monitoring")
        assertEquals(401, noToken.statusCode(), noToken.body())
        assertTrue(noToken.body().contains("UNAUTHORIZED"), noToken.body())

        val wrong = request(environment, "GET", "/api/admin/monitoring?token=nope")
        assertEquals(401, wrong.statusCode(), wrong.body())

        val byQuery = request(environment, "GET", "/api/admin/monitoring?token=test-admin-token")
        assertEquals(200, byQuery.statusCode(), byQuery.body())

        val port = environment.getProperty("local.server.port")
        val byHeader = java.net.http.HttpClient.newHttpClient().send(
            java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:$port/api/admin/monitoring"))
                .header("X-Admin-Token", "test-admin-token").build(),
            java.net.http.HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, byHeader.statusCode(), byHeader.body())
    }
}
