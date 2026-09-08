package dev.hansw.catchmyride.auth

import java.io.FileInputStream
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * 앱인토스 파트너 API 공용 mTLS 컨텍스트 — 토스 로그인(TossOAuthClient)과 푸시 발송(AppsInTossPushClient)이
 * 같은 콘솔 발급 인증서(PKCS12)를 쓴다. (PEM → PKCS12: openssl pkcs12 -export -in cert -inkey key -out toss-mtls.p12)
 */
fun mtlsSslContext(keystorePath: String, keystorePassword: String): SSLContext {
    val password = keystorePassword.toCharArray()
    val keyStore = KeyStore.getInstance("PKCS12").apply {
        FileInputStream(keystorePath).use { load(it, password) }
    }
    val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        .apply { init(keyStore, password) }
        .keyManagers
    return SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }
}
