package dev.hansw.catchmyride.journey

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.time.LocalDateTime

@Repository
class JourneyRepository(
    private val jdbc: JdbcClient,
    private val objectMapper: ObjectMapper,
) {

    /** lastUsedAt 내림차순(히스토리), null은 생성순 뒤 (§9-1) */
    fun findAll(userKey: String): List<Journey> =
        jdbc.sql(
            """
            SELECT journey_id, label, repeat_days_json, legs_json, last_used_at
            FROM journey WHERE user_key = :userKey
            ORDER BY last_used_at DESC NULLS LAST, created_at ASC
            """.trimIndent(),
        )
            .param("userKey", userKey)
            .query { rs, _ -> toJourney(rs) }
            .list()

    fun find(userKey: String, journeyId: String): Journey? =
        jdbc.sql(
            """
            SELECT journey_id, label, repeat_days_json, legs_json, last_used_at
            FROM journey WHERE user_key = :userKey AND journey_id = :journeyId
            """.trimIndent(),
        )
            .param("userKey", userKey)
            .param("journeyId", journeyId)
            .query { rs, _ -> toJourney(rs) }
            .optional().orElse(null)

    fun count(userKey: String): Int =
        jdbc.sql("SELECT COUNT(*) FROM journey WHERE user_key = :userKey")
            .param("userKey", userKey)
            .query(Int::class.java).single()

    // ":param IS NULL" 패턴 금지 — PostgreSQL이 파라미터 타입을 못 정해 500이 난다
    // (H2 테스트는 통과해 배포에서만 터졌던 2026-09-14 사고). null 여부로 쿼리를 나눈다.
    fun labelExists(userKey: String, label: String, excludeId: String? = null): Boolean {
        val spec = if (excludeId == null) {
            jdbc.sql("SELECT COUNT(*) FROM journey WHERE user_key = :userKey AND label = :label")
        } else {
            jdbc.sql(
                """
                SELECT COUNT(*) FROM journey
                WHERE user_key = :userKey AND label = :label AND journey_id <> :excludeId
                """.trimIndent(),
            ).param("excludeId", excludeId)
        }
        return spec
            .param("userKey", userKey)
            .param("label", label)
            .query(Int::class.java).single() > 0
    }

    fun insert(userKey: String, journey: Journey, now: LocalDateTime) {
        jdbc.sql(
            """
            INSERT INTO journey (user_key, journey_id, label, repeat_days_json, legs_json, last_used_at, created_at)
            VALUES (:userKey, :journeyId, :label, :repeatDays, :legs, NULL, :now)
            """.trimIndent(),
        )
            .param("userKey", userKey)
            .param("journeyId", journey.id)
            .param("label", journey.label)
            .param("repeatDays", objectMapper.writeValueAsString(journey.repeatDays))
            .param("legs", objectMapper.writeValueAsString(journey.legs))
            .param("now", now)
            .update()
    }

    /** @return 갱신된 행 수 — 0이면 없는 여정 */
    fun update(userKey: String, journey: Journey): Int =
        jdbc.sql(
            """
            UPDATE journey SET label = :label, repeat_days_json = :repeatDays, legs_json = :legs
            WHERE user_key = :userKey AND journey_id = :journeyId
            """.trimIndent(),
        )
            .param("userKey", userKey)
            .param("journeyId", journey.id)
            .param("label", journey.label)
            .param("repeatDays", objectMapper.writeValueAsString(journey.repeatDays))
            .param("legs", objectMapper.writeValueAsString(journey.legs))
            .update()

    fun touchLastUsed(userKey: String, journeyId: String, now: LocalDateTime) {
        jdbc.sql(
            "UPDATE journey SET last_used_at = :now WHERE user_key = :userKey AND journey_id = :journeyId",
        )
            .param("userKey", userKey)
            .param("journeyId", journeyId)
            .param("now", now)
            .update()
    }

    fun delete(userKey: String, journeyId: String): Int =
        jdbc.sql("DELETE FROM journey WHERE user_key = :userKey AND journey_id = :journeyId")
            .param("userKey", userKey)
            .param("journeyId", journeyId)
            .update()

    private fun toJourney(rs: ResultSet): Journey = Journey(
        id = rs.getString("journey_id"),
        label = rs.getString("label"),
        repeatDays = objectMapper.readValue(rs.getString("repeat_days_json"), Array<String>::class.java).toList(),
        legs = objectMapper.readValue(rs.getString("legs_json"), Array<JourneyLeg>::class.java).toList(),
        lastUsedAt = rs.getTimestamp("last_used_at")?.toLocalDateTime(),
    )
}
