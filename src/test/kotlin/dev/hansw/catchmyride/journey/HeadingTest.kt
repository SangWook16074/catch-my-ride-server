package dev.hansw.catchmyride.journey

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 방면 판정(Heading.kt) 명세 — 2026-09-21 실측 응답 기준:
 * 3호선·1호선은 하행이 id 증가, 9호선은 상행이 증가, 2호선 본선은 순환(201~243, 내선이 증가).
 * 유저에게 상행/하행을 묻지 않는다 — 초행길 유저가 대상 (오너 결정 2026-09-21)
 */
class HeadingTest {

    private fun row(line: String, id: Long, heading: Heading, prev: Long, next: Long) = ApproachingTrain(
        "1", line = line, isExpress = false, arvlCd = "99", message = null, secondsToArrival = null,
        heading = heading, stationId = id, prevStationId = prev, nextStationId = next,
    )

    @Test
    fun `3호선 충무로→교대는 하행, 교대→충무로는 상행`() {
        // 교대(340) 전광판: 상행 341→339, 하행 339→341 (실측)
        val rows = listOf(
            row("3호선", 1003000340, Heading.UP, 1003000341, 1003000339),
            row("3호선", 1003000340, Heading.DOWN, 1003000339, 1003000341),
        )
        assertEquals(Heading.DOWN, resolveHeading("3호선", 1003000331, 1003000340, rows))
        assertEquals(Heading.UP, resolveHeading("3호선", 1003000340, 1003000331, rows))
    }

    @Test
    fun `9호선은 상행이 id 증가 — 관례가 아니라 전광판 필드로 판정한다`() {
        // 당산(913) 전광판: 상행 912→914, 하행 914→912 (실측)
        val rows = listOf(
            row("9호선", 1009000913, Heading.UP, 1009000912, 1009000914),
            row("9호선", 1009000913, Heading.DOWN, 1009000914, 1009000912),
        )
        assertEquals(Heading.UP, resolveHeading("9호선 급행", 1009000913, 1009000915, rows))
        assertEquals(Heading.DOWN, resolveHeading("9호선 급행", 1009000915, 1009000913, rows))
    }

    @Test
    fun `2호선 본선은 순환 — 시청↔충정로 이음새를 짧은 쪽으로 넘는다`() {
        // 시청(201) 전광판: 외선 202→243, 내선 243→202 (실측)
        val rows = listOf(
            row("2호선", 1002000201, Heading.DOWN, 1002000202, 1002000243),
            row("2호선", 1002000201, Heading.UP, 1002000243, 1002000202),
        )
        assertEquals(Heading.DOWN, resolveHeading("2호선", 1002000201, 1002000243, rows)) // 시청→충정로 = 외선
        assertEquals(Heading.UP, resolveHeading("2호선", 1002000243, 1002000201, rows))   // 충정로→시청 = 내선
        assertEquals(Heading.UP, resolveHeading("2호선", 1002000201, 1002000210, rows))   // 시청→뚝섬 = 내선
    }

    @Test
    fun `판정 불가는 null — 같은 역, 본선·지선 조합, 방면 필드 없음, 다른 노선 행`() {
        val rows = listOf(row("3호선", 1003000340, Heading.DOWN, 1003000339, 1003000341))
        assertNull(resolveHeading("3호선", 1003000340, 1003000340, rows))
        assertNull(resolveHeading("2호선", 1002000201, 1002002111, rows))
        assertNull(resolveHeading("3호선", 1003000331, 1003000340, emptyList()))
        assertNull(resolveHeading("4호선", 1004000413, 1004000423, rows)) // 3호선 행으로 4호선을 판정하지 않는다
        // 하행 행만 있는데 필요한 방면이 상행이면 null (아는 척 금지)
        assertNull(resolveHeading("3호선", 1003000340, 1003000331, rows))
    }

    @Test
    fun `updnLine 정규화 — 전광판 문자열과 노선 위치 0·1`() {
        assertEquals(Heading.UP, headingOf("상행"))
        assertEquals(Heading.UP, headingOf("내선"))
        assertEquals(Heading.UP, headingOf("0"))
        assertEquals(Heading.DOWN, headingOf("하행"))
        assertEquals(Heading.DOWN, headingOf("외선"))
        assertEquals(Heading.DOWN, headingOf("1"))
        assertNull(headingOf(null))
        assertNull(headingOf("?"))
    }
}
