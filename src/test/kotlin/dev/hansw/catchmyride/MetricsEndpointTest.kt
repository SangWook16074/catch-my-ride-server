package dev.hansw.catchmyride

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 응답 시간 모니터링용 /actuator/metrics가 노출되는지 검증한다.
 * 개별 요청 로그는 RequestTimingFilter, 집계는 http.server.requests 미터로 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MetricsEndpointTest {

    @Autowired
    lateinit var environment: Environment

    @Test
    fun `metrics 엔드포인트가 열려 있고 http_server_requests 미터를 집계한다`() {
        val port = environment.getProperty("local.server.port")
        val client = HttpClient.newHttpClient()

        // 실요청을 한 번 보내야 http.server.requests 미터가 생성된다
        client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/api/v1/stops/search?query=여의도")).build(),
            HttpResponse.BodyHandlers.discarding(),
        )

        val response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:$port/actuator/metrics/http.server.requests")).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("http.server.requests"), "미터 이름이 응답에 있어야 한다: ${response.body()}")
    }
}
