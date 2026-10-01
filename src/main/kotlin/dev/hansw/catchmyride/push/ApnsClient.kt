package dev.hansw.catchmyride.push

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64

/** APNs가 죽은 활동 push token을 알렸을 때(410/BadDeviceToken) 던진다 — 호출부가 토큰을 폐기한다 */
class ApnsTokenInvalidException : RuntimeException()

/**
 * API.md §9-5 (v0.12) — Live Activity 원격 갱신 직접 발송 (ActivityKit push notifications).
 * FCM을 거치지 않는다 — Apple 문서의 토큰 기반(JWT, ES256) 인증으로 api.push.apple.com에 HTTP/2로 직접 보낸다.
 *
 * JWT는 ~1시간 유효해 50분 캐시(수명 안에서 재사용, 과도한 서명 비용을 피한다).
 * 서명은 별도 JWT 라이브러리 없이 java.security(EC/SHA256withECDSA)로 직접 만든다 — DER 서명을
 * JOSE(raw r‖s) 포맷으로 변환해야 한다(ES256 JWS 규격, DER과 다르다).
 *
 * env(APNS_KEY_ID 등)가 비어 있으면 dry-run — 코드베이스 공통 강등 규칙(FcmPushClient와 동일).
 */
@Component
class ApnsClient(private val props: ApnsProperties) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = JsonMapper()
    private val httpClient: HttpClient by lazy { HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build() }

    @Volatile
    private var cachedJwt: Pair<String, Instant>? = null

    val live: Boolean get() = props.live

    /** `aps.event: "update"` — 표면 값이 바뀔 때마다(역 이동 포함 우선순위 10) */
    fun sendUpdate(activityToken: String, contentState: Map<String, Any?>, now: Instant): Boolean =
        send(activityToken, event = "update", contentState = contentState, now = now)

    /** `aps.event: "end"` + dismissal-date(+5분) — DONE·트립 삭제 */
    fun sendEnd(activityToken: String, contentState: Map<String, Any?>, now: Instant): Boolean =
        send(activityToken, event = "end", contentState = contentState, now = now)

    private fun send(activityToken: String, event: String, contentState: Map<String, Any?>, now: Instant): Boolean {
        if (!live) {
            log.info("[dry-run] APNs 미발송 — event={} token={}...", event, activityToken.take(8))
            return false
        }
        val aps = linkedMapOf<String, Any?>(
            "timestamp" to now.epochSecond,
            "event" to event,
            "content-state" to contentState,
        )
        if (event == "end") {
            aps["dismissal-date"] = now.plusSeconds(END_DISMISSAL_SECONDS).epochSecond
        } else {
            aps["stale-date"] = now.plusSeconds(UPDATE_STALE_SECONDS).epochSecond
        }
        val payload = mapper.writeValueAsString(mapOf("aps" to aps))
        val host = if (props.sandbox) SANDBOX_HOST else PRODUCTION_HOST
        val request = HttpRequest.newBuilder(URI.create("https://$host/3/device/$activityToken"))
            .header("apns-push-type", "liveactivity")
            .header("apns-topic", "${props.bundleId}.push-type.liveactivity")
            .header("apns-priority", "10") // 역 이동도 즉시 — 5는 iOS가 몰아서 늦게 보여줄 수 있다 (오너 요구)
            .header("authorization", "bearer ${currentJwt()}")
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()
        return try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            when {
                response.statusCode() in 200..299 -> true
                response.statusCode() == 410 || response.body().contains("BadDeviceToken") -> {
                    log.warn("APNs 토큰 폐기 대상 — status={} body={}", response.statusCode(), response.body())
                    throw ApnsTokenInvalidException()
                }
                else -> {
                    log.warn("APNs 발송 실패 — status={} body={}", response.statusCode(), response.body())
                    false
                }
            }
        } catch (e: ApnsTokenInvalidException) {
            throw e
        } catch (e: Exception) {
            log.warn("APNs 발송 예외 — {}", e.message)
            false
        }
    }

    private fun currentJwt(): String {
        val now = Instant.now()
        cachedJwt?.let { (jwt, expiresAt) -> if (now.isBefore(expiresAt)) return jwt }
        val jwt = signJwt(now)
        cachedJwt = jwt to now.plus(JWT_CACHE_DURATION)
        return jwt
    }

    /** internal — ApnsClientJwtTest가 서명이 공개키로 검증 가능한지 직접 확인한다(손수 짠 ES256 서명이라 회귀 위험이 크다) */
    internal fun signJwt(now: Instant): String {
        val header = """{"alg":"ES256","kid":"${props.keyId}"}"""
        val claims = """{"iss":"${props.teamId}","iat":${now.epochSecond}}"""
        val signingInput = "${base64Url(header.toByteArray())}.${base64Url(claims.toByteArray())}"
        val signature = signEs256(signingInput.toByteArray())
        return "$signingInput.${base64Url(signature)}"
    }

    private fun signEs256(data: ByteArray): ByteArray {
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey())
        signature.update(data)
        return derToJose(signature.sign())
    }

    private fun privateKey(): PrivateKey {
        val base64 = props.privateKey
            .replace("\\n", "\n")
            .lines()
            .filterNot { it.startsWith("-----") }
            .joinToString("")
            .trim()
        val keyBytes = Base64.getDecoder().decode(base64)
        return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
    }

    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /**
     * ECDSA 서명은 기본적으로 DER(ASN.1 SEQUENCE of two INTEGERs)로 나오는데, JWS(ES256)는
     * 고정 길이 raw r‖s(각 32바이트, P-256 기준)를 요구한다 — 변환하지 않으면 Apple이 서명을 거부한다.
     */
    private fun derToJose(der: ByteArray, componentLength: Int = 32): ByteArray {
        var offset = 0
        require(der[offset].toInt() == 0x30) { "ECDSA DER 서명이 아닙니다" }
        offset++
        offset += derLengthSize(der, offset)
        require(der[offset].toInt() == 0x02) { "r 정수가 없습니다" }
        offset++
        val (rLen, rLenSize) = derLength(der, offset)
        offset += rLenSize
        val r = der.copyOfRange(offset, offset + rLen)
        offset += rLen
        require(der[offset].toInt() == 0x02) { "s 정수가 없습니다" }
        offset++
        val (sLen, sLenSize) = derLength(der, offset)
        offset += sLenSize
        val s = der.copyOfRange(offset, offset + sLen)
        return fixedLength(r, componentLength) + fixedLength(s, componentLength)
    }

    private fun derLengthSize(der: ByteArray, offset: Int): Int = derLength(der, offset).second

    /** @return (길이값, 길이를 표현하는 데 쓴 바이트 수) */
    private fun derLength(der: ByteArray, offset: Int): Pair<Int, Int> {
        val first = der[offset].toInt() and 0xFF
        return if (first and 0x80 == 0) {
            first to 1
        } else {
            val numBytes = first and 0x7F
            var value = 0
            for (i in 1..numBytes) value = (value shl 8) or (der[offset + i].toInt() and 0xFF)
            value to (numBytes + 1)
        }
    }

    private fun fixedLength(value: ByteArray, length: Int): ByteArray {
        val trimmed = value.dropWhile { it == 0.toByte() }.toByteArray()
        return when {
            trimmed.size == length -> trimmed
            trimmed.size < length -> ByteArray(length - trimmed.size) + trimmed
            else -> trimmed.copyOfRange(trimmed.size - length, trimmed.size)
        }
    }

    companion object {
        private const val PRODUCTION_HOST = "api.push.apple.com"
        private const val SANDBOX_HOST = "api.sandbox.push.apple.com"
        private const val UPDATE_STALE_SECONDS = 180L // +3분 (API.md §9-5)
        private const val END_DISMISSAL_SECONDS = 300L // +5분 — v0.11 되돌리기 창과 맞춤
        private val JWT_CACHE_DURATION: Duration = Duration.ofMinutes(50) // APNs JWT는 ~1시간 유효
    }
}
