package dev.hansw.catchmyride.commute

import dev.hansw.catchmyride.ApiContractTestSupport.request
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.init.ScriptUtils
import tools.jackson.databind.ObjectMapper
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * API.md §1-2 계약 검증 — 다중 통근 경로 CRUD(최대 5개)와 레거시 §1 어댑터,
 * 그리고 schema.sql의 단일 설정 → '출근' 경로 이관(멱등)까지 통으로 확인한다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["DATA_GO_KR_KEY=", "SEOUL_OPEN_DATA_KEY="],
)
class CommuteRouteApiTest {

    @Autowired lateinit var environment: Environment
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var dataSource: DataSource
    @Autowired lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun wipe() {
        // 공유 H2 — 기본 dev-user 데이터가 다른 테스트와 섞이지 않게 비운다
        jdbc.sql("DELETE FROM commute_route WHERE user_key = 'dev-user'").update()
        jdbc.sql("DELETE FROM commute_setting WHERE user_key = 'dev-user'").update()
    }

    private fun setting(departure: String = "08:20") = """
        {
          "home": {"latitude": 37.5219, "longitude": 126.9245},
          "stops": [{"type": "SUBWAY", "stopId": "여의도", "displayName": "여의도역", "routes": ["9호선 급행"]}],
          "walkMinutes": 8,
          "notificationMode": "FIXED",
          "fixedDepartureTime": "$departure",
          "commuteWindow": null,
          "bufferMinutes": 3,
          "activeDays": ["MON", "TUE", "WED", "THU", "FRI"]
        }
    """.trimIndent()

    private fun routeBody(label: String, departure: String = "08:20", enabled: Boolean = true) =
        """{"label": "$label", "enabled": $enabled, "setting": ${setting(departure)}}"""

    private fun idOf(body: String): String = objectMapper.readTree(body).get("id").asText()

    @Test
    fun `경로 생성-목록-수정-삭제 왕복`() {
        val created = request(environment, "POST", "/api/v1/commute-routes", routeBody("출근"))
        assertEquals(201, created.statusCode(), created.body())
        val goId = idOf(created.body())

        val second = request(environment, "POST", "/api/v1/commute-routes", routeBody("퇴근", departure = "18:30"))
        assertEquals(201, second.statusCode(), second.body())

        val list = request(environment, "GET", "/api/v1/commute-routes")
        assertEquals(200, list.statusCode(), list.body())
        assertTrue(list.body().indexOf("출근") < list.body().indexOf("퇴근"), "생성순 정렬: ${list.body()}")

        val updated = request(
            environment, "PUT", "/api/v1/commute-routes/$goId",
            routeBody("출근길", enabled = false),
        )
        assertEquals(200, updated.statusCode(), updated.body())
        assertTrue(updated.body().contains("\"enabled\":false"), updated.body())

        val deleted = request(environment, "DELETE", "/api/v1/commute-routes/$goId")
        assertEquals(204, deleted.statusCode())
        val after = request(environment, "GET", "/api/v1/commute-routes")
        assertTrue(!after.body().contains("출근길") && after.body().contains("퇴근"), after.body())
    }

    @Test
    fun `경로는 최대 5개, 초과 생성은 400`() {
        repeat(5) { i ->
            val r = request(environment, "POST", "/api/v1/commute-routes", routeBody("경로$i"))
            assertEquals(201, r.statusCode(), r.body())
        }
        val over = request(environment, "POST", "/api/v1/commute-routes", routeBody("여섯번째"))
        assertEquals(400, over.statusCode())
        assertTrue(over.body().contains("최대 5개"), over.body())
    }

    @Test
    fun `중복 라벨과 빈 라벨은 400`() {
        request(environment, "POST", "/api/v1/commute-routes", routeBody("출근"))
        val dup = request(environment, "POST", "/api/v1/commute-routes", routeBody("출근"))
        assertEquals(400, dup.statusCode())
        assertTrue(dup.body().contains("같은 이름의 경로"), dup.body())

        val blank = request(environment, "POST", "/api/v1/commute-routes", routeBody("  "))
        assertEquals(400, blank.statusCode())
        assertTrue(blank.body().contains("경로 이름"), blank.body())
    }

    @Test
    fun `없는 경로 수정·삭제는 404`() {
        val put = request(environment, "PUT", "/api/v1/commute-routes/no-such-id", routeBody("출근"))
        assertEquals(404, put.statusCode(), put.body())
        val del = request(environment, "DELETE", "/api/v1/commute-routes/no-such-id")
        assertEquals(404, del.statusCode(), del.body())
    }

    @Test
    fun `레거시 §1 어댑터 - PUT commute-setting은 '출근' 경로를 만들고 첫 경로와 왕복한다`() {
        val put = request(environment, "PUT", "/api/v1/commute-setting", setting())
        assertEquals(200, put.statusCode(), put.body())

        val list = request(environment, "GET", "/api/v1/commute-routes")
        assertTrue(list.body().contains("\"label\":\"출근\""), "레거시 PUT은 출근 경로 생성: ${list.body()}")

        // 레거시 PUT 재호출은 새 경로를 만들지 않고 첫 경로를 갱신한다
        val put2 = request(environment, "PUT", "/api/v1/commute-setting", setting(departure = "09:00"))
        assertEquals(200, put2.statusCode())
        val routes = objectMapper.readTree(request(environment, "GET", "/api/v1/commute-routes").body()).get("routes")
        assertEquals(1, routes.size())
        assertEquals("09:00", routes.get(0).get("setting").get("fixedDepartureTime").asText())

        val get = request(environment, "GET", "/api/v1/commute-setting")
        assertEquals(200, get.statusCode())
        assertTrue(get.body().contains("\"fixedDepartureTime\":\"09:00\""), get.body())

        val delete = request(environment, "DELETE", "/api/v1/commute-setting")
        assertEquals(204, delete.statusCode())
        assertEquals(404, request(environment, "GET", "/api/v1/commute-setting").statusCode())
    }

    @Test
    fun `schema sql 이관 - 기존 단일 설정이 '출근' 경로가 되고 재실행해도 늘지 않는다`() {
        val legacyUser = "migrate-user"
        jdbc.sql("DELETE FROM commute_route WHERE user_key = :u").param("u", legacyUser).update()
        jdbc.sql("DELETE FROM commute_setting WHERE user_key = :u").param("u", legacyUser).update()
        jdbc.sql(
            """
            INSERT INTO commute_setting
              (user_key, home_latitude, home_longitude, stops_json, walk_minutes, notification_mode,
               fixed_departure_time, window_start, window_end, buffer_minutes, active_days_json, updated_at)
            VALUES (:u, 37.5, 126.9, '[]', 8, 'FIXED', '08:20', NULL, NULL, 3, '["MON"]', CURRENT_TIMESTAMP)
            """.trimIndent(),
        ).param("u", legacyUser).update()

        repeat(2) { // 멱등 — 부팅마다 실행돼도 안전해야 한다
            dataSource.connection.use { conn ->
                ScriptUtils.executeSqlScript(conn, ClassPathResource("schema.sql"))
            }
        }

        val migrated = jdbc.sql("SELECT route_id, label, enabled FROM commute_route WHERE user_key = :u")
            .param("u", legacyUser)
            .query { rs, _ -> Triple(rs.getString("route_id"), rs.getString("label"), rs.getBoolean("enabled")) }
            .list()
        assertEquals(1, migrated.size, "이관은 정확히 1행: $migrated")
        assertEquals(Triple("migrated", "출근", true), migrated[0])
    }
}
