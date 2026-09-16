package dev.hansw.catchmyride.feedback

import dev.hansw.catchmyride.api.ApiException
import dev.hansw.catchmyride.api.UserKeyResolver
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * API.md §3 — 탑승 피드백 원탭 (FR-601, North Star의 유일한 측정 수단).
 * 같은 notifiedDate 재제출은 마지막 값으로 갱신.
 * ~~"그날 발송 이력이 없으면 400"~~ → 제거 (오너 결정 2026-09-15): "탔어요"가 하차 알림
 * 브리지의 진입점이 되면서, 알림이 없던 날의 자발 피드백도 유효한 신호다 —
 * 알림 여부와 무관하게 기록한다.
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
        val userKey = userKeys.resolve(auth)
        repository.upsert(userKey, date, request.result)
        return Response(recorded = true)
    }

    data class HistoryEntry(val date: String, val result: String)
    data class HistoryResponse(val entries: List<HistoryEntry>)

    /**
     * API.md §3-2 — 통근 리포트("나의 통근")용 이력 (명세서 §9 2026-09-16).
     * 서버는 이력만 반환한다 — 성공률·스트릭 집계는 클라이언트 몫 (§3-1과 같은 태도).
     */
    @GetMapping("/api/v1/boarding-feedback/history")
    fun history(
        @RequestHeader(value = "Authorization", required = false) auth: String?,
        @RequestParam(value = "limit", required = false) limit: Int?,
    ): HistoryResponse {
        val userKey = userKeys.resolve(auth)
        val capped = (limit ?: HISTORY_DEFAULT_LIMIT).coerceIn(1, HISTORY_MAX_LIMIT)
        return HistoryResponse(
            repository.history(userKey, capped).map { HistoryEntry(it.date.toString(), it.result) },
        )
    }

    companion object {
        private val RESULTS = setOf("BOARDED", "MISSED")
        const val HISTORY_DEFAULT_LIMIT = 60
        const val HISTORY_MAX_LIMIT = 366
    }
}

data class FeedbackHistoryRow(val date: LocalDate, val result: String)

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

    /** §3-2 통근 리포트용 — 최근 알림일 순 (날짜, 결과) 이력 */
    fun history(userKey: String, limit: Int): List<FeedbackHistoryRow> =
        jdbc.sql(
            """
            SELECT notified_date, result FROM boarding_feedback
            WHERE user_key = :userKey ORDER BY notified_date DESC LIMIT :limit
            """.trimIndent(),
        )
            .param("userKey", userKey).param("limit", limit)
            .query { rs, _ ->
                FeedbackHistoryRow(rs.getDate("notified_date").toLocalDate(), rs.getString("result"))
            }
            .list()

    /** S-5 반복 발송 중단용 — 그날 "탔어요"가 접수됐는가 (이미 탄 유저에게 다음 차 안내는 소음) */
    fun boardedOn(userKey: String, date: LocalDate): Boolean =
        jdbc.sql(
            """
            SELECT COUNT(*) FROM boarding_feedback
            WHERE user_key = :userKey AND notified_date = :date AND result = 'BOARDED'
            """.trimIndent(),
        )
            .param("userKey", userKey).param("date", date)
            .query { rs, _ -> rs.getLong(1) }.single() > 0

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
