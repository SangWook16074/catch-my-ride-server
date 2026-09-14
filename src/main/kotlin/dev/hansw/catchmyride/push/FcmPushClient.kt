package dev.hansw.catchmyride.push

import com.google.auth.oauth2.GoogleCredentials
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import tools.jackson.databind.json.JsonMapper
import java.io.FileInputStream
import java.time.LocalDate

/**
 * 스토어앱 FCM 발송 클라이언트 (API.md §4-1) — HTTP v1 messages:send.
 * 인증: 서비스 계정 키(JSON, env FCM_SERVICE_ACCOUNT 경로) → OAuth2 액세스 토큰.
 * 키 미설정이면 dry-run(로그만) — 코드베이스 공통 강등 규칙 (AppsInTossPushClient와 동일).
 *
 * 문구는 토스 콘솔 검수 제약(제목 7자·본문 25자) 없이 자유 — 미니앱보다 리치한 카피를 쓴다.
 * 알림 탭 랜딩은 data.link의 커스텀 스킴 딥링크 — 클라이언트가 탑승 피드백 프롬프트를 띄운다.
 *
 * UNREGISTERED(앱 삭제·토큰 만료)는 토큰을 폐기하고 미전달(false)로 기록한다 — 재시도 무의미.
 * 일시 오류는 1회 즉시 재시도 후 예외 — 스케줄러가 push_log를 되돌려 다음 틱에 다시 시도한다.
 */
@Component
class FcmPushClient(
    private val props: PushProperties,
    private val tokens: PushTokenRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper = JsonMapper()

    // 키 파일 없는 로컬 기동에서 부팅이 깨지지 않게 지연 생성 (mtlsSslContext와 동일 패턴)
    private val credentials: GoogleCredentials by lazy {
        FileInputStream(props.fcmServiceAccountPath).use {
            GoogleCredentials.fromStream(it).createScoped(FCM_SCOPE)
        }
    }
    private val projectId: String by lazy {
        val json = mapper.readTree(java.io.File(props.fcmServiceAccountPath))
        json.get("project_id")?.asString() ?: error("서비스 계정 JSON에 project_id가 없습니다")
    }
    private val rest: RestClient by lazy { RestClient.builder().baseUrl(props.fcmBaseUrl).build() }

    val live: Boolean get() = props.fcmServiceAccountPath.isNotBlank()

    /** @return 실제 발송이면 true, dry-run·미전달(토큰 폐기)이면 false (push_log.delivered에 그대로 기록) */
    fun send(userKey: String, token: PushToken, decision: PushDecision, notifiedDate: LocalDate): Boolean {
        if (!live) {
            log.info(
                "[dry-run] FCM 미발송 — user={} stage={} route={} minutes={}",
                userKey, decision.stage, decision.routeName, decision.minutesToArrival,
            )
            return false
        }
        return sendMessage(
            userKey = userKey,
            token = token,
            title = title(decision),
            body = body(decision),
            // 알림 탭 → 커스텀 스킴 딥링크 (클라이언트 parsePushEntry 계약)
            link = "catchmyride://open?from=push&notifiedDate=$notifiedDate",
        )
    }

    /**
     * 임의 문구 발송 — 트립 하차 알림(§9-4) 등 출발 알림 밖의 발송 공용.
     * 재시도·UNREGISTERED 토큰 폐기 규칙은 출발 알림과 동일하다.
     */
    fun sendMessage(userKey: String, token: PushToken, title: String, body: String, link: String): Boolean {
        if (!live) {
            log.info("[dry-run] FCM 미발송 — user={} title={}", userKey, title)
            return false
        }
        val payload = mapOf(
            "message" to mapOf(
                "token" to token.token,
                "notification" to mapOf("title" to title, "body" to body),
                "data" to mapOf("link" to link),
                "apns" to mapOf("payload" to mapOf("aps" to mapOf("sound" to "default"))),
                "android" to mapOf("priority" to "HIGH"),
            ),
        )
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                credentials.refreshIfExpired()
                rest.post()
                    .uri("/v1/projects/$projectId/messages:send")
                    .header("Authorization", "Bearer ${credentials.accessToken.tokenValue}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity()
                return true
            } catch (e: RestClientResponseException) {
                // 404 NOT_FOUND / errorCode UNREGISTERED = 죽은 토큰 — 폐기하고 미전달로 종료 (§4-1)
                if (e.statusCode.value() == 404 || e.responseBodyAsString.contains("UNREGISTERED")) {
                    log.warn("FCM 토큰 폐기(UNREGISTERED) — user={} title={}", userKey, title)
                    tokens.delete(userKey)
                    return false
                }
                lastError = e
                log.warn("FCM 발송 실패(시도 {}/2) — user={} title={}: {}", attempt + 1, userKey, title, e.message)
            } catch (e: Exception) {
                lastError = e
                log.warn("FCM 발송 실패(시도 {}/2) — user={} title={}: {}", attempt + 1, userKey, title, e.message)
            }
        }
        throw lastError!!
    }

    private fun title(decision: PushDecision): String = when (decision.stage) {
        PushStage.PRE -> "곧 나가야 해요"
        PushStage.REMIND -> "1분 뒤 나가세요"
    }

    private fun body(decision: PushDecision): String {
        val route = decision.routeName
        val minutes = decision.minutesToArrival
        return when {
            route != null && minutes != null -> "$route ${minutes}분 후 도착해요. 지금 준비하세요."
            route != null -> "$route 출발 시간이에요. 지금 준비하세요."
            else -> "출발 시간이에요. 지금 준비하세요."
        }
    }

    companion object {
        private const val FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
    }
}
