package dev.hansw.catchmyride.api

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals

/**
 * KeepAliveWarmer의 자기 호출이 실제 API 경로에 도달하는지 검증한다.
 * 워밍 요청이 서버를 통과했다면 http.server.requests 미터에 해당 uri 태그가 생긴다
 * (actuator는 없는 태그 조회에 404를 준다 — 이것으로 도달 여부를 판별).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["warmup.enabled=true"],
)
class KeepAliveWarmerTest {

    @Autowired
    lateinit var warmer: KeepAliveWarmer

    @Autowired
    lateinit var environment: Environment

    @Test
    fun `워밍업 자기 호출이 commute-setting 경로를 실제로 태운다`() {
        warmer.keepAlive() // 기동 시 1회는 이미 돌았지만 명시 호출로 결정적으로 만든다

        val port = environment.getProperty("local.server.port")
        val uri = "http://localhost:$port/actuator/metrics/http.server.requests?tag=uri:/api/v1/commute-setting"
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(uri)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertEquals(200, response.statusCode(), "워밍 요청이 서버에 도달하지 않았다: ${response.body()}")
    }
}
