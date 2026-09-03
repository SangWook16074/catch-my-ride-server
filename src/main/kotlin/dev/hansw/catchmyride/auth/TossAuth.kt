package dev.hansw.catchmyride.auth

/** 토스 토큰 발급 결과 — expiresIn은 문서상 문자열("3599")이라 초 단위 Long으로 정규화해 내려준다. */
data class TossTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
)

/**
 * 토스 로그인 파트너 API 추상화 — 실구현은 TossOAuthClient(mTLS).
 * UserKeyResolver·AuthController가 이 인터페이스만 보게 해서 테스트에서 페이크로 대체한다.
 */
interface TossAuth {
    /** mTLS 인증서가 설정돼 실호출 가능한 상태인지 */
    val enabled: Boolean

    /** appLogin 인가 코드 → 토큰 발급. referrer는 SDK가 준 값("DEFAULT"/"SANDBOX") 그대로 전달 */
    fun generateToken(authorizationCode: String, referrer: String): TossTokens

    fun refreshToken(refreshToken: String): TossTokens

    /** accessToken 검증 + userKey 조회 (login-me). 만료/위조 토큰이면 ApiException.unauthorized */
    fun fetchUserKey(accessToken: String): String
}
