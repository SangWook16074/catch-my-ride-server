package dev.hansw.catchmyride.journey

import dev.hansw.catchmyride.ApiContractTestSupport.request
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.simple.JdbcClient
import kotlin.test.assertEquals

/** API.md §9-5 `PUT /api/v1/trips/{tripId}/surface-token` (v0.12) 계약 테스트 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TripSurfaceTokenApiTest {

    @Autowired lateinit var environment: Environment
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var surfaceTokens: TripSurfaceTokenRepository

    @BeforeEach
    fun wipe() {
        jdbc.sql("DELETE FROM trip_push_log").update()
        jdbc.sql("DELETE FROM trip_surface_token").update()
        jdbc.sql("DELETE FROM trip").update()
        jdbc.sql("DELETE FROM journey").update()
    }

    private fun startTrip(): String {
        val started = request(
            environment, "POST", "/api/v1/trips",
            """{"legs":[{"type":"SUBWAY","line":"9호선 급행","boardStop":"여의도","alightStop":"당산"}]}""",
        )
        assertEquals(201, started.statusCode(), started.body())
        return Regex("\"tripId\":\"([^\"]+)\"").find(started.body())!!.groupValues[1]
    }

    @Test
    fun `토큰을 등록하면 204이고 저장된다`() {
        val tripId = startTrip()

        val response = request(
            environment, "PUT", "/api/v1/trips/$tripId/surface-token",
            """{"platform":"IOS","token":"activity-push-token-abc"}""",
        )

        assertEquals(204, response.statusCode(), response.body())
        val saved = surfaceTokens.find(tripId)
        assertEquals("IOS", saved?.platform)
        assertEquals("activity-push-token-abc", saved?.token)
    }

    @Test
    fun `존재하지 않는 트립은 404`() {
        val response = request(
            environment, "PUT", "/api/v1/trips/no-such-trip/surface-token",
            """{"platform":"IOS","token":"x"}""",
        )
        assertEquals(404, response.statusCode())
    }

    @Test
    fun `DONE 트립은 404 — 무시해도 된다`() {
        val tripId = startTrip()
        jdbc.sql("UPDATE trip SET phase = 'DONE' WHERE trip_id = :tripId").param("tripId", tripId).update()

        val response = request(
            environment, "PUT", "/api/v1/trips/$tripId/surface-token",
            """{"platform":"IOS","token":"x"}""",
        )

        assertEquals(404, response.statusCode())
    }

    @Test
    fun `재등록은 갱신(멱등)`() {
        val tripId = startTrip()
        request(environment, "PUT", "/api/v1/trips/$tripId/surface-token", """{"platform":"IOS","token":"old"}""")

        val response = request(environment, "PUT", "/api/v1/trips/$tripId/surface-token", """{"platform":"IOS","token":"new"}""")

        assertEquals(204, response.statusCode())
        assertEquals("new", surfaceTokens.find(tripId)?.token)
    }

    @Test
    fun `platform·token이 비면 400`() {
        val tripId = startTrip()

        val response = request(environment, "PUT", "/api/v1/trips/$tripId/surface-token", """{"platform":"","token":""}""")

        assertEquals(400, response.statusCode())
    }
}
