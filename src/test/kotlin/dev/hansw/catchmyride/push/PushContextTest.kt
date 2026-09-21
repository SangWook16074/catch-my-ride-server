package dev.hansw.catchmyride.push

import org.junit.jupiter.api.Test
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 템플릿 변수 채우기 규칙 — 본문이 `{{minute}}분 후 도착해요.` 꼴이므로
 * 값이 비면 문장이 깨진다. 실시간 정보 없음은 "몇 분 후"로 렌더링돼야 한다 (2026-09-10).
 */
class PushContextTest {

    private val client = AppsInTossPushClient(PushProperties())

    private fun decision(minutes: Int?) = PushDecision(
        stage = PushStage.REMIND,
        departureTime = LocalTime.of(8, 20),
        routeName = "9호선 급행",
        minutesToArrival = minutes,
    )

    @Test
    fun `실시간 분이 있으면 그대로 넣는다`() {
        assertEquals(mapOf("minute" to "9"), client.context(decision(9)))
    }

    @Test
    fun `실시간 정보가 없으면 '몇 ' 폴백 - 렌더링하면 '몇 분 후 도착해요'`() {
        assertEquals(mapOf("minute" to "몇 "), client.context(decision(null)))
    }

    // 발송 대상 헤더 (2026-09-21 앱인토스 경고 — 유효하지 않은 x-toss-user-key 호출 차단)

    @Test
    fun `익명 키는 접두사를 떼고 x-anon-key로 보낸다`() {
        assertEquals("x-anon-key" to "abcDEF123-_", client.targetHeader("anon:abcDEF123-_"))
    }

    @Test
    fun `login-me 숫자 userKey는 x-toss-user-key로 보낸다`() {
        assertEquals("x-toss-user-key" to "1234567890", client.targetHeader("1234567890"))
    }

    @Test
    fun `dev-user 같은 로컬 폴백 키는 발송 대상이 아니다 - 호출 자체를 막는다`() {
        assertNull(client.targetHeader("dev-user"))
        assertNull(client.targetHeader("push-test-retry"))
        assertNull(client.targetHeader(""))
        assertNull(client.targetHeader("anon:"))
    }
}
