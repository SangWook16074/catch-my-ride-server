package dev.hansw.catchmyride.push

import dev.hansw.catchmyride.auth.mtlsSslContext
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.net.http.HttpClient
import java.time.Duration

/**
 * 토스가 요청 자체를 거부한 발송 — 재시도해도 결과가 같은 규격 오류(HTTP 4xx, resultType=FAIL).
 * 스케줄러는 이 예외를 롤백하지 않고 미전달로 남겨 같은 스테이지를 다시 부르지 않는다 (2026-09-21 앱인토스 경고).
 */
class PushRejectedException(message: String) : RuntimeException(message)

/**
 * S-6 앱인토스 send-message 클라이언트 (docs/push-templates.md).
 * POST /api-partner/v1/apps-in-toss/messenger/send-message — templateSetCode + context 변수 주입.
 *
 * 인증(2026-09-08 스펙 확정): mTLS(TLS 연결 단계, 콘솔 발급 인증서) + 발송 대상 헤더 택 1 —
 * 익명 유저는 `x-anon-key`(getAnonymousKey 해시), 토스 로그인 유저는 `x-toss-user-key`.
 * 우리 userKey가 `anon:{hash}` 형태면 접두사를 떼고 x-anon-key로 보낸다 (API.md §8-3).
 * 그 외는 login-me가 준 숫자 userKey여야만 x-toss-user-key로 보낸다 — `dev-user` 같은 로컬 폴백 키는
 * 토스가 "유효하지 않은 userKey"로 거부하므로 호출 자체를 하지 않는다 (2026-09-21 앱인토스 경고 메일).
 *
 * keystore 또는 해당 스테이지 템플릿 코드가 비어 있으면 dry-run(로그만 남기고 미발송).
 * 실패 분류 (2026-09-21):
 * - 규격 거부(HTTP 4xx, resultType=FAIL) → 재시도 없이 [PushRejectedException]. 스케줄러는 push_log를
 *   남겨(미전달) 같은 스테이지를 다시 부르지 않는다 — 종전엔 롤백 후 30초마다 2회씩 무한 재호출돼
 *   토스 측에 "잘못된 규격 분당 N건" 경고를 유발했다.
 * - 일시 장애(네트워크·5xx) → 1회 즉시 재시도 후 예외 — 스케줄러가 push_log를 되돌려 다음 틱에 다시 시도한다.
 * HTTP 200이라도 응답 resultType=FAIL이면 거부로 취급한다. resultType=SUCCESS인데 sentPushCount=0인
 * 유저 단위 미전달(약관 미동의 등)은 재시도 없이 미전달(false)로만 기록한다.
 * 발송 한도(앱 15,000회/분·유저 10회/분, NFR-08)는 유저당 하루 2회 구조상 도달 불가라 별도 제어 없음.
 */
@Component
class AppsInTossPushClient(private val props: PushProperties) {

    private val log = LoggerFactory.getLogger(javaClass)

    // 인증서 없는 로컬 기동에서 SSLContext 초기화가 부팅을 깨지 않도록 지연 생성 (TossOAuthClient와 동일 패턴)
    private val rest: RestClient by lazy {
        val http = HttpClient.newBuilder()
            .sslContext(mtlsSslContext(props.keystorePath, props.keystorePassword))
            .connectTimeout(Duration.ofSeconds(5))
            .build()
        RestClient.builder()
            .baseUrl(props.baseUrl)
            .requestFactory(JdkClientHttpRequestFactory(http).apply { setReadTimeout(Duration.ofSeconds(10)) })
            .build()
    }

    /** @return 실제 발송이면 true, dry-run·발송 불가 대상이면 false (push_log.delivered에 그대로 기록) */
    fun send(userKey: String, decision: PushDecision): Boolean {
        val templateSetCode = when (decision.stage) {
            PushStage.PRE -> props.templatePre
            PushStage.REMIND -> props.templateRemind
        }
        if (!props.live || templateSetCode.isBlank()) {
            log.info("[dry-run] 푸시 미발송 — user={} stage={} route={} minutes={} departure={}",
                userKey, decision.stage, decision.routeName, decision.minutesToArrival, decision.departureTime)
            return false
        }

        val (targetHeader, targetValue) = targetHeader(userKey) ?: run {
            log.warn("푸시 대상 아님(토스 발송 키 형식이 아님) — user={} stage={}: 호출 생략", userKey, decision.stage)
            return false
        }

        val body = mapOf(
            "templateSetCode" to templateSetCode,
            "context" to context(decision),
        )
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                val response = rest.post()
                    .uri("/api-partner/v1/apps-in-toss/messenger/send-message")
                    .header(targetHeader, targetValue)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map::class.java)
                val resultType = response?.get("resultType")
                if (resultType == "FAIL") {
                    val error = response["error"] as? Map<*, *>
                    throw PushRejectedException(
                        "send-message FAIL — code=${error?.get("errorCode")} reason=${error?.get("reason")} " +
                            "template=$templateSetCode target=$targetHeader",
                    )
                }
                // resultType=SUCCESS라도 유저 단위 전달 실패가 detail.fail에 숨어 있다 (예: TERMS_DISAGREED_MEMBER —
                // 2026-09-09 리마인드 미수신 사고). 재시도해도 같은 유저 상태면 결과가 같으니 미전달로 기록만 하고 끝낸다.
                val success = response?.get("success") as? Map<*, *>
                val sentPushCount = (success?.get("sentPushCount") as? Number)?.toInt()
                if (sentPushCount == 0) {
                    val failReason = ((success["fail"] as? Map<*, *>)?.get("sentPush") as? List<*>)
                        ?.firstNotNullOfOrNull { (it as? Map<*, *>)?.get("reachedFailReason") }
                    log.warn("푸시 미전달(응답은 SUCCESS) — user={} stage={} reason={}", userKey, decision.stage, failReason)
                    return false
                }
                return true
            } catch (e: PushRejectedException) {
                throw e
            } catch (e: RestClientResponseException) {
                // 4xx = 우리 요청 규격 문제(헤더·템플릿 코드·context) — 다시 보내도 같은 답이라 즉시 거부로 끝낸다
                if (e.statusCode.is4xxClientError) {
                    throw PushRejectedException(
                        "send-message ${e.statusCode.value()} — template=$templateSetCode target=$targetHeader " +
                            "body=${e.responseBodyAsString.take(300)}",
                    )
                }
                lastError = e
                log.warn("푸시 발송 실패(시도 {}/2) — user={} stage={}: {}", attempt + 1, userKey, decision.stage, e.message)
            } catch (e: Exception) {
                lastError = e
                log.warn("푸시 발송 실패(시도 {}/2) — user={} stage={}: {}", attempt + 1, userKey, decision.stage, e.message)
            }
        }
        throw lastError!!
    }

    /**
     * 발송 대상 헤더 — `anon:{hash}`는 x-anon-key, 숫자(login-me userKey)는 x-toss-user-key.
     * 둘 다 아니면 null: 토스가 모르는 키라 호출해 봐야 "유효하지 않은 userKey" 거부만 쌓인다.
     */
    internal fun targetHeader(userKey: String): Pair<String, String>? = when {
        userKey.startsWith("anon:") -> userKey.removePrefix("anon:").takeIf { it.isNotBlank() }?.let { "x-anon-key" to it }
        userKey.isNotEmpty() && userKey.all { it.isDigit() } -> "x-toss-user-key" to userKey
        else -> null
    }

    /**
     * 콘솔 등록 템플릿(2026-09-08)의 변수는 `minute` 하나 — 본문이 `{{minute}}분 후 도착해요.` 꼴이라
     * 값이 비면 "분 후 도착해요."로 깨진다 (2026-09-10 오너 리포트). 실시간 정보가 없으면 `몇 `을 넣어
     * "몇 분 후 도착해요."라는 자연스러운 폴백 문장으로 렌더링한다 — 템플릿 재검수 없이 해결.
     */
    internal fun context(decision: PushDecision): Map<String, String> =
        mapOf("minute" to (decision.minutesToArrival?.toString() ?: MINUTE_FALLBACK))

    private companion object {
        const val MINUTE_FALLBACK = "몇 "
    }
}
