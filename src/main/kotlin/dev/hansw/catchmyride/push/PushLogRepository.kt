package dev.hansw.catchmyride.push

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.time.LocalDateTime

enum class PushStage { PRE, REMIND }

/**
 * push_log 저장소 — FR-403(출근 1회당 최대 2회)의 강제 장치.
 * 발송 직전에 기록하고 실패 시 지워서(다음 틱 재시도) 어떤 경로로도 같은 스테이지가 두 번 나가지 않게 한다.
 */
@Repository
class PushLogRepository(private val jdbc: JdbcClient) {

    fun sentStages(userKey: String, date: LocalDate): Set<PushStage> =
        jdbc.sql("SELECT stage FROM push_log WHERE user_key = :userKey AND notified_date = :date")
            .param("userKey", userKey).param("date", date)
            .query { rs, _ -> PushStage.valueOf(rs.getString("stage")) }
            .list().toSet()

    /** API.md §3 — "그날 알림 발송 이력이 없으면 400" 검증용 */
    fun hasAny(userKey: String, date: LocalDate): Boolean =
        jdbc.sql("SELECT COUNT(*) FROM push_log WHERE user_key = :userKey AND notified_date = :date")
            .param("userKey", userKey).param("date", date)
            .query { rs, _ -> rs.getLong(1) }.single() > 0

    fun record(userKey: String, date: LocalDate, stage: PushStage, routeName: String?, delivered: Boolean) {
        jdbc.sql(
            """
            INSERT INTO push_log (user_key, notified_date, stage, route_name, delivered, sent_at)
            VALUES (:userKey, :date, :stage, :routeName, :delivered, :sentAt)
            """.trimIndent(),
        )
            .param("userKey", userKey)
            .param("date", date)
            .param("stage", stage.name)
            .param("routeName", routeName)
            .param("delivered", delivered)
            .param("sentAt", LocalDateTime.now())
            .update()
    }

    fun markDelivered(userKey: String, date: LocalDate, stage: PushStage) {
        jdbc.sql("UPDATE push_log SET delivered = TRUE WHERE user_key = :userKey AND notified_date = :date AND stage = :stage")
            .param("userKey", userKey).param("date", date).param("stage", stage.name)
            .update()
    }

    fun delete(userKey: String, date: LocalDate, stage: PushStage) {
        jdbc.sql("DELETE FROM push_log WHERE user_key = :userKey AND notified_date = :date AND stage = :stage")
            .param("userKey", userKey).param("date", date).param("stage", stage.name)
            .update()
    }
}
