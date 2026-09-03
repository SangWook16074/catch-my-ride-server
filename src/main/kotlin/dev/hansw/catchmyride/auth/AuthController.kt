package dev.hansw.catchmyride.auth

import dev.hansw.catchmyride.api.ApiException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * API.md §8 — 토스 로그인 교환 엔드포인트 (인증 불필요 — 로그인 전에 호출된다).
 *
 * 클라이언트 appLogin()의 인가 코드를 받아 토스 generate-token으로 교환해 돌려준다.
 * 이후 클라이언트는 accessToken을 모든 요청의 Bearer로 보내고(API.md 공통),
 * 만료(1시간) 전후로 /refresh를 호출한다 (refreshToken 유효 14일).
 *
 * mTLS 인증서 미설정(auth.toss.keystore-path 빈 값) 동안은 503 UPSTREAM_UNAVAILABLE —
 * 클라이언트는 로그인 불가로 보고 헤더 없이 동작한다(dev-user 폴백, SERVER_FEEDBACK.md §3).
 */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController(private val toss: TossAuth) {

    data class LoginRequest(val authorizationCode: String?, val referrer: String?)
    data class RefreshRequest(val refreshToken: String?)
    data class TokenResponse(val accessToken: String, val refreshToken: String, val expiresInSeconds: Long)
    data class ModeResponse(val tossLoginAvailable: Boolean)

    /**
     * §8-0 — 클라이언트가 세션 시작 시 인증 방식을 고르는 기준.
     * false(사업자 등록·mTLS 인증서 전)면 클라이언트는 appLogin 대신 getAnonymousKey()로
     * `Bearer anon:{hash}`를 보낸다. 인증서가 설정되면 자동으로 true → 토스 로그인 전환.
     */
    @GetMapping("/mode")
    fun mode(): ModeResponse = ModeResponse(tossLoginAvailable = toss.enabled)

    @PostMapping("/login")
    fun login(@RequestBody request: LoginRequest): TokenResponse {
        requireLive()
        val code = request.authorizationCode?.takeIf { it.isNotBlank() }
            ?: throw ApiException.invalidRequest("authorizationCode는 필수입니다")
        val referrer = request.referrer?.takeIf { it == "DEFAULT" || it == "SANDBOX" }
            ?: throw ApiException.invalidRequest("referrer는 DEFAULT 또는 SANDBOX여야 합니다")
        return toss.generateToken(code, referrer).toResponse()
    }

    @PostMapping("/refresh")
    fun refresh(@RequestBody request: RefreshRequest): TokenResponse {
        requireLive()
        val refreshToken = request.refreshToken?.takeIf { it.isNotBlank() }
            ?: throw ApiException.invalidRequest("refreshToken은 필수입니다")
        return toss.refreshToken(refreshToken).toResponse()
    }

    private fun requireLive() {
        if (!toss.enabled) {
            throw ApiException.upstreamUnavailable("토스 로그인이 아직 설정되지 않았습니다 (mTLS 인증서 대기)")
        }
    }

    private fun TossTokens.toResponse() = TokenResponse(accessToken, refreshToken, expiresInSeconds)
}
