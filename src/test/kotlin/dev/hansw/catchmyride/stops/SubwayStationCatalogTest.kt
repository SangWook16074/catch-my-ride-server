package dev.hansw.catchmyride.stops

import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 역 좌표 조회 명세 — API.md §9-2 "중간 시작"(이미 탄 뒤 하차 알림 시작)에서
 * 유저 좌표를 "지금 있는 역"으로 바꾸는 유일한 근거다. 좌표는 서울열린데이터광장
 * subwayStationMaster를 내장 카탈로그에 합쳐 둔 값 (2026-09-24).
 */
class SubwayStationCatalogTest {

    private val catalog = SubwayStationCatalog(JsonMapper.builder().build())

    @Test
    fun `노선에서 가장 가까운 역을 찾는다`() {
        assertEquals("약수", catalog.nearest("3호선", 37.554565, 127.010449, 1_500.0))
        assertEquals("충무로", catalog.nearest("3호선", 37.561318, 126.994076, 1_500.0))
    }

    @Test
    fun `그 노선 역만 본다 — 강남 한복판이어도 3호선 역은 아니다`() {
        // 강남(2호선·신분당선) 좌표 — 3호선으로 물으면 반경 안에 없다
        assertNull(catalog.nearest("3호선", 37.497414, 127.028008, 800.0))
        assertEquals("강남", catalog.nearest("2호선", 37.497414, 127.028008, 800.0))
    }

    @Test
    fun `역과 좌표 사이 거리 — 탑승역에서 얼마나 멀어졌는지 재는 근거 (2026-09-29)`() {
        // 수유(4호선)에서 미아사거리 좌표까지 2.8km — 두 정거장
        val meters = catalog.distanceTo("4호선", "수유", 37.613292, 127.030053)!!
        assertTrue(meters in 2_700.0..2_850.0, "수유↔미아사거리 ≈ 2.8km, 실제 ${meters.toInt()}m")
        assertNull(catalog.distanceTo("3호선", "수유", 37.613292, 127.030053)) // 수유는 3호선 역이 아니다
        assertNull(catalog.distanceTo("4호선", "없는역", 37.613292, 127.030053))
    }

    @Test
    fun `역 좌표 조회 — 재수집 기준점(seedStop)용`() {
        assertEquals(37.554565 to 127.010449, catalog.coordinatesOf("3호선", "약수"))
        assertNull(catalog.coordinatesOf("4호선", "약수")) // 약수는 3·6호선
    }

    @Test
    fun `반경 밖이면 null — 모르면 위치를 쓰지 않는다 (NFR-03)`() {
        assertNull(catalog.nearest("9호선", 37.554565, 127.010449, 300.0)) // 약수 부근엔 9호선 없음
    }
}
