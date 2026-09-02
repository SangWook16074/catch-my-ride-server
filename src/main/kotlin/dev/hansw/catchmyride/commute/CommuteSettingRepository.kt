package dev.hansw.catchmyride.commute

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.time.LocalDateTime

/** findAll용 — 스케줄러는 발송 대상 식별에 userKey가 함께 필요하다 */
data class StoredSetting(val userKey: String, val setting: CommuteSetting)

/**
 * commute_setting 테이블 저장소 (schema.sql).
 * stops·activeDays는 통째로만 읽고 쓰므로 JSON 문자열 컬럼 — upsert는 방언 의존을 피해 delete+insert.
 */
@Repository
class CommuteSettingRepository(
    private val jdbc: JdbcClient,
    private val objectMapper: ObjectMapper,
) {

    /** 푸시 스케줄러(S-5)가 매 틱 전체 순회 — MVP 유저 규모에서 문제 없고, 커지면 활성 요일·시간대 필터를 SQL로 내린다 */
    fun findAll(): List<StoredSetting> =
        jdbc.sql("SELECT * FROM commute_setting")
            .query { rs, _ -> StoredSetting(rs.getString("user_key"), toSetting(rs)) }
            .list()

    fun find(userKey: String): CommuteSetting? =
        jdbc.sql("SELECT * FROM commute_setting WHERE user_key = :userKey")
            .param("userKey", userKey)
            .query { rs, _ -> toSetting(rs) }
            .optional()
            .orElse(null)

    @Transactional
    fun upsert(userKey: String, setting: CommuteSetting) {
        jdbc.sql("DELETE FROM commute_setting WHERE user_key = :userKey")
            .param("userKey", userKey).update()
        jdbc.sql(
            """
            INSERT INTO commute_setting
              (user_key, home_latitude, home_longitude, stops_json, walk_minutes, notification_mode,
               fixed_departure_time, window_start, window_end, buffer_minutes, active_days_json, updated_at)
            VALUES
              (:userKey, :lat, :lng, :stops, :walk, :mode, :fixedTime, :windowStart, :windowEnd, :buffer, :days, :updatedAt)
            """.trimIndent(),
        )
            .param("userKey", userKey)
            .param("lat", setting.home.latitude)
            .param("lng", setting.home.longitude)
            .param("stops", objectMapper.writeValueAsString(setting.stops))
            .param("walk", setting.walkMinutes)
            .param("mode", setting.notificationMode.name)
            .param("fixedTime", setting.fixedDepartureTime)
            .param("windowStart", setting.commuteWindow?.start)
            .param("windowEnd", setting.commuteWindow?.end)
            .param("buffer", setting.bufferMinutes)
            .param("days", objectMapper.writeValueAsString(setting.activeDays))
            .param("updatedAt", LocalDateTime.now())
            .update()
    }

    fun delete(userKey: String) {
        jdbc.sql("DELETE FROM commute_setting WHERE user_key = :userKey")
            .param("userKey", userKey).update()
    }

    private fun toSetting(rs: ResultSet): CommuteSetting {
        val windowStart = rs.getString("window_start")
        return CommuteSetting(
            home = GeoPoint(rs.getDouble("home_latitude"), rs.getDouble("home_longitude")),
            stops = objectMapper.readValue(rs.getString("stops_json"), Array<CommuteStop>::class.java).toList(),
            walkMinutes = rs.getInt("walk_minutes"),
            notificationMode = NotificationMode.valueOf(rs.getString("notification_mode")),
            fixedDepartureTime = rs.getString("fixed_departure_time"),
            commuteWindow = windowStart?.let { CommuteWindow(it, rs.getString("window_end")) },
            bufferMinutes = rs.getInt("buffer_minutes"),
            activeDays = objectMapper.readValue(rs.getString("active_days_json"), Array<String>::class.java).toList(),
        )
    }
}
