package dev.hansw.catchmyride.api

import dev.hansw.catchmyride.auth.TossAuth
import dev.hansw.catchmyride.auth.TossAuthProperties
import dev.hansw.catchmyride.auth.TossTokens
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 유저 식별 규칙 (S-3):
 * - 토큰 + 토스 연동 on → login-me 검증 결과 userKey, TTL 캐시
 * - 헤더 없음 / 토스 off → dev-user-key 폴백, dev-user-key도 비면 401
 */
class UserKeyResolverTest {

    private class FakeToss(
        override val enabled: Boolean = true,
        private val userKeys: Map<String, String> = emptyMap(),
    ) : TossAuth {
        var lookups = 0
        override fun generateToken(authorizationCode: String, referrer: String): TossTokens = error("사용 안 함")
        override fun refreshToken(refreshToken: String): TossTokens = error("사용 안 함")
        override fun fetchUserKey(accessToken: String): String {
            lookups++
            return userKeys[accessToken] ?: throw ApiException.unauthorized()
        }
    }

    private class MutableClock(var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId): Clock = this
    }

    private val props = TossAuthProperties(keystorePath = "unused", userKeyCacheTtl = Duration.ofMinutes(10))
    private val clock = MutableClock(Instant.parse("2026-09-03T00:00:00Z"))

    private fun resolver(toss: TossAuth, devUserKey: String = "dev-user") =
        UserKeyResolver(toss, props, devUserKey, clock)

    @Test
    fun `Bearer 토큰이 있으면 토스 검증 결과 userKey를 쓴다`() {
        val toss = FakeToss(userKeys = mapOf("token-a" to "1001", "token-b" to "1002"))
        val resolver = resolver(toss)
        assertEquals("1001", resolver.resolve("Bearer token-a"))
        assertEquals("1002", resolver.resolve("Bearer token-b")) // 유저마다 다른 키 — 덮어쓰기 버그의 회귀 방지
    }

    @Test
    fun `같은 토큰은 TTL 안에서 캐시돼 토스를 한 번만 부른다`() {
        val toss = FakeToss(userKeys = mapOf("token-a" to "1001"))
        val resolver = resolver(toss)
        repeat(3) { resolver.resolve("Bearer token-a") }
        assertEquals(1, toss.lookups)

        clock.now = clock.now.plus(Duration.ofMinutes(11)) // TTL 경과 → 재검증
        resolver.resolve("Bearer token-a")
        assertEquals(2, toss.lookups)
    }

    @Test
    fun `위조·만료 토큰은 401이고 캐시에 남지 않는다`() {
        val toss = FakeToss()
        val resolver = resolver(toss)
        assertFailsWith<ApiException> { resolver.resolve("Bearer bad") }
        assertFailsWith<ApiException> { resolver.resolve("Bearer bad") }
        assertEquals(2, toss.lookups)
    }

    @Test
    fun `헤더가 없으면 dev-user-key 폴백`() {
        assertEquals("dev-user", resolver(FakeToss()).resolve(null))
    }

    @Test
    fun `토스 미설정이면 토큰이 와도 dev-user-key 폴백 - 로컬 E2E 보존`() {
        val resolver = resolver(FakeToss(enabled = false))
        assertEquals("dev-user", resolver.resolve("Bearer whatever"))
    }

    @Test
    fun `dev-user-key가 비어 있으면 헤더 없는 요청은 401 - 배포 강제`() {
        val e = assertFailsWith<ApiException> { resolver(FakeToss(), devUserKey = "").resolve(null) }
        assertEquals("UNAUTHORIZED", e.code)
    }

    @Test
    fun `익명 키 토큰은 토스 검증 없이 그대로 userKey가 된다 - 사업자 등록 전 기본 경로`() {
        val toss = FakeToss()
        val resolver = resolver(toss)
        assertEquals("anon:a1b2c3d4e5", resolver.resolve("Bearer anon:a1b2c3d4e5"))
        assertEquals("anon:f6g7h8i9j0", resolver.resolve("Bearer anon:f6g7h8i9j0")) // 유저마다 다른 키
        assertEquals(0, toss.lookups)
    }

    @Test
    fun `익명 키는 토스 연동이 꺼져 있어도 동작한다`() {
        val resolver = resolver(FakeToss(enabled = false), devUserKey = "")
        assertEquals("anon:a1b2c3d4e5", resolver.resolve("Bearer anon:a1b2c3d4e5"))
    }

    @Test
    fun `형식이 어긋난 익명 키는 401 - 너무 짧거나 허용 밖 문자`() {
        val resolver = resolver(FakeToss())
        assertFailsWith<ApiException> { resolver.resolve("Bearer anon:short") } // 8자 미만
        assertFailsWith<ApiException> { resolver.resolve("Bearer anon:${"x".repeat(101)}") } // 100자 초과
        assertFailsWith<ApiException> { resolver.resolve("Bearer anon:has space!") }
    }

    @Test
    fun `Bearer 형식이 아니거나 빈 토큰이면 토큰 없음으로 취급한다`() {
        val toss = FakeToss()
        val resolver = resolver(toss)
        assertEquals("dev-user", resolver.resolve("Basic abc"))
        assertEquals("dev-user", resolver.resolve("Bearer "))
        assertEquals(0, toss.lookups)
    }
}
