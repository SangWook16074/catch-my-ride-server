package dev.hansw.catchmyride.feedback

import dev.hansw.catchmyride.api.ApiException
import dev.hansw.catchmyride.api.UserKeyResolver
import dev.hansw.catchmyride.push.PushLogRepository
import dev.hansw.catchmyride.push.PushProperties
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * API.md §3 — 탑승 피드백 원탭 (FR-601, North Star의 유일한 측정 수단).
 * 같은 notifiedDate 재제출은 마지막 값으로 갱신.
 * "그날 발송 이력이 없으면 400" 검증은 라이브 발송 상태(push.api-key 설정)에서만 켠다 —
 * dry-run 단계에서 막으면 클라이언트 E2E(가짜 ?from=push 진입 테스트)가 전부 400이 되기 때문.
 */
@RestController
class BoardingFeedbackController(
    private val repository: BoardingFeedbackRepository,
    private val userKeys: UserKeyResolver,
    private val pushLog: PushLogRepository,
    private val pushProps: PushProperties,
) {

    data class Request(val result: String, val notifiedDate: String)
    data class Response(val recorded: Boolean)

    @PostMapping("/api/v1/boarding-feedback")
    @ResponseStatus(HttpStatus.CREATED)
    fun post(
        @RequestHeader(value = "Authorization", required = false) auth: String?,
        @RequestBody request: Request,
    ): Response {
        if (request.result !in RESULTS) throw ApiException.invalidRequest("result는 BOARDED 또는 MISSED여야 합니다")
        val date = runCatching { LocalDate.parse(request.notifiedDate) }
            .getOrElse { throw ApiException.invalidRequest("notifiedDate는 YYYY-MM-DD 형식이어야 합니다") }
        val userKey = userKeys.resolve(auth)
        if (pushProps.live && !pushLog.hasAny(userKey, date)) {
            throw ApiException.invalidRequest("해당 날짜에 발송된 알림이 없습니다")
        }
        repository.upsert(userKey, date, request.result)
        return Response(recorded = true)
    }

    companion object {
        private val RESULTS = setOf("BOARDED", "MISSED")
    }
}

@Repository
class BoardingFeedbackRepository(private val jdbc: JdbcClient) {

    /** §3-1 버퍼 추천용 — 최근 알림일 순 결과(BOARDED/MISSED) 문자열 */
    fun recentResults(userKey: String, limit: Int): List<String> =
        jdbc.sql(
            """
            SELECT result FROM boarding_feedback
            WHERE user_key = :userKey ORDER BY notified_date DESC LIMIT :limit
            """.trimIndent(),
        )
            .param("userKey", userKey).param("limit", limit)
            .query { rs, _ -> rs.getString("result") }
            .list()

    @Transactional
    fun upsert(userKey: String, notifiedDate: LocalDate, result: String) {
        jdbc.sql("DELETE FROM boarding_feedback WHERE user_key = :userKey AND notified_date = :date")
            .param("userKey", userKey).param("date", notifiedDate).update()
        jdbc.sql(
            """
            INSERT INTO boarding_feedback (user_key, notified_date, result, submitted_at)
            VALUES (:userKey, :date, :result, :submittedAt)
            """.trimIndent(),
        )
            .param("userKey", userKey)
            .param("date", notifiedDate)
            .param("result", result)
            .param("submittedAt", LocalDateTime.now())
            .update()
    }
}
