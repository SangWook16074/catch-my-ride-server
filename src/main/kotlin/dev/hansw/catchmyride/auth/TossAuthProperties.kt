package dev.hansw.catchmyride.auth

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 토스 로그인 서버 연동 설정 (S-3 잔여 — API.md §8).
 * 토스 파트너 API(generate-token·refresh-token·login-me)는 mTLS 필수라서
 * 콘솔에서 발급받은 클라이언트 인증서(PKCS12)가 있어야 실동작한다.
 *
 * keystore-path가 비어 있으면 비활성 — 인증 엔드포인트(/api/v1/auth)는 503, 요청 인증은 기존 dev-user-key 폴백.
 * (push.api-key·ncp.maps와 같은 "env 넣으면 라이브 전환" 패턴)
 */
@ConfigurationProperties("auth.toss")
data class TossAuthProperties(
    val baseUrl: String = "https://apps-in-toss-api.toss.im",
    val keystorePath: String = "",
    val keystorePassword: String = "",
    /** login-me 결과(토큰→userKey) 캐시 TTL — 요청마다 토스를 부르지 않기 위한 것 */
    val userKeyCacheTtl: Duration = Duration.ofMinutes(10),
) {
    val enabled: Boolean get() = keystorePath.isNotBlank()
}
