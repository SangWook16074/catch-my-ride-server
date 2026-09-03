package dev.hansw.catchmyride.api

import dev.hansw.catchmyride.auth.TossAuth
import dev.hansw.catchmyride.auth.TossAuthProperties
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 요청의 유저 식별 (API.md 공통 인증, S-3).
 *
 * - `Bearer anon:{hash}`: 앱인토스 getAnonymousKey() 익명 키 — 사업자 등록 전 기본 식별자 (API.md §8-3).
 *   토스가 유저별로 고정 발급하는 불투명 해시라 서버 검증 API 없이 형식 검사만 하고 그대로 쓴다
 *   (위조하려면 타인의 해시 자체를 알아야 함 — MVP 수용 리스크, API.md §8-3 명시)
 * - 그 외 Bearer 토큰 + 토스 연동 on: login-me 검증 → userKey (TTL 캐시로 요청마다 토스를 부르지 않는다)
 * - 헤더 없음 / 토스 미설정: auth.dev-user-key 폴백 — 로컬 E2E용 (SERVER_FEEDBACK.md §3)
 * - 배포에서 유저 분리 강제: AUTH_DEV_USER_KEY 빈 값 → 헤더 없는 요청은 401
 */
@Component
class UserKeyResolver(
    private val toss: TossAuth,
    private val props: TossAuthProperties,
    @Value("\${auth.dev-user-key}") private val devUserKey: String,
    private val clock: Clock,
) {

    private data class CachedKey(val userKey: String, val expiresAt: Instant)

    private val cache = ConcurrentHashMap<String, CachedKey>()

    fun resolve(authorizationHeader: String?): String {
        val token = bearerToken(authorizationHeader)
        if (token != null && token.startsWith(ANON_PREFIX)) return anonUserKey(token)
        if (token != null && toss.enabled) return userKeyFor(token)
        if (devUserKey.isNotBlank()) return devUserKey
        throw ApiException.unauthorized()
    }

    /** 익명 키는 접두어를 유지한 채 userKey로 쓴다 — 미래의 토스 로그인 userKey(숫자)와 충돌 방지 */
    private fun anonUserKey(token: String): String {
        val hash = token.removePrefix(ANON_PREFIX)
        // 형식 방어: user_key VARCHAR(128) 안에 들어가고, 로그·SQL 파라미터에 안전한 문자만
        if (hash.length !in 8..100 || !hash.all { it.isLetterOrDigit() || it in "-_.=+/" }) {
            throw ApiException.unauthorized()
        }
        return token
    }

    private fun userKeyFor(token: String): String {
        val now = clock.instant()
        cache[token]?.let { if (it.expiresAt.isAfter(now)) return it.userKey else cache.remove(token) }
        val userKey = toss.fetchUserKey(token) // 실패(만료·위조)는 401로 던져지고 캐시에 남지 않는다
        if (cache.size >= MAX_CACHE_ENTRIES) cache.clear() // accessToken 수명이 1시간이라 단순 전체 비움으로 충분
        cache[token] = CachedKey(userKey, now.plus(props.userKeyCacheTtl))
        return userKey
    }

    private fun bearerToken(header: String?): String? {
        if (header == null) return null
        val token = header.removePrefix(BEARER_PREFIX).trim()
        return token.takeIf { header.startsWith(BEARER_PREFIX) && it.isNotEmpty() }
    }

    companion object {
        private const val BEARER_PREFIX = "Bearer "
        private const val ANON_PREFIX = "anon:"
        private const val MAX_CACHE_ENTRIES = 10_000
    }
}
