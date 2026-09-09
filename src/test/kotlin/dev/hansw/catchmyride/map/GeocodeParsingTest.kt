package dev.hansw.catchmyride.map

import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** API.md §7 — NCP Geocoding 응답 매핑 규칙 (x=경도·y=위도 문자열, 최대 10건, 불량 행 제외). */
class GeocodeParsingTest {

    private val controller = GeocodeController(
        ncp = NcpMapClient(NcpMapProperties()),
        rateLimiter = MapRateLimiter(java.time.Clock.systemUTC()),
        objectMapper = ObjectMapper(),
    )

    @Test
    fun `x·y 문자열을 경도·위도 숫자로 변환한다`() {
        val body = """
            {"status":"OK","addresses":[
              {"roadAddress":"서울특별시 영등포구 여의공원로 101","jibunAddress":"서울특별시 영등포구 여의도동 23","x":"126.9245000","y":"37.5219000"}
            ]}
        """.trimIndent()
        val results = controller.parse(body)
        assertEquals(1, results.size)
        assertEquals("서울특별시 영등포구 여의공원로 101", results[0].roadAddress)
        assertEquals("서울특별시 영등포구 여의도동 23", results[0].jibunAddress)
        assertEquals(126.9245, results[0].longitude, 1e-6)
        assertEquals(37.5219, results[0].latitude, 1e-6)
    }

    @Test
    fun `좌표가 숫자가 아니거나 없는 행은 제외한다`() {
        val body = """
            {"addresses":[
              {"roadAddress":"정상","jibunAddress":"","x":"127.0","y":"37.0"},
              {"roadAddress":"x 불량","jibunAddress":"","x":"abc","y":"37.0"},
              {"roadAddress":"y 없음","jibunAddress":"","x":"127.0"}
            ]}
        """.trimIndent()
        val results = controller.parse(body)
        assertEquals(1, results.size)
        assertEquals("정상", results[0].roadAddress)
    }

    @Test
    fun `역지오코딩 - roadaddr와 addr을 각각 조립한다`() {
        val body = """
            {"status":{"code":0},"results":[
              {"name":"addr","region":{"area1":{"name":"경기도"},"area2":{"name":"성남시 분당구"},"area3":{"name":"정자동"},"area4":{"name":""}},
               "land":{"type":"1","number1":"178","number2":"1"}},
              {"name":"roadaddr","region":{"area1":{"name":"경기도"},"area2":{"name":"성남시 분당구"},"area3":{"name":"정자동"},"area4":{"name":""}},
               "land":{"name":"정자일로","number1":"95","number2":""}}
            ]}
        """.trimIndent()
        val result = controller.parseReverse(body)
        assertEquals("경기도 성남시 분당구 정자일로 95", result.roadAddress)
        assertEquals("경기도 성남시 분당구 정자동 178-1", result.jibunAddress)
    }

    @Test
    fun `역지오코딩 - 결과 없으면 빈 문자열 두 개`() {
        val result = controller.parseReverse("""{"status":{"code":3,"name":"no results"},"results":[]}""")
        assertEquals("", result.roadAddress)
        assertEquals("", result.jibunAddress)
    }

    @Test
    fun `최대 10건으로 자른다, addresses 없으면 빈 배열`() {
        val many = (1..15).joinToString(",") { """{"roadAddress":"주소$it","jibunAddress":"","x":"127.0","y":"37.0"}""" }
        assertEquals(10, controller.parse("""{"addresses":[$many]}""").size)
        assertTrue(controller.parse("""{"status":"OK"}""").isEmpty())
    }
}
