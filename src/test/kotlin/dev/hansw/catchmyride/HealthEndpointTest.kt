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
 * 운영 감시(UptimeRobot)와 docker compose healthcheck가 사용하는
 * /actuator/health 엔드포인트가 열려 있는지 검증한다 (DEPLOY.md D-7).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthEndpointTest {

    @Autowired
    lateinit var environment: Environment

    @Test
    fun `health 엔드포인트가 UP을 돌려준다`() {
        val port = environment.getProperty("local.server.port")
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port/actuator/health")).build()

        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())

        assertEquals(200, response.statusCode(), "응답: ${response.body()}")
        assertTrue(response.body().contains("UP"), "응답에 UP이 없음: ${response.body()}")
    }
}
