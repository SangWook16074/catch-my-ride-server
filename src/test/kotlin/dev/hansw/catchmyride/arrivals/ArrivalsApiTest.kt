package dev.hansw.catchmyride.arrivals

import dev.hansw.catchmyride.ApiContractTestSupport.request
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * API.md §2 계약 검증. 키를 빈 값으로 강제해 공공 API를 타지 않는다 —
 * 모든 소스 불능이면 200 + realtimeAvailable=false로 정직하게 내려가는지가 핵심 (NFR-03).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["DATA_GO_KR_KEY=", "SEOUL_OPEN_DATA_KEY="],
)
class ArrivalsApiTest {

    @Autowired
    lateinit var environment: Environment

    @Test
    fun `통근 설정이 없으면 404 SETTING_NOT_FOUND`() {
        request(environment, "DELETE", "/api/v1/commute-setting")

        val response = request(environment, "GET", "/api/v1/arrivals")

        assertEquals(404, response.statusCode())
        assertTrue(response.body().contains("SETTING_NOT_FOUND"), response.body())
    }

    @Test
    fun `소스 전체 불능이면 200에 realtimeAvailable false`() {
        val setting = """
            {
              "home": {"latitude": 37.5219, "longitude": 126.9245},
              "stops": [{"type": "SUBWAY", "stopId": "여의도", "displayName": "여의도역", "routes": ["5호선"]}],
              "walkMinutes": 8,
              "notificationMode": "FIXED",
              "fixedDepartureTime": "08:20",
              "commuteWindow": null,
              "bufferMinutes": 3,
              "activeDays": ["MON"]
            }
        """.trimIndent()
        assertEquals(200, request(environment, "PUT", "/api/v1/commute-setting", setting).statusCode())

        val response = request(environment, "GET", "/api/v1/arrivals")

        assertEquals(200, response.statusCode(), response.body())
        assertTrue(response.body().contains("\"realtimeAvailable\":false"), response.body())
        assertTrue(response.body().contains("\"walkMinutes\":8"), response.body())

        request(environment, "DELETE", "/api/v1/commute-setting") // 다른 테스트에 영향 없게 정리
    }

    @Test
    fun `상태 계산 - mock statusOf와 동일`() {
        val walk = 480 // 8분
        val buffer = 180 // 3분
        assertEquals(ArrivalStatus.MISSED, ArrivalsService.status(400, walk, buffer))   // 도보보다 빨리 옴
        assertEquals(ArrivalStatus.HURRY, ArrivalsService.status(600, walk, buffer))    // 여유 120초 ≤ 버퍼
        assertEquals(ArrivalStatus.RELAXED, ArrivalsService.status(700, walk, buffer))  // 여유 220초 > 버퍼
        assertEquals(ArrivalStatus.HURRY, ArrivalsService.status(480, walk, buffer))    // 여유 0초
        assertEquals(ArrivalStatus.MISSED, ArrivalsService.status(null, walk, buffer))  // 정보 없음
    }
}
