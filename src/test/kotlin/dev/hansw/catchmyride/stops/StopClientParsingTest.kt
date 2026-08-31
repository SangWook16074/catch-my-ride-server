package dev.hansw.catchmyride.stops

import dev.hansw.catchmyride.spike.SpikeProperties
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 상류 응답 파싱 검증 — 픽스처는 실제 API 응답 형태를 그대로 축약한 것.
 * (서울: 2026-08-28 실호출 형식 / GBIS 정류소 조회: 문서 기준 — 활용신청 승인 후 실측 재검증 대상)
 */
class StopClientParsingTest {

    private val props = SpikeProperties(
        polling = SpikeProperties.Polling(Duration.ofSeconds(30), "06:30", "09:30", true),
        logDir = "./spike-logs",
        keys = SpikeProperties.Keys(dataGoKr = "", seoulOpenData = ""),
        targets = SpikeProperties.Targets(emptyList(), emptyList(), emptyList()),
    )
    private val seoul = SeoulBusStopClient(props)
    private val gbis = GbisStopClient(props, JsonMapper.builder().build())

    @Test
    fun `서울 정류소명 검색 XML을 파싱한다`() {
        val body = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <ServiceResult><comMsgHeader/><msgHeader><headerCd>0</headerCd></msgHeader><msgBody>
            <itemList><stId>121000214</stId><stNm>여의도환승센터</stNm><arsId>19284</arsId></itemList>
            <itemList><stId>121000215</stId><stNm>여의도역</stNm><arsId>0</arsId></itemList>
            </msgBody></ServiceResult>
        """.trimIndent()

        val results = seoul.parseSearch(body, 10)

        assertEquals(1, results.size, "arsId 0(가상 정류장)은 제외되어야 함")
        assertEquals(StopSearchResult(StopType.SEOUL_BUS, "19284", "여의도환승센터", "서울 · 19284"), results[0])
    }

    @Test
    fun `서울 정류소 경유 노선 XML을 중복 없이 파싱한다`() {
        val body = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <ServiceResult><msgBody>
            <itemList><rtNm>720</rtNm><arrmsg1>3분후[2번째 전]</arrmsg1></itemList>
            <itemList><rtNm>261</rtNm></itemList>
            <itemList><rtNm>720</rtNm></itemList>
            </msgBody></ServiceResult>
        """.trimIndent()

        val routes = seoul.parseRoutes(body)

        assertEquals(listOf("720", "261"), routes.map { it.name })
    }

    @Test
    fun `GBIS 정류소 검색 JSON을 파싱한다`() {
        val body = """
            {"response":{"msgHeader":{"resultCode":0},"msgBody":{"busStationList":[
              {"stationId":228000723,"stationName":"수내역","regionName":"성남시","mobileNo":" 07348"},
              {"stationId":228000724,"stationName":"수내동","regionName":"성남시","mobileNo":""}
            ]}}}
        """.trimIndent()

        val results = gbis.parseSearch(body, 10)

        assertEquals(2, results.size)
        assertEquals(StopSearchResult(StopType.GYEONGGI_BUS, "228000723", "수내역", "성남시 · 07348"), results[0])
        assertEquals("성남시", results[1].subtitle, "빈 mobileNo는 subtitle에서 빠져야 함")
    }

    @Test
    fun `GBIS 경유 노선 JSON을 파싱한다`() {
        val body = """
            {"response":{"msgBody":{"busRouteList":[
              {"routeId":233000374,"routeName":"P9602"},{"routeId":233000031,"routeName":"9401"}
            ]}}}
        """.trimIndent()

        val routes = gbis.parseRoutes(body)

        assertEquals(listOf("P9602", "9401"), routes.map { it.name })
    }

    @Test
    fun `빈 응답이나 결과 없음은 빈 목록이다`() {
        assertTrue(seoul.parseSearch("<ServiceResult><msgBody/></ServiceResult>", 10).isEmpty())
        assertTrue(gbis.parseSearch("""{"response":{"msgBody":{}}}""", 10).isEmpty())
    }
}
