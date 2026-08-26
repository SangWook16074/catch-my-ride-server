package dev.hansw.catchmyride.spike.adapter

import org.junit.jupiter.api.Test
import tools.jackson.dataformat.xml.XmlMapper
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * TOPIS XML 파싱 헬퍼 검증 — itemList 1건/여러 건, 빈 필드 처리.
 * Jackson 3 마이그레이션(asText → asString) 시 동작이 유지되는지 고정한다.
 */
class JsonNodeHelpersTest {

    private val xmlMapper = XmlMapper.builder().build()

    @Test
    fun `itemList가 여러 건이면 각각 리스트 원소가 된다`() {
        val root = xmlMapper.readTree(
            """
            <msgBody>
                <itemList><rtNm>720</rtNm></itemList>
                <itemList><rtNm>271</rtNm></itemList>
            </msgBody>
            """.trimIndent()
        )
        val items = root.path("itemList").asItemList()
        assertEquals(listOf("720", "271"), items.map { it.textOrNull("rtNm") })
    }

    @Test
    fun `itemList가 1건이면 단일 객체를 리스트로 감싼다`() {
        val root = xmlMapper.readTree("<msgBody><itemList><rtNm>720</rtNm></itemList></msgBody>")
        val items = root.path("itemList").asItemList()
        assertEquals(1, items.size)
        assertEquals("720", items[0].textOrNull("rtNm"))
    }

    @Test
    fun `itemList가 없으면 빈 리스트다`() {
        val root = xmlMapper.readTree("<msgBody></msgBody>")
        assertEquals(emptyList(), root.path("itemList").asItemList())
    }

    @Test
    fun `없는 필드나 빈 필드는 null을 돌려준다`() {
        val root = xmlMapper.readTree("<item><rtNm></rtNm></item>")
        assertNull(root.textOrNull("rtNm"), "빈 필드는 null이어야 함")
        assertNull(root.textOrNull("noSuchField"), "없는 필드는 null이어야 함")
    }
}
