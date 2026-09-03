package dev.hansw.catchmyride.auth

import dev.hansw.catchmyride.api.ApiException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** API.md §8 — 인가 코드/refresh 교환 계약. */
class AuthControllerTest {

    private class FakeToss(override val enabled: Boolean = true) : TossAuth {
        override fun generateToken(authorizationCode: String, referrer: String): TossTokens {
            assertEquals("code-1", authorizationCode)
            return TossTokens("at-1", "rt-1", 3599)
        }

        override fun refreshToken(refreshToken: String): TossTokens {
            assertEquals("rt-1", refreshToken)
            return TossTokens("at-2", "rt-2", 3599)
        }

        override fun fetchUserKey(accessToken: String): String = error("사용 안 함")
    }

    @Test
    fun `로그인 - 인가 코드를 토큰으로 교환해 반환한다`() {
        val response = AuthController(FakeToss())
            .login(AuthController.LoginRequest(authorizationCode = "code-1", referrer = "DEFAULT"))
        assertEquals("at-1", response.accessToken)
        assertEquals("rt-1", response.refreshToken)
        assertEquals(3599, response.expiresInSeconds)
    }

    @Test
    fun `로그인 - referrer는 SANDBOX도 허용, 그 외 값은 400`() {
        val controller = AuthController(FakeToss())
        controller.login(AuthController.LoginRequest("code-1", "SANDBOX"))
        val e = assertFailsWith<ApiException> { controller.login(AuthController.LoginRequest("code-1", "PROD")) }
        assertEquals("INVALID_REQUEST", e.code)
    }

    @Test
    fun `로그인 - 인가 코드 누락은 400`() {
        val e = assertFailsWith<ApiException> {
            AuthController(FakeToss()).login(AuthController.LoginRequest(null, "DEFAULT"))
        }
        assertEquals("INVALID_REQUEST", e.code)
    }

    @Test
    fun `refresh - 새 토큰 쌍을 반환한다`() {
        val response = AuthController(FakeToss()).refresh(AuthController.RefreshRequest("rt-1"))
        assertEquals("at-2", response.accessToken)
        assertEquals("rt-2", response.refreshToken)
    }

    @Test
    fun `토스 미설정이면 503 - 클라이언트는 익명 키 모드로 폴백`() {
        val controller = AuthController(FakeToss(enabled = false))
        val e = assertFailsWith<ApiException> { controller.login(AuthController.LoginRequest("code-1", "DEFAULT")) }
        assertEquals("UPSTREAM_UNAVAILABLE", e.code)
        assertFailsWith<ApiException> { controller.refresh(AuthController.RefreshRequest("rt-1")) }
    }

    @Test
    fun `mode - 토스 설정 여부를 그대로 알려준다`() {
        assertEquals(true, AuthController(FakeToss(enabled = true)).mode().tossLoginAvailable)
        assertEquals(false, AuthController(FakeToss(enabled = false)).mode().tossLoginAvailable)
    }
}
