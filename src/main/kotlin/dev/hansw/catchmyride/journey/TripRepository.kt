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
    /**
     * 유저가 "내가 탄 열차가 아니에요"로 물린 열차 번호들 (§9-3 다시 잡기, 2026-09-30 오너 요청) —
     * 다시 잡을 때 이 열차는 후보에서 뺀다. 안 그러면 방금 물린 열차를 그대로 다시 특정한다.
     * 구간이 바뀌면(환승) 비운다 — 다른 노선의 열차 번호와 섞을 이유가 없다
     */
    val rejectedTrains: List<String> = emptyList(),
    /**
     * 구간 중간에서 시작한 트립의 **유저 탑승 위치 역** — 위치(§9-2)로 판정됐을 때만 채워진다.
     * null = 탑승역에서 시작(기존 경로). 채워져 있으면 후보 재수집을 탑승역 전광판에서 하지
     * 않는다 — 그건 유저 뒤에 오는 열차다 (2026-09-24)
     */
    val seedStop: String? = null,
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
                              candidates_json, rejected_trains_json, seed_stop, heading, remaining_stops, current_stop, realtime_available,
                              leg_started_at, last_seen_at, started_at, updated_at)
            VALUES (:tripId, :userKey, :journeyId, :legs, :legIndex, :phase, :btrainNo,
                    :candidates, :rejected, :seedStop, :heading, :remaining, :currentStop, :realtime, :legStartedAt, :lastSeenAt, :startedAt, :now)
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
            .param("rejected", objectMapper.writeValueAsString(trip.rejectedTrains))
            .param("seedStop", trip.seedStop)
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

    /**
     * @param touch false면 `updated_at`을 올리지 않는다 — 목격이 끊긴 채 상태만 갱신할 때 쓴다.
     * 방치 트립 자동 정리(STALE_AFTER)가 **마지막으로 실제 진전이 있던 시점**부터 재게 하려면
     * 여기서 시계를 올리지 않아야 한다 (LOST가 저장을 건너뛰던 것과 같은 이유, 2026-09-30)
     */
    fun save(trip: Trip, now: LocalDateTime, touch: Boolean = true) {
        jdbc.sql(
            """
            UPDATE trip SET leg_index = :legIndex, phase = :phase, btrain_no = :btrainNo,
                            candidates_json = :candidates, rejected_trains_json = :rejected, seed_stop = :seedStop,
                            heading = :heading, remaining_stops = :remaining,
                            current_stop = :currentStop, realtime_available = :realtime,
                            leg_started_at = :legStartedAt, last_seen_at = :lastSeenAt
                            ${if (touch) ", updated_at = :now" else ""}
            WHERE trip_id = :tripId
            """.trimIndent(),
        )
            .param("tripId", trip.tripId)
            .param("legIndex", trip.legIndex)
            .param("phase", trip.phase.name)
            .param("btrainNo", trip.btrainNo)
            .param("candidates", objectMapper.writeValueAsString(trip.candidates))
            .param("rejected", objectMapper.writeValueAsString(trip.rejectedTrains))
            .param("seedStop", trip.seedStop)
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

    /**
     * 이 구간의 발송 기록 삭제 — **유저가 "내가 탄 열차가 아니에요"를 누른 경우에만** 쓴다.
     * 잘못 잡은 열차로 나간 예고·하차 알림은 무효라, 제대로 잡은 뒤 다시 2회까지 보낼 수 있어야 한다
     * (FR-704의 "이벤트당 2회"는 유지 — 유저가 직접 리셋한 이벤트에 한해 다시 센다, 2026-09-30)
     */
    fun clearPushLog(tripId: String, legIndex: Int) {
        jdbc.sql("DELETE FROM trip_push_log WHERE trip_id = :tripId AND leg_index = :legIndex")
            .param("tripId", tripId)
            .param("legIndex", legIndex)
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
        rejectedTrains = rs.getString("rejected_trains_json")
            ?.let { objectMapper.readValue(it, Array<String>::class.java).toList() }
            .orEmpty(), // 2026-09-30 이전 트립은 null

        seedStop = rs.getString("seed_stop"),
        heading = rs.getString("heading")?.let { Heading.valueOf(it) },
        remainingStops = rs.getObject("remaining_stops")?.let { (it as Number).toInt() },
        currentStop = rs.getString("current_stop"),
        realtimeAvailable = rs.getBoolean("realtime_available"),
        legStartedAt = rs.getTimestamp("leg_started_at").toLocalDateTime(),
        lastSeenAt = rs.getTimestamp("last_seen_at")?.toLocalDateTime(),
        startedAt = rs.getTimestamp("started_at").toLocalDateTime(),
    )
}
