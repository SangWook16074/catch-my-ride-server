package dev.hansw.catchmyride.push

import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Duration
import java.time.format.DateTimeFormatter

/**
 * S-6 앱인토스 send-message 클라이언트 (docs/push-templates.md).
 * POST /api-partner/v1/apps-in-toss/messenger/send-message — templateSetCode + context 변수 주입.
 *
 * api-key 또는 해당 스테이지 템플릿 코드가 비어 있으면 dry-run(로그만 남기고 미발송).
 * 실패는 1회 즉시 재시도(S-6) 후 예외 — 스케줄러가 push_log를 되돌려 다음 틱에 다시 시도한다.
 * 발송 한도(앱 15,000회/분·유저 10회/분, NFR-08)는 유저당 하루 2회 구조상 도달 불가라 별도 제어 없음.
 */
@Component
class AppsInTossPushClient(private val props: PushProperties) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val rest = RestClient.builder()
        .baseUrl(props.baseUrl)
        .requestFactory(JdkClientHttpRequestFactory().apply { setReadTimeout(Duration.ofSeconds(10)) })
        .build()

    /** @return 실제 발송이면 true, dry-run이면 false (push_log.delivered에 그대로 기록) */
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

        val body = mapOf(
            "userKey" to userKey,
            "templateSetCode" to templateSetCode,
            "context" to context(decision),
        )
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                rest.post()
                    .uri("/api-partner/v1/apps-in-toss/messenger/send-message")
                    // 인증 스킴은 파트너 키 발급 후 확정(API.md 미확정 1) — 우선 Bearer, 다르면 여기만 고친다
                    .header("Authorization", "Bearer ${props.apiKey}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity()
                return true
            } catch (e: Exception) {
                lastError = e
                log.warn("푸시 발송 실패(시도 {}/2) — user={} stage={}: {}", attempt + 1, userKey, decision.stage, e.message)
            }
        }
        throw lastError!!
    }

    /** docs/push-templates.md 변수 — 값이 없으면 빈 문자열(템플릿 렌더링에서 자연 탈락) */
    private fun context(decision: PushDecision): Map<String, String> = buildMap {
        put("route", decision.routeName.orEmpty())
        put("minutes", decision.minutesToArrival?.toString().orEmpty())
        if (decision.stage == PushStage.PRE) {
            put("departureTime", decision.departureTime.format(DEPARTURE_FORMAT))
        }
    }

    companion object {
        private val DEPARTURE_FORMAT = DateTimeFormatter.ofPattern("H:mm") // 템플릿 예시 표기 "8:12"
    }
}
