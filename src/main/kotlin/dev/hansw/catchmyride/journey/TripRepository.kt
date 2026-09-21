package dev.hansw.catchmyride.journey

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.LocalDateTime
import tools.jackson.databind.ObjectMapper

data class Trip(
    val tripId: String,
    val userKey: String,
    /** 저장 여정 id — 1회성 트립(§9-2 `POST /api/v1/trips`, FR-708)은 null */
    val journeyId: String?,
    val legs: List<JourneyLeg>,
    val legIndex: Int,
    val phase: TripPhase,
    val btrainNo: String?,
    val candidates: List<String>,
    /** 이 구간의 진행 방면 — 서버 판정(Heading.kt), 모르면 null(방면 필터 없이 자기선택으로 강등) */
    val heading: Heading? = null,
    val remainingStops: Int?,
    /** 열차 현재 위치 역명(상류 arvlMsg3) — 특정 후 목격 값, 모르면 null (§9-3 currentStop) */
    val currentStop: String? = null,
    val realtimeAvailable: Boolean,
    val legStartedAt: LocalDateTime,
    val lastSeenAt: LocalDateTime?,
    val startedAt: LocalDateTime,
) {
    val currentLeg: JourneyLeg get() = legs[legIndex]
    val isLastLeg: Boolean get() = legIndex >= legs.size - 1
}

@Repository
class TripRepository(
    private val jdbc: JdbcClient,
    private val objectMapper: ObjectMapper,
) {

    fun find(tripId: String): Trip? =
        jdbc.sql("SELECT * FROM trip WHERE trip_id = :tripId")
            .param("tripId", tripId)
            .query { rs, _ -> toTrip(rs) }
            .optional().orElse(null)

    fun findByUser(userKey: String): Trip? =
        jdbc.sql("SELECT * FROM trip WHERE user_key = :userKey")
            .param("userKey", userKey)
            .query { rs, _ -> toTrip(rs) }
            .optional().orElse(null)

    /**
     * 추적 대상 — TRACKING·ARRIVING + LOST(재목격 시 TRACKING 복구, §9-3 2026-09-15 실측 개정).
     * TRANSFER는 유저 수동 재개 대기, DONE만 종료 상태.
     */
    fun findActive(): List<Trip> =
        jdbc.sql("SELECT * FROM trip WHERE phase IN ('TRACKING', 'ARRIVING', 'LOST')")
            .query { rs, _ -> toTrip(rs) }
            .list()

    fun insert(trip: Trip, now: LocalDateTime) {
        jdbc.sql(
            """
            INSERT INTO trip (trip_id, user_key, journey_id, legs_json, leg_index, phase, btrain_no,
                              candidates_json, heading, remaining_stops, current_stop, realtime_available,
                              leg_started_at, last_seen_at, started_at, updated_at)
            VALUES (:tripId, :userKey, :journeyId, :legs, :legIndex, :phase, :btrainNo,
                    :candidates, :heading, :remaining, :currentStop, :realtime, :legStartedAt, :lastSeenAt, :startedAt, :now)
            """.trimIndent(),
        )
            .param("tripId", trip.tripId)
            .param("userKey", trip.userKey)
            .param("journeyId", trip.journeyId)
            .param("legs", objectMapper.writeValueAsString(trip.legs))
            .param("legIndex", trip.legIndex)
            .param("phase", trip.phase.name)
            .param("btrainNo", trip.btrainNo)
            .param("candidates", objectMapper.writeValueAsString(trip.candidates))
            .param("heading", trip.heading?.name)
            .param("remaining", trip.remainingStops)
            .param("currentStop", trip.currentStop)
            .param("realtime", trip.realtimeAvailable)
            .param("legStartedAt", trip.legStartedAt)
            .param("lastSeenAt", trip.lastSeenAt)
            .param("startedAt", trip.startedAt)
            .param("now", now)
            .update()
    }

    fun save(trip: Trip, now: LocalDateTime) {
        jdbc.sql(
            """
            UPDATE trip SET leg_index = :legIndex, phase = :phase, btrain_no = :btrainNo,
                            candidates_json = :candidates, heading = :heading, remaining_stops = :remaining,
                            current_stop = :currentStop, realtime_available = :realtime,
                            leg_started_at = :legStartedAt, last_seen_at = :lastSeenAt, updated_at = :now
            WHERE trip_id = :tripId
            """.trimIndent(),
        )
            .param("tripId", trip.tripId)
            .param("legIndex", trip.legIndex)
            .param("phase", trip.phase.name)
            .param("btrainNo", trip.btrainNo)
            .param("candidates", objectMapper.writeValueAsString(trip.candidates))
            .param("heading", trip.heading?.name)
            .param("remaining", trip.remainingStops)
            .param("currentStop", trip.currentStop)
            .param("realtime", trip.realtimeAvailable)
            .param("legStartedAt", trip.legStartedAt)
            .param("lastSeenAt", trip.lastSeenAt)
            .param("now", now)
            .update()
    }

    fun delete(tripId: String) {
        jdbc.sql("DELETE FROM trip_push_log WHERE trip_id = :tripId").param("tripId", tripId).update()
        jdbc.sql("DELETE FROM trip WHERE trip_id = :tripId").param("tripId", tripId).update()
    }

    fun deleteByJourney(userKey: String, journeyId: String) {
        findByUser(userKey)?.takeIf { it.journeyId == journeyId }?.let { delete(it.tripId) }
    }

    /** 종료 상태(DONE·LOST·TRANSFER 방치 포함)로 오래 남은 트립 정리 — §9-3 자동 정리 */
    fun deleteStale(before: LocalDateTime) {
        jdbc.sql(
            """
            DELETE FROM trip_push_log WHERE trip_id IN (SELECT trip_id FROM trip WHERE updated_at < :before)
            """.trimIndent(),
        ).param("before", before).update()
        jdbc.sql("DELETE FROM trip WHERE updated_at < :before").param("before", before).update()
    }

    // FR-704 — 이벤트당 최대 2회. PK 중복 = 이미 발송 판단
    fun pushLogged(tripId: String, legIndex: Int, stage: TripPushStage): Boolean =
        jdbc.sql(
            "SELECT COUNT(*) FROM trip_push_log WHERE trip_id = :tripId AND leg_index = :legIndex AND stage = :stage",
        )
            .param("tripId", tripId)
            .param("legIndex", legIndex)
            .param("stage", stage.name)
            .query(Int::class.java).single() > 0

    fun recordPush(tripId: String, legIndex: Int, stage: TripPushStage, delivered: Boolean, now: LocalDateTime) {
        jdbc.sql(
            """
            INSERT INTO trip_push_log (trip_id, leg_index, stage, delivered, sent_at)
            VALUES (:tripId, :legIndex, :stage, :delivered, :now)
            """.trimIndent(),
        )
            .param("tripId", tripId)
            .param("legIndex", legIndex)
            .param("stage", stage.name)
            .param("delivered", delivered)
            .param("now", now)
            .update()
    }

    fun rollbackPush(tripId: String, legIndex: Int, stage: TripPushStage) {
        jdbc.sql(
            "DELETE FROM trip_push_log WHERE trip_id = :tripId AND leg_index = :legIndex AND stage = :stage",
        )
            .param("tripId", tripId)
            .param("legIndex", legIndex)
            .param("stage", stage.name)
            .update()
    }

    private fun toTrip(rs: ResultSet): Trip = Trip(
        tripId = rs.getString("trip_id"),
        userKey = rs.getString("user_key"),
        journeyId = rs.getString("journey_id"),
        legs = objectMapper.readValue(rs.getString("legs_json"), Array<JourneyLeg>::class.java).toList(),
        legIndex = rs.getInt("leg_index"),
        phase = TripPhase.valueOf(rs.getString("phase")),
        btrainNo = rs.getString("btrain_no"),
        candidates = objectMapper.readValue(rs.getString("candidates_json"), Array<String>::class.java).toList(),
        heading = rs.getString("heading")?.let { Heading.valueOf(it) },
        remainingStops = rs.getObject("remaining_stops")?.let { (it as Number).toInt() },
        currentStop = rs.getString("current_stop"),
        realtimeAvailable = rs.getBoolean("realtime_available"),
        legStartedAt = rs.getTimestamp("leg_started_at").toLocalDateTime(),
        lastSeenAt = rs.getTimestamp("last_seen_at")?.toLocalDateTime(),
        startedAt = rs.getTimestamp("started_at").toLocalDateTime(),
    )
}
