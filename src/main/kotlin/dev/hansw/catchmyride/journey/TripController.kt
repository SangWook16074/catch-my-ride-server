package dev.hansw.catchmyride.journey

import dev.hansw.catchmyride.api.ApiException
import dev.hansw.catchmyride.api.UserKeyResolver
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * API.md §9-2/9-3 — 트립 시작(저장 여정 / 1회성 인라인 구간)·상태·환승 재개·종료.
 * GET은 저장된 추적 상태를 읽기만 한다 — 전진은 TripTrackingScheduler가 담당 (서버 권위).
 */
@RestController
class TripController(
    private val trips: TripRepository,
    private val journeys: JourneyRepository,
    private val trains: TrainPositions,
    private val stationIds: StationIdCache,
    private val legValidator: JourneyLegValidator,
    private val userKeys: UserKeyResolver,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    data class StartResponse(val tripId: String, val startedAt: String)
    data class QuickStartRequest(val legs: List<LegRequest>?)
    data class StatusResponse(
        val phase: String,
        val legIndex: Int,
        val remainingStops: Int?,
        val currentStop: String?,
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
        val trip = startTrip(userKey, journeyId = journeyId, legs = journey.legs)
        journeys.touchLastUsed(userKey, journeyId, trip.startedAt) // 히스토리 정렬 키 (§9-2)
        return StartResponse(tripId = trip.tripId, startedAt = trip.startedAt.format(ISO))
    }

    /**
     * 여정 비귀속 1회성 트립 시작 (§9-2 `POST /api/v1/trips`, FR-708) — 저장 없이 인라인 구간으로
     * 바로 추적한다. 검증·동시 1개 규칙은 저장 여정과 동일, 여정 히스토리(lastUsedAt)에는 비귀속.
     * 트립 레코드가 legs 스냅숏을 들고 있으므로 추적 엔진·푸시는 그대로 재사용된다.
     */
    @PostMapping("/api/v1/trips")
    @ResponseStatus(HttpStatus.CREATED)
    fun quickStart(
        @RequestHeader(value = "Authorization", required = false) auth: String?,
        @RequestBody request: QuickStartRequest,
    ): StartResponse {
        val userKey = userKeys.resolve(auth)
        val legs = legValidator.validate(request.legs)
        val trip = startTrip(userKey, journeyId = null, legs = legs)
        return StartResponse(tripId = trip.tripId, startedAt = trip.startedAt.format(ISO))
    }

    private fun startTrip(userKey: String, journeyId: String?, legs: List<JourneyLeg>): Trip {
        trips.findByUser(userKey)?.let {
            throw ApiException.invalidRequest("진행 중인 트립이 있습니다: ${it.tripId}")
        }
        val now = LocalDateTime.now(clock)
        val seed = seedLeg(legs.first())
        val trip = Trip(
            tripId = UUID.randomUUID().toString(),
            userKey = userKey,
            journeyId = journeyId,
            legs = legs,
            legIndex = 0,
            phase = TripPhase.TRACKING,
            btrainNo = null,
            candidates = seed.candidates,
            heading = seed.heading,
            remainingStops = null,
            realtimeAvailable = true,
            legStartedAt = now,
            lastSeenAt = null,
            startedAt = now,
        )
        trips.insert(trip, now)
        return trip
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
            currentStop = trip.currentStop, // 열차 현재 위치 역명(상류 arvlMsg3) — 모르면 null (API.md §9-3)
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
        val seed = seedLeg(nextLeg)
        val resumed = trip.copy(
            legIndex = nextIndex,
            phase = TripPhase.TRACKING,
            btrainNo = null,
            candidates = seed.candidates,
            heading = seed.heading, // 새 구간 — 방면도 새로 판정 (환승 후 반대 방면 문제의 핵심)
            remainingStops = null,
            currentStop = null, // 새 구간 — 이전 구간의 위치 역명을 이월하지 않는다
            realtimeAvailable = true,
            legStartedAt = now,
            lastSeenAt = null,
        )
        trips.save(resumed, now)
        return StatusResponse(
            phase = resumed.phase.name,
            legIndex = resumed.legIndex,
            remainingStops = null,
            currentStop = null,
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

    private data class LegSeed(val heading: Heading?, val candidates: List<String>)

    /**
     * 구간 시작 시드 — 방면 판정 + 탑승 후보.
     * 방면: 탑승역·하차역 전광판의 역 id와 방면별 이전/다음 역 id로 서버가 정한다(Heading.kt) — 유저에게
     * 상행/하행을 묻지 않는다 (2026-09-21 QA: 충무로→교대에서 반대 방면 열차를 후보로 잡아 반대로 추적).
     * 후보: 탑승역에서 지금 도착·출발 중인 열차 중 그 방면 — 하차역 목록에 이 중 하나가 나타나면 우리 열차.
     * 상류 실패·빈 전광판이면 빈 후보/미판정 — 추적 엔진이 매 틱 재시도한다 (2026-09-16 저녁 실측 개정)
     */
    private fun seedLeg(leg: JourneyLeg): LegSeed {
        val boardRows = try {
            trains.approaching(leg.boardStop).also(stationIds::learn)
        } catch (e: Exception) {
            log.warn("탑승 후보 열차 조회 실패 — board={}: {}", leg.boardStop, e.message)
            emptyList()
        }
        val alightRows = try {
            trains.approaching(leg.alightStop).also(stationIds::learn)
        } catch (e: Exception) {
            log.warn("하차역 조회 실패(방면 판정용) — alight={}: {}", leg.alightStop, e.message)
            emptyList()
        }
        val boardId = stationIds.get(leg.line, leg.boardStop)
        val alightId = stationIds.get(leg.line, leg.alightStop)
        val heading = if (boardId != null && alightId != null) {
            resolveHeading(leg.line, boardId, alightId, boardRows + alightRows)
        } else {
            null
        }
        if (heading == null) {
            log.info("방면 미판정(시작) — {}→{} {}: 추적 엔진이 재시도", leg.boardStop, leg.alightStop, leg.line)
        }
        return LegSeed(heading, boardingCandidates(boardRows, leg.line, heading))
    }

    companion object {
        private val ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME
    }
}
