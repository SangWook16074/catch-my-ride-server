package dev.hansw.catchmyride.push

import dev.hansw.catchmyride.api.ApiException
import dev.hansw.catchmyride.api.UserKeyResolver
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime

/**
 * API.md §4-1 — 스토어앱(catch_my_ride) FCM 푸시 토큰 등록·갱신.
 * 유저당 토큰 1개(PK user_key), 재등록은 최신 값으로 갱신 — 멱등이라 클라이언트가
 * 앱 시작·온보딩 저장·onTokenRefresh마다 불러도 안전하다.
 * 토큰이 등록된 유저는 스케줄러가 앱인토스 대신 FCM으로 발송한다 (PushNotificationScheduler).
 */
@RestController
class PushTokenController(
    private val repository: PushTokenRepository,
    private val userKeys: UserKeyResolver,
) {

    data class Request(val token: String, val platform: String)
    data class Response(val registered: Boolean)

    @PutMapping("/api/v1/push-token")
    fun put(
        @RequestHeader(value = "Authorization", required = false) auth: String?,
        @RequestBody request: Request,
    ): Response {
        if (request.token.isBlank() || request.token.length > 512) {
            throw ApiException.invalidRequest("token은 1~512자여야 합니다")
        }
        if (request.platform !in PLATFORMS) {
            throw ApiException.invalidRequest("platform은 IOS 또는 ANDROID여야 합니다")
        }
        repository.upsert(userKeys.resolve(auth), request.token.trim(), request.platform)
        return Response(registered = true)
    }

    companion object {
        private val PLATFORMS = setOf("IOS", "ANDROID")
    }
}

data class PushToken(val token: String, val platform: String)

@Repository
class PushTokenRepository(private val jdbc: JdbcClient) {

    fun find(userKey: String): PushToken? =
        jdbc.sql("SELECT token, platform FROM push_token WHERE user_key = :userKey")
            .param("userKey", userKey)
            .query { rs, _ -> PushToken(rs.getString("token"), rs.getString("platform")) }
            .optional().orElse(null)

    @Transactional
    fun upsert(userKey: String, token: String, platform: String) {
        jdbc.sql("DELETE FROM push_token WHERE user_key = :userKey")
            .param("userKey", userKey).update()
        jdbc.sql(
            """
            INSERT INTO push_token (user_key, token, platform, updated_at)
            VALUES (:userKey, :token, :platform, :updatedAt)
            """.trimIndent(),
        )
            .param("userKey", userKey)
            .param("token", token)
            .param("platform", platform)
            .param("updatedAt", LocalDateTime.now())
            .update()
    }

    /** FCM UNREGISTERED(앱 삭제·토큰 만료) — 다음 등록까지 미발송 (API.md §4-1) */
    fun delete(userKey: String) {
        jdbc.sql("DELETE FROM push_token WHERE user_key = :userKey")
            .param("userKey", userKey).update()
    }
}
