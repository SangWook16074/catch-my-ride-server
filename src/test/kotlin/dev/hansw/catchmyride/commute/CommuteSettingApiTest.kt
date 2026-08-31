package dev.hansw.catchmyride.commute

import dev.hansw.catchmyride.ApiContractTestSupport.request
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** API.md §1 계약 검증 — 검증 규칙·문구는 클라이언트 mock.ts와 동일해야 한다. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["DATA_GO_KR_KEY=", "SEOUL_OPEN_DATA_KEY="],
)
class CommuteSettingApiTest {

    @Autowired
    lateinit var environment: Environment

    private fun validSetting(routes: String = "\"9호선 급행\"") = """
        {
          "home": {"latitude": 37.5219, "longitude": 126.9245},
          "stops": [{"type": "SUBWAY", "stopId": "여의도", "displayName": "여의도역", "routes": [$routes]}],
          "walkMinutes": 8,
          "notificationMode": "FIXED",
          "fixedDepartureTime": "08:20",
          "commuteWindow": null,
          "bufferMinutes": 3,
          "activeDays": ["MON", "TUE", "WED", "THU", "FRI"]
        }
    """.trimIndent()

    @Test
    fun `PUT 후 GET으로 왕복하고 DELETE는 204, 이후 GET은 SETTING_NOT_FOUND`() {
        val put = request(environment, "PUT", "/api/v1/commute-setting", validSetting())
        assertEquals(200, put.statusCode(), put.body())
        assertTrue(put.body().contains("여의도"), put.body())

        val get = request(environment, "GET", "/api/v1/commute-setting")
        assertEquals(200, get.statusCode(), get.body())
        assertTrue(get.body().contains("9호선 급행"), "저장된 노선 표기가 그대로 와야 함: ${get.body()}")
        assertTrue(get.body().contains("\"walkMinutes\":8"), get.body())

        val delete = request(environment, "DELETE", "/api/v1/commute-setting")
        assertEquals(204, delete.statusCode())
        assertTrue(delete.body().isEmpty(), "204는 바디가 없어야 함")

        val after = request(environment, "GET", "/api/v1/commute-setting")
        assertEquals(404, after.statusCode())
        assertTrue(after.body().contains("SETTING_NOT_FOUND"), after.body())
    }

    @Test
    fun `검증 실패는 400 INVALID_REQUEST와 mock과 같은 문구`() {
        val noStops = validSetting().replace(Regex("\"stops\": \\[.*?\\],"), "\"stops\": [],")
        val r1 = request(environment, "PUT", "/api/v1/commute-setting", noStops)
        assertEquals(400, r1.statusCode())
        assertTrue(r1.body().contains("정류장을 1개 이상"), r1.body())

        val noTime = validSetting().replace("\"fixedDepartureTime\": \"08:20\"", "\"fixedDepartureTime\": null")
        val r2 = request(environment, "PUT", "/api/v1/commute-setting", noTime)
        assertEquals(400, r2.statusCode())
        assertTrue(r2.body().contains("정시 모드는 출발 시각"), r2.body())

        val zeroWalk = validSetting().replace("\"walkMinutes\": 8", "\"walkMinutes\": 0")
        val r3 = request(environment, "PUT", "/api/v1/commute-setting", zeroWalk)
        assertEquals(400, r3.statusCode())
        assertTrue(r3.body().contains("도보 시간은 1분 이상"), r3.body())
    }

    @Test
    fun `깨진 본문도 공통 에러 바디로 온다`() {
        val response = request(environment, "PUT", "/api/v1/commute-setting", "{broken json")
        assertEquals(400, response.statusCode())
        assertTrue(response.body().contains("INVALID_REQUEST"), response.body())
    }

    @Test
    fun `존재하지 않는 경로도 code 있는 에러 바디로 온다`() {
        val response = request(environment, "GET", "/api/v1/does-not-exist")
        assertEquals(404, response.statusCode())
        assertTrue(response.body().contains("\"code\""), "Spring 기본 에러 바디면 안 됨: ${response.body()}")
    }
}
