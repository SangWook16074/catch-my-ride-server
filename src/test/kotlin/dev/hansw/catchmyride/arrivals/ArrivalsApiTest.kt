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
    fun `지하철 매칭 - 저장 방면이 있으면 그 방면만, 없으면 전 방면`() {
        val info = dev.hansw.catchmyride.spike.ArrivalInfo(
            source = "SEOUL_SUBWAY", stopId = "수유", routeName = "당고개행 - 성신여대입구방면",
            direction = "상행", directionLabel = "당고개행", predictedSecondsToArrival = 300,
            remainingStops = null, isExpress = false, rawMessage = null, line = "4호선",
        )
        val stop = { direction: String? ->
            dev.hansw.catchmyride.commute.CommuteStop(
                type = dev.hansw.catchmyride.stops.StopType.SUBWAY,
                stopId = "수유", displayName = "수유역", routes = listOf("4호선"), direction = direction,
            )
        }
        // 방면 미저장(구버전 경로) — 전 방면 매칭 (하위호환)
        assertTrue(ArrivalsService.matches(stop(null), "4호선", info))
        // 같은 방면만 통과 — 반대 방향 열차로 추천·푸시가 나가지 않는다 (FR-501 개정)
        assertTrue(ArrivalsService.matches(stop("상행"), "4호선", info))
        assertEquals(false, ArrivalsService.matches(stop("하행"), "4호선", info))
    }

    @Test
    fun `버스 매칭 - 방면 저장값은 무시 (정류장이 곧 방향)`() {
        val info = dev.hansw.catchmyride.spike.ArrivalInfo(
            source = "TOPIS", stopId = "19284", routeName = "720", direction = null,
            predictedSecondsToArrival = 300, remainingStops = 3, isExpress = null, rawMessage = null,
        )
        val stop = dev.hansw.catchmyride.commute.CommuteStop(
            type = dev.hansw.catchmyride.stops.StopType.SEOUL_BUS,
            stopId = "19284", displayName = "여의도환승센터", routes = listOf("720"), direction = "상행",
        )
        assertTrue(ArrivalsService.matches(stop, "720", info))
    }

    @Test
    fun `trainLineNm 파싱 - 행선지만 추출, 형식이 다르면 null`() {
        assertEquals(
            "당고개행",
            dev.hansw.catchmyride.spike.adapter.SeoulSubwayAdapter.parseDestination("당고개행 - 성신여대입구방면"),
        )
        assertEquals("급행 김포공항행", dev.hansw.catchmyride.spike.adapter.SeoulSubwayAdapter.parseDestination("급행 김포공항행 - 가양방면"))
        assertEquals(null, dev.hansw.catchmyride.spike.adapter.SeoulSubwayAdapter.parseDestination("이상한형식"))
        assertEquals(null, dev.hansw.catchmyride.spike.adapter.SeoulSubwayAdapter.parseDestination(null))
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
