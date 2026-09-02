package dev.hansw.catchmyride.map

import dev.hansw.catchmyride.ApiContractTestSupport.request
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * API.md §6·§7 계약 검증 — 검증 실패는 400 공통 에러 바디, NCP 키 미설정은 503(클라이언트 폴백).
 * 상류 실호출은 🧑 NCP 키 발급 후 배포 검증(SERVER_FEEDBACK.md §4 curl)으로 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MapApiTest {

    @Autowired
    lateinit var environment: Environment

    @Test
    fun `map-preview - 좌표 범위 밖은 400 INVALID_REQUEST`() {
        val response = request(environment, "GET", "/api/v1/map-preview?lat=91&lng=126.9245")
        assertEquals(400, response.statusCode(), response.body())
        assertTrue(response.body().contains("INVALID_REQUEST"), response.body())
    }

    @Test
    fun `map-preview - 숫자가 아닌 좌표·필수 파라미터 누락도 400 공통 에러 바디`() {
        val notNumber = request(environment, "GET", "/api/v1/map-preview?lat=abc&lng=126.9")
        assertEquals(400, notNumber.statusCode(), notNumber.body())
        assertTrue(notNumber.body().contains("INVALID_REQUEST"), notNumber.body())

        val missing = request(environment, "GET", "/api/v1/map-preview?lat=37.5")
        assertEquals(400, missing.statusCode(), missing.body())
        assertTrue(missing.body().contains("INVALID_REQUEST"), missing.body())
    }

    @Test
    fun `map-preview - NCP 키 미설정이면 503 UPSTREAM_UNAVAILABLE (클라이언트 OSM 폴백)`() {
        val response = request(environment, "GET", "/api/v1/map-preview?lat=37.5219&lng=126.9245&w=708&h=320")
        assertEquals(503, response.statusCode(), response.body())
        assertTrue(response.body().contains("UPSTREAM_UNAVAILABLE"), response.body())
    }

    @Test
    fun `geocode - 2자 미만 검색어는 400`() {
        val response = request(environment, "GET", "/api/v1/geocode?query=a")
        assertEquals(400, response.statusCode(), response.body())
        assertTrue(response.body().contains("INVALID_REQUEST"), response.body())
    }

    @Test
    fun `geocode - NCP 키 미설정이면 503`() {
        val response = request(environment, "GET", "/api/v1/geocode?query=%EC%97%AC%EC%9D%98%EA%B3%B5%EC%9B%90%EB%A1%9C")
        assertEquals(503, response.statusCode(), response.body())
        assertTrue(response.body().contains("UPSTREAM_UNAVAILABLE"), response.body())
    }
}
