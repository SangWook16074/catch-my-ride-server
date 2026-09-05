package dev.hansw.catchmyride.commute

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.time.LocalDateTime

/** findAllEnabled용 — 스케줄러는 발송 대상 식별에 userKey가 함께 필요하다 */
data class StoredRoute(val userKey: String, val route: CommuteRoute)

/**
 * commute_route 테이블 저장소 (schema.sql).
 * stops·activeDays는 통째로만 읽고 쓰므로 JSON 문자열 컬럼 — 갱신은 방언 의존을 피해 delete+insert.
 * 목록 순서는 created_at — 첫 경로가 레거시 API(/commute-setting)와 routeId 생략 시의 기본 경로다.
 */
@Repository
class CommuteRouteRepository(
    private val jdbc: JdbcClient,
    private val objectMapper: ObjectMapper,
) {

    /** 푸시 스케줄러(S-5)가 매 틱 전체 순회 — MVP 규모에서 문제 없고, 커지면 요일·시간대 필터를 SQL로 내린다 */
    fun findAllEnabled(): List<StoredRoute> =
        jdbc.sql("SELECT * FROM commute_route WHERE enabled = TRUE")
            .query { rs, _ -> StoredRoute(rs.getString("user_key"), toRoute(rs)) }
            .list()

    fun list(userKey: String): List<CommuteRoute> =
        jdbc.sql("SELECT * FROM commute_route WHERE user_key = :userKey ORDER BY created_at, route_id")
            .param("userKey", userKey)
            .query { rs, _ -> toRoute(rs) }
            .list()

    fun find(userKey: String, routeId: String): CommuteRoute? =
        jdbc.sql("SELECT * FROM commute_route WHERE user_key = :userKey AND route_id = :routeId")
            .param("userKey", userKey).param("routeId", routeId)
            .query { rs, _ -> toRoute(rs) }
            .optional()
            .orElse(null)

    fun insert(userKey: String, route: CommuteRoute) {
        val now = LocalDateTime.now()
        jdbc.sql(
            """
            INSERT INTO commute_route
              (user_key, route_id, label, enabled, home_latitude, home_longitude, stops_json, walk_minutes,
               notification_mode, fixed_departure_time, window_start, window_end, buffer_minutes, active_days_json,
               created_at, updated_at)
            VALUES
              (:userKey, :routeId, :label, :enabled, :lat, :lng, :stops, :walk, :mode, :fixedTime,
               :windowStart, :windowEnd, :buffer, :days, :createdAt, :updatedAt)
            """.trimIndent(),
        )
            .settingParams(userKey, route)
            .param("createdAt", now)
            .param("updatedAt", now)
            .update()
    }

    /** created_at을 보존해 목록 순서(= 기본 경로 판단)를 유지한다 */
    fun update(userKey: String, route: CommuteRoute): Boolean {
        val createdAt = jdbc.sql(
            "SELECT created_at FROM commute_route WHERE user_key = :userKey AND route_id = :routeId",
        )
            .param("userKey", userKey).param("routeId", route.id)
            .query { rs, _ -> rs.getTimestamp("created_at").toLocalDateTime() }
            .optional().orElse(null) ?: return false
        jdbc.sql("DELETE FROM commute_route WHERE user_key = :userKey AND route_id = :routeId")
            .param("userKey", userKey).param("routeId", route.id).update()
        jdbc.sql(
            """
            INSERT INTO commute_route
              (user_key, route_id, label, enabled, home_latitude, home_longitude, stops_json, walk_minutes,
               notification_mode, fixed_departure_time, window_start, window_end, buffer_minutes, active_days_json,
               created_at, updated_at)
            VALUES
              (:userKey, :routeId, :label, :enabled, :lat, :lng, :stops, :walk, :mode, :fixedTime,
               :windowStart, :windowEnd, :buffer, :days, :createdAt, :updatedAt)
            """.trimIndent(),
        )
            .settingParams(userKey, route)
            .param("createdAt", createdAt)
            .param("updatedAt", LocalDateTime.now())
            .update()
        return true
    }

    fun delete(userKey: String, routeId: String): Boolean =
        jdbc.sql("DELETE FROM commute_route WHERE user_key = :userKey AND route_id = :routeId")
            .param("userKey", userKey).param("routeId", routeId)
            .update() > 0

    fun deleteAll(userKey: String) {
        jdbc.sql("DELETE FROM commute_route WHERE user_key = :userKey")
            .param("userKey", userKey).update()
    }

    private fun JdbcClient.StatementSpec.settingParams(userKey: String, route: CommuteRoute): JdbcClient.StatementSpec {
        val setting = route.setting
        return this
            .param("userKey", userKey)
            .param("routeId", route.id)
            .param("label", route.label)
            .param("enabled", route.enabled)
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
    }

    private fun toRoute(rs: ResultSet): CommuteRoute {
        val windowStart = rs.getString("window_start")
        return CommuteRoute(
            id = rs.getString("route_id"),
            label = rs.getString("label"),
            enabled = rs.getBoolean("enabled"),
            setting = CommuteSetting(
                home = GeoPoint(rs.getDouble("home_latitude"), rs.getDouble("home_longitude")),
                stops = objectMapper.readValue(rs.getString("stops_json"), Array<CommuteStop>::class.java).toList(),
                walkMinutes = rs.getInt("walk_minutes"),
                notificationMode = NotificationMode.valueOf(rs.getString("notification_mode")),
                fixedDepartureTime = rs.getString("fixed_departure_time"),
                commuteWindow = windowStart?.let { CommuteWindow(it, rs.getString("window_end")) },
                bufferMinutes = rs.getInt("buffer_minutes"),
                activeDays = objectMapper.readValue(rs.getString("active_days_json"), Array<String>::class.java).toList(),
            ),
        )
    }
}
