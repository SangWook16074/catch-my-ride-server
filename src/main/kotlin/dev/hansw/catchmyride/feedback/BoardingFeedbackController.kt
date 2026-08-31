package dev.hansw.catchmyride.feedback

import dev.hansw.catchmyride.api.ApiException
import dev.hansw.catchmyride.api.UserKeyResolver
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
 * 같은 notifiedDate 재제출은 마지막 값으로 갱신. "그날 발송 이력 검증"은 푸시(S-5/S-6) 구현 후 추가.
 */
@RestController
class BoardingFeedbackController(
    private val repository: BoardingFeedbackRepository,
    private val userKeys: UserKeyResolver,
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
        repository.upsert(userKeys.resolve(auth), date, request.result)
        return Response(recorded = true)
    }

    companion object {
        private val RESULTS = setOf("BOARDED", "MISSED")
    }
}

@Repository
class BoardingFeedbackRepository(private val jdbc: JdbcClient) {

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
