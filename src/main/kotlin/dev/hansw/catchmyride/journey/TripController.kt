package dev.hansw.catchmyride.journey

import dev.hansw.catchmyride.api.ApiException
import dev.hansw.catchmyride.api.UserKeyResolver
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * API.md §9-2/9-3 — 트립 시작·상태·환승 재개·종료.
 * GET은 저장된 추적 상태를 읽기만 한다 — 전진은 TripTrackingScheduler가 담당 (서버 권위).
 */
@RestController
class TripController(
    private val trips: TripRepository,
    private val journeys: JourneyRepository,
    private val trains: TrainPositions,
    private val userKeys: UserKeyResolver,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    data class StartResponse(val tripId: String, val startedAt: String)
    data class StatusResponse(
        val phase: String,
        val legIndex: Int,
        val remainingStops: Int?,
        val nextStop: String?,
        val eventStop: String,
        val realtimeAvailable: Boolean,
        val fetchedAt: String,
    )

    @PostMapping("/api/v1/journeys/{journeyId}/trips")
    @ResponseStatus(HttpStatus.CREATED)
    fun start(
        @RequestHeader(value = "Authorization", required = false) auth: String?,
        @PathVariable journeyId: String,
    ): StartResponse {
        val userKey = userKeys.resolve(auth)
        val journey = journeys.find(userKey, journeyId) ?: throw ApiException.settingNotFound()
        trips.findByUser(userKey)?.let {
            throw ApiException.invalidRequest("진행 중인 트립이 있습니다: ${it.tripId}")
        }
        val now = LocalDateTime.now(clock)
        val firstLeg = journey.legs.first()
        val trip = Trip(
            tripId = UUID.randomUUID().toString(),
            userKey = userKey,
            journeyId = journeyId,
            legs = journey.legs,
            legIndex = 0,
            phase = TripPhase.TRACKING,
            btrainNo = null,
            candidates = identifyCandidates(firstLeg),
            remainingStops = null,
            realtimeAvailable = true,
            legStartedAt = now,
            lastSeenAt = null,
            startedAt = now,
        )
        trips.insert(trip, now)
        journeys.touchLastUsed(userKey, journeyId, now) // 히스토리 정렬 키 (§9-2)
        return StartResponse(tripId = trip.tripId, startedAt = trip.startedAt.format(ISO))
    }

    @GetMapping("/api/v1/trips/{tripId}")
    fun status(
        @RequestHeader(value = "Authorization", required = false) auth: String?,
        @PathVariable tripId: String,
    ): StatusResponse {
        val trip = findOwned(auth, tripId)
        return StatusResponse(
            phase = trip.phase.name,
            legIndex = trip.legIndex,
            remainingStops = trip.remainingStops,
            nextStop = null, // v1 미제공 — 열차 현재 위치 역명은 실측 후 (API.md §9-3)
            eventStop = trip.currentLeg.alightStop,
            realtimeAvailable = trip.realtimeAvailable,
            fetchedAt = LocalDateTime.now(clock).format(ISO),
        )
    }

    /** 환승 후 다음 구간 수동 재개 (FR-703) — TRANSFER에서만 */
    @PostMapping("/api/v1/trips/{tripId}/next-leg")
    fun nextLeg(
        @RequestHeader(value = "Authorization", required = false) auth: String?,
        @PathVariable tripId: String,
    ): StatusResponse {
        val trip = findOwned(auth, tripId)
        if (trip.phase != TripPhase.TRANSFER) {
            throw ApiException.invalidRequest("환승 대기 상태가 아닙니다")
        }
        val now = LocalDateTime.now(clock)
        val nextIndex = trip.legIndex + 1
        val nextLeg = trip.legs[nextIndex]
        val resumed = trip.copy(
            legIndex = nextIndex,
            phase = TripPhase.TRACKING,
            btrainNo = null,
            candidates = identifyCandidates(nextLeg),
            remainingStops = null,
            realtimeAvailable = true,
            legStartedAt = now,
            lastSeenAt = null,
        )
        trips.save(resumed, now)
        return StatusResponse(
            phase = resumed.phase.name,
            legIndex = resumed.legIndex,
            remainingStops = null,
            nextStop = null,
            eventStop = nextLeg.alightStop,
            realtimeAvailable = true,
            fetchedAt = now.format(ISO),
        )
    }

    /** 완료·중도 취소 공용, 멱등 (§9-3) */
    @DeleteMapping("/api/v1/trips/{tripId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun end(
        @RequestHeader(value = "Authorization", required = false) auth: String?,
        @PathVariable tripId: String,
    ) {
        val userKey = userKeys.resolve(auth)
        val trip = trips.find(tripId) ?: return // 멱등 — 이미 없어도 204
        if (trip.userKey == userKey) {
            trips.delete(tripId)
        }
    }

    private fun findOwned(auth: String?, tripId: String): Trip {
        val userKey = userKeys.resolve(auth)
        val trip = trips.find(tripId)
        if (trip == null || trip.userKey != userKey) {
            throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "존재하지 않는 트립입니다")
        }
        return trip
    }

    /**
     * 탑승역에서 지금 도착·출발 중인 열차 후보 — 하차역 목록에 이 중 하나가 나타나면
     * 그 열차가 우리 열차다(방향 자기선택). 상류 실패는 빈 후보 — 특정 실패 시 LOST (FR-706)
     */
    private fun identifyCandidates(leg: JourneyLeg): List<String> = try {
        trains.approaching(leg.boardStop)
            .filter { it.matchesLine(leg.line) }
            .filter { (it.stationsAway ?: Int.MAX_VALUE) == 0 || (it.secondsToArrival ?: Int.MAX_VALUE) <= BOARD_WINDOW_SECONDS }
            .map { it.trainNo }
            .distinct()
    } catch (e: Exception) {
        log.warn("탑승 후보 열차 조회 실패 — board={}: {}", leg.boardStop, e.message)
        emptyList()
    }

    companion object {
        private val ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME

        /** 유저가 "시작"을 누르는 시점 = 탑승 직후 — 도착 임박·직전 도착 열차만 후보로 본다 */
        private const val BOARD_WINDOW_SECONDS = 120
    }
}
