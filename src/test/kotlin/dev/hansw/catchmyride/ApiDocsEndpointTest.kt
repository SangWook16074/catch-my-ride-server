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
 * API 문서(/swagger-ui.html)가 열리는지 검증한다.
 * 스펙 원천은 API.md — static/openapi.yaml은 그 계약의 Swagger 표현이고,
 * swagger-ui 에셋은 webjar에서 서빙되므로 세 경로가 모두 200이어야 문서가 뜬다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiDocsEndpointTest {

    @Autowired
    lateinit var environment: Environment

    private fun get(path: String): HttpResponse<String> {
        val port = environment.getProperty("local.server.port")
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).build()
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `swagger-ui 페이지가 열린다`() {
        val response = get("/swagger-ui.html")
        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("SwaggerUIBundle"), "swagger-ui 초기화 스크립트가 없음")
    }

    @Test
    fun `openapi 스펙이 서빙되고 v0_2 계약의 핵심 경로를 담고 있다`() {
        val response = get("/openapi.yaml")
        assertEquals(200, response.statusCode())
        for (path in listOf("/api/v1/commute-setting", "/api/v1/arrivals", "/api/v1/boarding-feedback", "/api/v1/stops/search", "/api/v1/push-token")) {
            assertTrue(response.body().contains(path), "스펙에 $path 누락 — API.md와 동기화할 것")
        }
    }

    @Test
    fun `openapi 스펙이 유효한 YAML이다`() {
        // Swagger UI는 브라우저에서 파싱하므로 문법 오류는 서버 200으로는 안 잡힌다 —
        // "9분후[3번째 전]" 같은 미인용 특수문자 회귀 방지 (2026-08-28 실사고)
        val body = get("/openapi.yaml").body()
        val root = tools.jackson.dataformat.yaml.YAMLMapper.builder().build().readTree(body)
        assertTrue(root.path("paths").size() >= 5, "paths가 비정상적으로 적음 — 파싱 결과: ${root.path("paths").size()}")
    }

    @Test
    fun `webjar의 swagger-ui 에셋이 서빙된다`() {
        val response = get("/webjars/swagger-ui/5.25.3/swagger-ui-bundle.js")
        assertEquals(200, response.statusCode(), "webjar /webjars/** 매핑 확인 필요")
    }
}
