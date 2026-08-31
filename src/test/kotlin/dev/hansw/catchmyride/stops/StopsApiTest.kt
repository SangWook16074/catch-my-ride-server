package dev.hansw.catchmyride.stops

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
 * API.md §5 계약의 HTTP 동작 검증.
 * 키를 빈 값으로 강제해 외부(버스 API) 호출 없이 돈다 — 지하철(내장 데이터) 소스만으로 검색이 성립해야 한다
 * (소스 격리 원칙: 버스 소스 불능이어도 검색은 동작).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["DATA_GO_KR_KEY=", "SEOUL_OPEN_DATA_KEY="],
)
class StopsApiTest {

    @Autowired
    lateinit var environment: Environment

    private fun get(path: String): HttpResponse<String> {
        val port = environment.getProperty("local.server.port")
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path")).build()
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `역명 검색이 지하철 결과를 돌려준다`() {
        val response = get("/api/v1/stops/search?query=%EC%97%AC%EC%9D%98%EB%8F%84") // 여의도

        assertEquals(200, response.statusCode(), response.body())
        assertTrue(response.body().contains("SUBWAY"), response.body())
        assertTrue(response.body().contains("여의도역"), response.body())
        assertTrue(response.body().contains("5호선"), response.body())
    }

    @Test
    fun `역 자를 붙인 검색어도 지하철에 매칭된다`() {
        // SERVER_FEEDBACK.md 재검증 버그 회귀 방지: "여의도역"으로 검색해도 지하철이 나와야 한다
        val response = get("/api/v1/stops/search?query=%EC%97%AC%EC%9D%98%EB%8F%84%EC%97%AD") // 여의도역

        assertEquals(200, response.statusCode(), response.body())
        assertTrue(response.body().contains("SUBWAY"), "지하철 누락: ${response.body()}")
        assertTrue(response.body().contains("\"stopId\":\"여의도\""), "stopId는 내부 역명 유지: ${response.body()}")
    }

    @Test
    fun `서울역은 역이 중복되지 않고 노선이 병합돼 있다`() {
        val response = get("/api/v1/stops/search?query=%EC%84%9C%EC%9A%B8%EC%97%AD") // 서울역

        assertEquals(200, response.statusCode(), response.body())
        assertTrue(!response.body().contains("서울역역"), "표시명에 역이 중복됨: ${response.body()}")
        assertTrue(response.body().contains("GTX-A"), "분리돼 있던 GTX-A 노선이 병합돼야 함: ${response.body()}")
        assertTrue(response.body().contains("1호선"), response.body())
    }

    @Test
    fun `2자 미만 query는 400 INVALID_REQUEST`() {
        val response = get("/api/v1/stops/search?query=%EC%97%AC") // 여

        assertEquals(400, response.statusCode())
        assertTrue(response.body().contains("INVALID_REQUEST"))
    }

    @Test
    fun `query 누락도 400 INVALID_REQUEST`() {
        val response = get("/api/v1/stops/search")

        assertEquals(400, response.statusCode())
        assertTrue(response.body().contains("INVALID_REQUEST"))
    }

    @Test
    fun `지하철 노선 조회가 호선 목록을 돌려준다`() {
        val response = get("/api/v1/stops/routes?type=SUBWAY&stopId=%EC%97%AC%EC%9D%98%EB%8F%84") // 여의도

        assertEquals(200, response.statusCode(), response.body())
        assertTrue(response.body().contains("5호선"), response.body())
        assertTrue(response.body().contains("9호선"), response.body())
    }

    @Test
    fun `잘못된 type은 400 INVALID_REQUEST`() {
        val response = get("/api/v1/stops/routes?type=KTX&stopId=x")

        assertEquals(400, response.statusCode())
        assertTrue(response.body().contains("INVALID_REQUEST"))
    }

    @Test
    fun `없는 역명 노선 조회는 400`() {
        val response = get("/api/v1/stops/routes?type=SUBWAY&stopId=%EC%97%86%EB%8A%94%EC%97%AD") // 없는역

        assertEquals(400, response.statusCode())
    }
}
