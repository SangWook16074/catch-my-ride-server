package dev.hansw.catchmyride.journey

import dev.hansw.catchmyride.api.ApiException
import dev.hansw.catchmyride.api.UserKeyResolver
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.sql.ResultSet
import java.time.Clock
import java.time.LocalDateTime

/**
 * §9-5 진행 표면 원격 갱신 (API.md v0.12) — Live Activity push token 등록·저장·변화 감지.
 * 실제 발송은 [TripSurfaceUpdater](iOS는 ApnsClient, Android는 기존 FcmPushClient)가 맡는다.
 */
data class SurfaceToken(val platform: String, val token: String)

@Repository
class TripSurfaceTokenRepository(private val jdbc: JdbcClient) {

    fun find(tripId: String): SurfaceToken? =
        jdbc.sql("SELECT platform, token FROM trip_surface_token WHERE trip_id = :tripId")
            .param("tripId", tripId)
            .query { rs, _ -> SurfaceToken(rs.getString("platform"), rs.getString("token")) }
            .optional().orElse(null)

    @Transactional
    fun upsert(tripId: String, platform: String, token: String, now: LocalDateTime) {
        jdbc.sql("DELETE FROM trip_surface_token WHERE trip_id = :tripId").param("tripId", tripId).update()
        jdbc.sql(
            """
            INSERT INTO trip_surface_token (trip_id, platform, token, updated_at)
            VALUES (:tripId, :platform, :token, :now)
            """.trimIndent(),
        )
            .param("tripId", tripId)
            .param("platform", platform)
            .param("token", token)
            .param("now", now)
            .update()
    }

    /** APNs 410/BadDeviceToken — 만료 토큰 폐기 (§9-5, 트립당 FCM 토큰 폐기와 동일 규칙) */
    fun delete(tripId: String) {
        jdbc.sql("DELETE FROM trip_surface_token WHERE trip_id = :tripId").param("tripId", tripId).update()
    }
}

/**
 * 직전 발송값(변화 감지) + 분당 발송 카운터(상한 6건, §9-5) — 트립마다 한 행.
 * `sentCount`/`windowStartedAt`은 고정 1분 윈도우: 윈도우가 지났으면 0부터 다시 센다.
 */
data class TripSurfaceState(
    val lastPhase: String?,
    val lastLegIndex: Int?,
    val lastRemainingStops: Int?,
    val lastCurrentStop: String?,
    val lastEventStop: String?,
    val lastRealtimeAvailable: Boolean?,
    val sentCount: Int,
    val windowStartedAt: LocalDateTime?,
) {
    fun differsFrom(values: SurfaceValues): Boolean =
        lastPhase != values.phase || lastLegIndex != values.legIndex || lastRemainingStops != values.remainingStops ||
            lastCurrentStop != values.currentStop || lastEventStop != values.eventStop ||
            lastRealtimeAvailable != values.realtimeAvailable
}

@Repository
class TripSurfaceStateRepository(private val jdbc: JdbcClient) {

    fun find(tripId: String): TripSurfaceState? =
        jdbc.sql("SELECT * FROM trip_surface_state WHERE trip_id = :tripId")
            .param("tripId", tripId)
            .query { rs, _ -> toState(rs) }
            .optional().orElse(null)

    @Transactional
    fun upsert(tripId: String, state: TripSurfaceState, now: LocalDateTime) {
        jdbc.sql("DELETE FROM trip_surface_state WHERE trip_id = :tripId").param("tripId", tripId).update()
        jdbc.sql(
            """
            INSERT INTO trip_surface_state
              (trip_id, last_phase, last_leg_index, last_remaining_stops, last_current_stop, last_event_stop,
               last_realtime_available, sent_count, window_started_at, updated_at)
            VALUES (:tripId, :lastPhase, :lastLegIndex, :lastRemaining, :lastCurrentStop, :lastEventStop,
                    :lastRealtime, :sentCount, :windowStartedAt, :now)
            """.trimIndent(),
        )
            .param("tripId", tripId)
            .param("lastPhase", state.lastPhase)
            .param("lastLegIndex", state.lastLegIndex)
            .param("lastRemaining", state.lastRemainingStops)
            .param("lastCurrentStop", state.lastCurrentStop)
            .param("lastEventStop", state.lastEventStop)
            .param("lastRealtime", state.lastRealtimeAvailable)
            .param("sentCount", state.sentCount)
            .param("windowStartedAt", state.windowStartedAt)
            .param("now", now)
            .update()
    }

    fun delete(tripId: String) {
        jdbc.sql("DELETE FROM trip_surface_state WHERE trip_id = :tripId").param("tripId", tripId).update()
    }

    private fun toState(rs: ResultSet): TripSurfaceState = TripSurfaceState(
        lastPhase = rs.getString("last_phase"),
        lastLegIndex = rs.getObject("last_leg_index")?.let { (it as Number).toInt() },
        lastRemainingStops = rs.getObject("last_remaining_stops")?.let { (it as Number).toInt() },
        lastCurrentStop = rs.getString("last_current_stop"),
        lastEventStop = rs.getString("last_event_stop"),
        lastRealtimeAvailable = rs.getObject("last_realtime_available") as Boolean?,
        sentCount = rs.getInt("sent_count"),
        windowStartedAt = rs.getTimestamp("window_started_at")?.toLocalDateTime(),
    )
}

/** §9-5 "표면에 보이는 값" — 이 중 하나라도 바뀌면 원격 갱신을 보낸다 (같은 값 반복 발송 금지) */
data class SurfaceValues(
    val phase: String,
    val legIndex: Int,
    val remainingStops: Int?,
    val currentStop: String?,
    val eventStop: String,
    val realtimeAvailable: Boolean,
)

fun Trip.surfaceValues(): SurfaceValues = SurfaceValues(
    phase = phase.name,
    legIndex = legIndex,
    remainingStops = remainingStops,
    currentStop = currentStop,
    eventStop = currentLeg.alightStop,
    realtimeAvailable = realtimeAvailable,
)

/** API.md §9-5 `PUT /api/v1/trips/{tripId}/surface-token` */
@RestController
class TripSurfaceController(
    private val trips: TripRepository,
    private val surfaceTokens: TripSurfaceTokenRepository,
    private val userKeys: UserKeyResolver,
    private val clock: Clock,
) {

    data class SurfaceTokenRequest(val platform: String?, val token: String?)

    @PutMapping("/api/v1/trips/{tripId}/surface-token")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun put(
        @RequestHeader(value = "Authorization", required = false) auth: String?,
        @PathVariable tripId: String,
        @RequestBody request: SurfaceTokenRequest,
    ) {
        val userKey = userKeys.resolve(auth)
        val trip = trips.find(tripId)
        // DONE·삭제면 404(무시해도 된다, §9-5) — 토큰은 그 트립에만 귀속되고 트립과 함께 지운다
        if (trip == null || trip.userKey != userKey || trip.phase == TripPhase.DONE) {
            throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "존재하지 않는 트립입니다")
        }
        val platform = request.platform?.trim().orEmpty()
        val token = request.token?.trim().orEmpty()
        if (platform.isEmpty() || token.isEmpty() || token.length > 512) {
            throw ApiException.invalidRequest("platform·token을 입력해야 합니다")
        }
        surfaceTokens.upsert(tripId, platform, token, LocalDateTime.now(clock))
    }
}
