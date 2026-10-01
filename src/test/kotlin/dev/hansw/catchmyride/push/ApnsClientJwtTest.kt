package dev.hansw.catchmyride.push

import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ApnsClient의 ES256 JWT는 별도 라이브러리 없이 손수 서명한다(API.md §9-5, v0.12) — DER 서명을
 * JOSE(raw r‖s) 포맷으로 직접 변환하는 코드라 바이트 하나만 틀려도 Apple이 조용히 거부한다.
 * 실제 Apple 키 없이도 검증 가능하게, 테스트에서 발급한 EC 키로 서명 → 공개키로 역검증한다.
 */
class ApnsClientJwtTest {

    @Test
    fun `ES256 JWT는 공개키로 검증 가능한 서명을 만든다`() {
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val pem = "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.private.encoded) +
            "\n-----END PRIVATE KEY-----"
        val props = ApnsProperties(keyId = "KEY123", teamId = "TEAM456", privateKey = pem, bundleId = "com.example.app")
        val client = ApnsClient(props)

        val jwt = client.signJwt(Instant.ofEpochSecond(1_700_000_000))
        val parts = jwt.split(".")
        assertEquals(3, parts.size, jwt)

        val header = String(Base64.getUrlDecoder().decode(parts[0]))
        val claims = String(Base64.getUrlDecoder().decode(parts[1]))
        assertTrue(header.contains("\"alg\":\"ES256\""), header)
        assertTrue(header.contains("KEY123"), header)
        assertTrue(claims.contains("TEAM456"), claims)

        val raw = Base64.getUrlDecoder().decode(parts[2])
        assertEquals(64, raw.size) // P-256 raw r‖s(각 32바이트) — JOSE ES256 규격

        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(keyPair.public)
        verifier.update("${parts[0]}.${parts[1]}".toByteArray())
        assertTrue(verifier.verify(joseToDer(raw)), "공개키로 서명을 검증하지 못했다 — DER↔JOSE 변환 버그 의심")
    }

    /** 테스트 전용 역변환(JOSE raw r‖s → DER) — java.security.Signature.verify는 DER을 요구한다 */
    private fun joseToDer(raw: ByteArray): ByteArray {
        val r = raw.copyOfRange(0, 32)
        val s = raw.copyOfRange(32, 64)
        fun encodeInt(component: ByteArray): ByteArray {
            var bytes = component.dropWhile { it == 0.toByte() }.toByteArray()
            if (bytes.isEmpty()) {
                bytes = byteArrayOf(0)
            }
            if (bytes[0].toInt() and 0x80 != 0) {
                bytes = byteArrayOf(0) + bytes
            }
            return byteArrayOf(0x02, bytes.size.toByte()) + bytes
        }
        val body = encodeInt(r) + encodeInt(s)
        return byteArrayOf(0x30, body.size.toByte()) + body
    }
}
