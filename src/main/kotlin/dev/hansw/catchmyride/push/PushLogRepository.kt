package dev.hansw.catchmyride.push

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.time.LocalDateTime

enum class PushStage { PRE, REMIND }

/** 경로의 그날 마지막 발송 — 스케줄러가 다음 스테이지/사이클/간격을 판단하는 근거 */
data class LastPush(val stage: PushStage, val cycle: Int, val sentAt: LocalDateTime)

/**
 * push_log 저장소 — FR-403(사이클당 최대 2회)의 강제 장치. 경로(routeId)×사이클 단위로 스테이지 중복을 막는다.
 * 발송 직전에 기록하고 실패 시 지워서(다음 틱 재시도) 같은 사이클의 같은 스테이지가 두 번 나가지 않게 한다.
 * FIXED 모드는 cycle 0 하나(하루 2회), RECOMMENDED는 시간대 안에서 사이클이 늘어난다 (2026-09-09 FR-403 개정).
 */
@Repository
class PushLogRepository(private val jdbc: JdbcClient) {

    fun sentStages(userKey: String, routeId: String, date: LocalDate): Set<PushStage> =
        jdbc.sql(
            "SELECT stage FROM push_log WHERE user_key = :userKey AND route_id = :routeId AND notified_date = :date",
        )
            .param("userKey", userKey).param("routeId", routeId).param("date", date)
            .query { rs, _ -> PushStage.valueOf(rs.getString("stage")) }
            .list().toSet()

    fun lastEntry(userKey: String, routeId: String, date: LocalDate): LastPush? =
        jdbc.sql(
            """
            SELECT stage, cycle, sent_at FROM push_log
            WHERE user_key = :userKey AND route_id = :routeId AND notified_date = :date
            ORDER BY sent_at DESC, cycle DESC LIMIT 1
            """.trimIndent(),
        )
            .param("userKey", userKey).param("routeId", routeId).param("date", date)
            .query { rs, _ ->
                LastPush(
                    PushStage.valueOf(rs.getString("stage")),
                    rs.getInt("cycle"),
                    rs.getTimestamp("sent_at").toLocalDateTime(),
                )
            }
            .optional().orElse(null)

    /** API.md §3 — "그날 알림 발송 이력이 없으면 400" 검증용. 경로 무관하게 그날 발송 여부만 본다 */
    fun hasAny(userKey: String, date: LocalDate): Boolean =
        jdbc.sql("SELECT COUNT(*) FROM push_log WHERE user_key = :userKey AND notified_date = :date")
            .param("userKey", userKey).param("date", date)
            .query { rs, _ -> rs.getLong(1) }.single() > 0

    fun record(
        userKey: String, routeId: String, date: LocalDate, stage: PushStage,
        routeName: String?, delivered: Boolean, cycle: Int = 0,
        /** 스케줄러 시계 기준 — 사이클 간격 판정(lastEntry.sentAt)과 같은 시계를 쓴다 */
        sentAt: LocalDateTime = LocalDateTime.now(),
    ) {
        jdbc.sql(
            """
            INSERT INTO push_log (user_key, route_id, notified_date, stage, route_name, delivered, sent_at, cycle)
            VALUES (:userKey, :routeId, :date, :stage, :routeName, :delivered, :sentAt, :cycle)
            """.trimIndent(),
        )
            .param("userKey", userKey)
            .param("routeId", routeId)
            .param("date", date)
            .param("stage", stage.name)
            .param("routeName", routeName)
            .param("delivered", delivered)
            .param("sentAt", sentAt)
            .param("cycle", cycle)
            .update()
    }

    fun markDelivered(userKey: String, routeId: String, date: LocalDate, stage: PushStage, cycle: Int = 0) {
        jdbc.sql(
            """
            UPDATE push_log SET delivered = TRUE
            WHERE user_key = :userKey AND route_id = :routeId AND notified_date = :date AND stage = :stage AND cycle = :cycle
            """.trimIndent(),
        )
            .param("userKey", userKey).param("routeId", routeId).param("date", date).param("stage", stage.name)
            .param("cycle", cycle)
            .update()
    }

    fun delete(userKey: String, routeId: String, date: LocalDate, stage: PushStage, cycle: Int = 0) {
        jdbc.sql(
            """
            DELETE FROM push_log
            WHERE user_key = :userKey AND route_id = :routeId AND notified_date = :date AND stage = :stage AND cycle = :cycle
            """.trimIndent(),
        )
            .param("userKey", userKey).param("routeId", routeId).param("date", date).param("stage", stage.name)
            .param("cycle", cycle)
            .update()
    }
}
