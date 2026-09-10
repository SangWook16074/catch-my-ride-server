package dev.hansw.catchmyride.push

import dev.hansw.catchmyride.ApiContractTestSupport.request
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.simple.JdbcClient
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * API.md §4-1 — 스토어앱 FCM 푸시 토큰 등록 계약.
 * 유저당 1개, 재등록은 최신 값으로 갱신(멱등) — 클라이언트가 시작·저장·onTokenRefresh마다 불러도 안전해야 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PushTokenApiTest {

    @Autowired lateinit var environment: Environment
    @Autowired lateinit var repository: PushTokenRepository
    @Autowired lateinit var jdbc: JdbcClient

    // 헤더 없는 요청은 auth.dev-user-key로 식별된다 (테스트 설정)
    private val devUserKey = "dev-user"

    @BeforeEach
    fun wipe() {
        jdbc.sql("DELETE FROM push_token").update()
    }

    @Test
    fun `토큰을 등록하고 재등록하면 최신 값으로 갱신된다`() {
        val first = request(environment, "PUT", "/api/v1/push-token", """{"token":"fcm-token-1","platform":"IOS"}""")
        assertEquals(200, first.statusCode(), first.body())
        assertTrue(first.body().contains("\"registered\":true"), first.body())
        assertEquals(PushToken("fcm-token-1", "IOS"), repository.find(devUserKey))

        val second = request(environment, "PUT", "/api/v1/push-token", """{"token":"fcm-token-2","platform":"ANDROID"}""")
        assertEquals(200, second.statusCode(), second.body())
        assertEquals(PushToken("fcm-token-2", "ANDROID"), repository.find(devUserKey))
    }

    @Test
    fun `빈 토큰과 알 수 없는 platform은 400이다`() {
        val blank = request(environment, "PUT", "/api/v1/push-token", """{"token":"","platform":"IOS"}""")
        assertEquals(400, blank.statusCode(), blank.body())
        assertTrue(blank.body().contains("INVALID_REQUEST"), blank.body())

        val platform = request(environment, "PUT", "/api/v1/push-token", """{"token":"fcm-token","platform":"WEB"}""")
        assertEquals(400, platform.statusCode(), platform.body())
        assertNull(repository.find(devUserKey))
    }

    @Test
    fun `UNREGISTERED 폐기용 delete는 등록을 지운다`() {
        repository.upsert(devUserKey, "fcm-token", "IOS")
        repository.delete(devUserKey)
        assertNull(repository.find(devUserKey))
    }
}
