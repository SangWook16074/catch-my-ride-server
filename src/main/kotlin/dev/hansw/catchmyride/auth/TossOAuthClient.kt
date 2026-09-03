package dev.hansw.catchmyride.auth

import dev.hansw.catchmyride.api.ApiException
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.io.FileInputStream
import java.net.http.HttpClient
import java.security.KeyStore
import java.time.Duration
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * 토스 로그인 파트너 API 클라이언트 (developers-apps-in-toss.toss.im › 토스 로그인).
 *
 * - POST /api-partner/v1/apps-in-toss/user/oauth2/generate-token  {authorizationCode, referrer}
 * - POST /api-partner/v1/apps-in-toss/user/oauth2/refresh-token   {refreshToken}
 * - GET  /api-partner/v1/apps-in-toss/user/oauth2/login-me        Bearer accessToken → userKey
 *
 * 서버 간 통신은 mTLS 필수 — 콘솔에서 받은 인증서를 PKCS12로 변환해 keystore-path에 두면 활성화된다.
 * (PEM(cert+key) → PKCS12: openssl pkcs12 -export -in cert.pem -inkey key.pem -out toss-mtls.p12)
 *
 * 응답 껍데기가 문서 표(평면 필드)와 실서비스({resultType, success:{...}}) 두 형태로 보고돼
 * 둘 다 받는다 — 실인증서 발급 후 첫 실호출에서 로그로 확정할 것.
 */
@Component
class TossOAuthClient(private val props: TossAuthProperties) : TossAuth {

    private val log = LoggerFactory.getLogger(javaClass)

    override val enabled: Boolean get() = props.enabled

    // 인증서가 없는 로컬 기동에서 SSLContext 초기화가 부팅을 깨지 않도록 지연 생성
    private val rest: RestClient by lazy {
        val http = HttpClient.newBuilder()
            .sslContext(mtlsContext())
            .connectTimeout(Duration.ofSeconds(5))
            .build()
        RestClient.builder()
            .baseUrl(props.baseUrl)
            .requestFactory(JdkClientHttpRequestFactory(http).apply { setReadTimeout(Duration.ofSeconds(10)) })
            .build()
    }

    override fun generateToken(authorizationCode: String, referrer: String): TossTokens =
        tokens(
            post(
                "/api-partner/v1/apps-in-toss/user/oauth2/generate-token",
                mapOf("authorizationCode" to authorizationCode, "referrer" to referrer),
            ),
        )

    override fun refreshToken(refreshToken: String): TossTokens =
        tokens(post("/api-partner/v1/apps-in-toss/user/oauth2/refresh-token", mapOf("refreshToken" to refreshToken)))

    override fun fetchUserKey(accessToken: String): String {
        val body = call {
            rest.get()
                .uri("/api-partner/v1/apps-in-toss/user/oauth2/login-me")
                .header("Authorization", "Bearer $accessToken")
                .retrieve()
                .body(Map::class.java)
        }
        // userKey는 문서상 number — 저장 키는 문자열로 통일 (commute_setting.user_key)
        return unwrap(body)["userKey"]?.toString() ?: throw ApiException.unauthorized()
    }

    private fun post(uri: String, body: Map<String, String>): Map<*, *>? = call {
        rest.post()
            .uri(uri)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(Map::class.java)
    }

    /**
     * 토스 쪽 4xx(invalid_grant·만료 등)는 전부 클라이언트 재로그인 사안 → 401.
     * 그 외(5xx·네트워크·인증서 문제)는 503 — 클라이언트는 헤더 생략/재시도로 강등한다.
     */
    private fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: RestClientResponseException) {
        if (e.statusCode.is4xxClientError) {
            log.info("토스 인증 거절 — status={} body={}", e.statusCode.value(), e.responseBodyAsString.take(200))
            throw ApiException.unauthorized()
        }
        log.warn("토스 인증 API 오류 — status={}: {}", e.statusCode.value(), e.message)
        throw ApiException.upstreamUnavailable("토스 인증 서버 오류")
    } catch (e: ResourceAccessException) {
        log.warn("토스 인증 API 접속 실패: {}", e.message)
        throw ApiException.upstreamUnavailable("토스 인증 서버에 연결할 수 없습니다")
    }

    private fun tokens(body: Map<*, *>?): TossTokens {
        val fields = unwrap(body)
        val accessToken = fields["accessToken"] as? String
        val refreshToken = fields["refreshToken"] as? String
        // expiresIn은 문서상 문자열("3599") — 숫자로 와도 받는다
        val expiresIn = when (val raw = fields["expiresIn"]) {
            is String -> raw.toLongOrNull()
            is Number -> raw.toLong()
            else -> null
        }
        if (accessToken == null || refreshToken == null || expiresIn == null) {
            log.warn("토스 토큰 응답 형식 불일치 — keys={}", fields.keys)
            throw ApiException.upstreamUnavailable("토스 인증 응답을 해석할 수 없습니다")
        }
        return TossTokens(accessToken, refreshToken, expiresIn)
    }

    /** {resultType, success:{...}} 껍데기면 success 내부를, 아니면 평면 그대로 */
    private fun unwrap(body: Map<*, *>?): Map<*, *> {
        if (body == null) throw ApiException.upstreamUnavailable("토스 인증 응답이 비어 있습니다")
        return (body["success"] as? Map<*, *>) ?: body
    }

    private fun mtlsContext(): SSLContext {
        val password = props.keystorePassword.toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            FileInputStream(props.keystorePath).use { load(it, password) }
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore, password) }
            .keyManagers
        return SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }
    }
}
