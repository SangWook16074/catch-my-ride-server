package dev.hansw.catchmyride.journey

import dev.hansw.catchmyride.api.ApiException
import dev.hansw.catchmyride.api.UserKeyResolver
import dev.hansw.catchmyride.stops.SubwayStationCatalog
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
import java.time.Duration
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
    private val riding: RidingSeedResolver,
    private val stations: SubwayStationCatalog,
    private val legValidator: JourneyLegValidator,
    private val userKeys: UserKeyResolver,
    private val surfaceUpdater: TripSurfaceUpdater,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    data class StartResponse(val tripId: String, val startedAt: String, val legIndex: Int)

    /**
     * 시작 시점 유저 위치(선택) — §9-2 "중간 시작". 이미 탄 상태로 시작하면 탑승역 전광판에는
     * 유저 뒤에 오는 열차만 있어 잘못 잡힌다(2026-09-24 오너 제보). 권한 거부·실내 측위 실패는
     * 그냥 없이 보낸다 — 서버는 기존 동작으로 강등한다 (NFR-03·NFR-05: 상시 추적 아님, 시작 1회)
     */
    data class LocationRequest(
        val lat: Double?,
        val lng: Double?,
        val accuracy: Double?,
        /** 이 좌표를 딴 뒤 흐른 시간(초, 선택) — 낡은 좌표는 뒤차를 고르게 해서 버린다 (2026-09-29) */
        val ageSeconds: Long? = null,
    )
    data class StartRequest(val location: LocationRequest?)
    /** §9-3 구간 바꾸기 — legIndex는 필수(0 ≤ 값 < 구간 수), location은 시작과 같은 선택 필드 */
    data class SwitchLegRequest(val legIndex: Int, val location: LocationRequest? = null)
    data class QuickStartRequest(val legs: List<LegRequest>?, val location: LocationRequest? = null)
    data class StatusResponse(
        val phase: String,
        val legIndex: Int,
        val remainingStops: Int?,
        val currentStop: String?,
        val eventStop: String,
        val realtimeAvailable: Boolean,
        /**
         * 이 열차를 상류에서 **마지막으로 목격한 시각** (없으면 null) — 특정 후 목격이 끊겨도 추적을
         * 끊지 않기로 했으므로(오너 결정 2026-09-30), 화면이 "언제 기준 값인지" 말할 수 있어야 한다.
         * 낡은 숫자를 현재처럼 보여주는 건 조용히 틀리는 것과 같다 (NFR-03)
         */
        val lastSeenAt: String?,
        /**
         * "내렸어요"를 되돌릴 수 있는 마감(v0.11, 없으면 null) — alighted 직후 5분 동안만 값이 있다.
         * 서버가 도착을 직접 확인한 하차(자동 TRANSFER·DONE)엔 null (API.md §9-3)
         */
        val undoableUntil: String?,
        val fetchedAt: String,
    )

    @PostMapping("/api/v1/journeys/{journeyId}/trips")
    @ResponseStatus(HttpStatus.CREATED)
    fun start(
        @RequestHeader(value = "Authorization", required = false) auth: String?,
        @PathVariable journeyId: String,
        @RequestBody(required = false) request: StartRequest?,
    ): StartResponse {
        val userKey = userKeys.resolve(auth)
        val journey = journeys.find(userKey, journeyId) ?: throw ApiException.settingNotFound()
        val trip = startTrip(userKey, journeyId = journeyId, legs = journey.legs, location = request?.location)
        journeys.touchLastUsed(userKey, journeyId, trip.startedAt) // 히스토리 정렬 키 (§9-2)
        return StartResponse(tripId = trip.tripId, startedAt = trip.startedAt.format(ISO), legIndex = trip.legIndex)
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
        val trip = startTrip(userKey, journeyId = null, legs = legs, location = request.location)
        return StartResponse(tripId = trip.tripId, startedAt = trip.startedAt.format(ISO), legIndex = trip.legIndex)
    }

    private fun startTrip(
        userKey: String,
        journeyId: String?,
        legs: List<JourneyLeg>,
        location: LocationRequest?,
    ): Trip {
        trips.findByUser(userKey)?.let {
            throw ApiException.invalidRequest("진행 중인 트립이 있습니다: ${it.tripId}")
        }
        val now = LocalDateTime.now(clock)
        val legIndex = determineStartLeg(legs, location)
        val seed = seedLeg(legs[legIndex], location)
        val trip = Trip(
            tripId = UUID.randomUUID().toString(),
            userKey = userKey,
            journeyId = journeyId,
            legs = legs,
            legIndex = legIndex,
            phase = TripPhase.TRACKING,
            btrainNo = null,
            candidates = seed.candidates,
            seedStop = seed.seedStop,
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

    /**
     * API.md §9-2 "시작 구간 판정" (v0.10, 2026-10-01 오너 결정) — 구간이 2개 이상인 여정에서
     * 중간 시작(§9-2 1~4번)보다 먼저 "유저가 지금 몇 번째 구간에 있는가"를 정한다.
     * `next-leg`·`re-identify`·`switch-leg`는 구간이 이미 정해져 있으므로 이 판정을 하지 않는다.
     *
     * 1. **타고 있는 구간** — 구간마다 중간 시작(열차 후보)을 돌려 열차가 잡히는 구간을 고른다.
     *    둘 이상이면 유저 좌표에서 후보 열차가 더 가까운 쪽, 같으면 뒤 구간.
     * 2. **타기 직전의 구간** — 안 잡히면 유저 좌표에서 가장 가까운 탑승역(반경 1.5km+오차)의 구간.
     *    환승역은 앞 구간의 하차역이자 뒷 구간의 탑승역이라 둘 다 가깝지만, 탑승역 기준으로만 재므로
     *    뒷 구간만 걸린다(앞 구간의 탑승역은 멀다) — 같은 거리면 뒤 구간.
     * 3. 둘 다 아니면 0번 구간.
     *
     * 좌표 게이트(§9-2 1번 — 90초·2.5km)는 그대로 쓰고, 통과 못 하거나 좌표가 없으면 0번(종전 동작).
     * 건너뛴 앞 구간은 추적·발송하지 않는다 — startTrip이 바로 판정된 구간부터 시드를 만든다.
     */
    private fun determineStartLeg(legs: List<JourneyLeg>, location: LocationRequest?): Int {
        if (legs.size < 2) {
            return 0
        }
        val fix = location?.toFix() ?: return 0
        if (fix.ageSeconds != null && fix.ageSeconds > RidingSeedResolver.MAX_FIX_AGE_SECONDS) {
            return 0 // 좌표 게이트 통과 못 함 — 종전 동작(0번)
        }
        if (fix.accuracyMeters != null && fix.accuracyMeters > RidingSeedResolver.MAX_ACCURACY_METERS) {
            return 0
        }

        // 1. 타고 있는 구간 — 구간마다 방면 판정 후 위치 기반 중간 시작(ridingSeed)을 돌려본다
        var ridingMatch: Pair<Int, Double>? = null
        for (i in legs.indices) {
            val leg = legs[i]
            val heading = legHeadingForDetection(leg)
            val seed = ridingSeed(leg, location, heading, emptyList()) ?: continue
            val distance = seed.distanceMeters ?: continue
            if (seed.candidates.isEmpty()) {
                continue
            }
            if (ridingMatch == null || distance <= ridingMatch!!.second) {
                ridingMatch = i to distance
            }
        }
        ridingMatch?.let { (legIndex, distance) ->
            log.info("시작 구간 판정 — leg={} (타는 중, 거리={}m)", legIndex, distance.toInt())
            return legIndex
        }

        // 2. 타기 직전의 구간 — 가장 가까운 탑승역(반경 1.5km + 오차)
        val radius = RidingSeedResolver.NEAR_STATION_METERS + (fix.accuracyMeters ?: 0.0)
        var nearestBoard: Pair<Int, Double>? = null
        for (i in legs.indices) {
            val leg = legs[i]
            val distance = stations.distanceTo(lineBase(leg.line), leg.boardStop, fix.lat, fix.lng) ?: continue
            if (distance <= radius && (nearestBoard == null || distance <= nearestBoard!!.second)) {
                nearestBoard = i to distance
            }
        }
        nearestBoard?.let { (legIndex, distance) ->
            log.info("시작 구간 판정 — leg={} (탑승 직전, 거리={}m)", legIndex, distance.toInt())
            return legIndex
        }

        // 3. 둘 다 아니면 0번 구간
        log.info("시작 구간 판정 — leg=0 (기본값)")
        return 0
    }

    /** 구간 판정에서만 쓰는 방면 계산 — seedLeg와 같은 규칙이지만 로그·후보(boardingCandidates)는 건너뛴다 */
    private fun legHeadingForDetection(leg: JourneyLeg): Heading? {
        val boardRows = try {
            trains.approaching(leg.boardStop).also(stationIds::learn)
        } catch (e: Exception) {
            emptyList()
        }
        val alightRows = try {
            trains.approaching(leg.alightStop).also(stationIds::learn)
        } catch (e: Exception) {
            emptyList()
        }
        val boardId = stationIds.get(leg.line, leg.boardStop)
        val alightId = stationIds.get(leg.line, leg.alightStop)
        return if (boardId != null && alightId != null) {
            resolveHeading(leg.line, boardId, alightId, boardRows + alightRows)
        } else {
            null
        }
    }

    @GetMapping("/api/v1/trips/{tripId}")
    fun status(
        @RequestHeader(value = "Authorization", required = false) auth: String?,
        @PathVariable tripId: String,
    ): StatusResponse {
        val trip = findOwned(auth, tripId)
        return trip.toStatusResponse(LocalDateTime.now(clock))
    }

    /** 환승 후 다음 구간 수동 재개 (FR-703) — TRANSFER에서만 */
    @PostMapping("/api/v1/trips/{tripId}/next-leg")
    fun nextLeg(
        @RequestHeader(value = "Authorization", required = false) auth: String?,
        @PathVariable tripId: String,
        @RequestBody(required = false) request: StartRequest?,
    ): StatusResponse {
        val trip = findOwned(auth, tripId)
        if (trip.phase != TripPhase.TRANSFER) {
            throw ApiException.invalidRequest("환승 대기 상태가 아닙니다")
        }
        val now = LocalDateTime.now(clock)
        val nextIndex = trip.legIndex + 1
        val nextLeg = trip.legs[nextIndex]
        val seed = seedLeg(nextLeg, request?.location)
        val resumed = trip.copy(
            legIndex = nextIndex,
            phase = TripPhase.TRACKING,
            btrainNo = null,
            candidates = seed.candidates,
            seedStop = seed.seedStop,
            heading = seed.heading, // 새 구간 — 방면도 새로 판정 (환승 후 반대 방면 문제의 핵심)
            rejectedTrains = emptyList(), // 새 구간 — 이전 구간에서 물린 열차 번호를 이월하지 않는다
            remainingStops = null,
            currentStop = null, // 새 구간 — 이전 구간의 위치 역명을 이월하지 않는다
            realtimeAvailable = true,
            legStartedAt = now,
            lastSeenAt = null,
            undoableUntil = null, // 새 구간으로 넘어감 — 직전 구간의 되돌리기 창은 더 이상 의미 없다
            undoSnapshot = null,
        )
        trips.save(resumed, now)
        surfaceUpdater.forceSend(trip.tripId, now) // §9-5 — 수동 전환 직후 1회
        return resumed.toStatusResponse(now)
    }

    /**
     * "내가 탄 열차가 아니에요" — 이 구간을 **다시 잡는다** (§9-3, 오너 요청 2026-09-30).
     *
     * 서버가 유저 뒤차·앞차를 특정하면 카운트다운과 하차 알림이 유저 열차와 어긋난다. 유저는 화면의
     * "현재 ○○ 부근"으로 그걸 제일 먼저 알아채므로, 그 자리에서 다시 잡게 해 준다 — 트립을 버리고
     * 새로 시작하면 여정·저장 흐름을 다시 타야 하고 1회성 구간은 날아간다.
     *
     * 규칙: 물린 열차는 [Trip.rejectedTrains]에 적어 후보에서 빼고(안 그러면 그대로 다시 잡힌다),
     * 위치를 새로 받아 "지금 있는 역" 기준으로 후보를 다시 만든다(§9-2 중간 시작과 같은 규칙 —
     * 이미 몇 정거장 갔을 테니 탑승역 전광판은 더 못 쓴다). 이 구간의 발송 기록은 지운다 —
     * 잘못 잡은 열차로 나간 예고는 무효이고, 제대로 잡은 뒤 FR-704의 2회를 다시 쓴다.
     * 물릴 수 있는 횟수는 구간당 [MAX_REJECTED]회 — 무한히 되돌리며 푸시를 다시 여는 길을 막는다
     * (2026-09-10 폭주 사고 교훈). 환승 대기·완료 상태에서는 400 (그건 `next-leg`·재시작의 일이다)
     */
    @PostMapping("/api/v1/trips/{tripId}/re-identify")
    fun reIdentify(
        @RequestHeader(value = "Authorization", required = false) auth: String?,
        @PathVariable tripId: String,
        @RequestBody(required = false) request: StartRequest?,
    ): StatusResponse {
        val trip = findOwned(auth, tripId)
        if (trip.phase == TripPhase.TRANSFER || trip.phase == TripPhase.DONE) {
            throw ApiException.invalidRequest("추적 중인 구간이 아닙니다")
        }
        if (trip.rejectedTrains.size >= MAX_REJECTED) {
            throw ApiException.invalidRequest("열차를 너무 여러 번 다시 잡았어요. 트립을 다시 시작해주세요")
        }
        val now = LocalDateTime.now(clock)
        val leg = trip.currentLeg
        val rejected = (trip.rejectedTrains + listOfNotNull(trip.btrainNo)).distinct()
        val seed = seedLeg(leg, request?.location, rejected)
        val reset = trip.copy(
            phase = TripPhase.TRACKING,
            btrainNo = null,
            candidates = seed.candidates,
            seedStop = seed.seedStop,
            // 방면은 같은 구간이라 그대로 — 재판정이 실패했다고 아는 값을 버리지 않는다
            heading = seed.heading ?: trip.heading,
            rejectedTrains = rejected,
            remainingStops = null,
            currentStop = null,
            realtimeAvailable = true,
            legStartedAt = now, // 특정 타임아웃·중간 시작 재수집 창을 지금부터 다시 센다
            lastSeenAt = null,
        )
        trips.save(reset, now)
        trips.clearPushLog(trip.tripId, trip.legIndex)
        surfaceUpdater.forceSend(trip.tripId, now) // §9-5 — 수동 전환 직후 1회
        log.info(
            "열차 다시 잡기 — trip={} leg={} 물린열차={} 후보={}대 위치역={}",
            trip.tripId, trip.legIndex, rejected, seed.candidates.size, seed.seedStop,
        )
        return reset.toStatusResponse(now)
    }

    /**
     * "구간 바꾸기" (§9-3 switch-leg, v0.10, 2026-10-01 오너 결정) — 시작 구간 자동 판정이 틀렸거나
     * 좌표 없이 시작돼 0번 구간에 묶였을 때의 출구. 앞·뒤 어느 쪽으로도 바꿀 수 있고 `TRANSFER`에서도
     * 허용한다(환승역에서 다음이 아닌 구간으로 갈 수 있다). 고른 구간의 물린 열차 목록·다시 잡기
     * 횟수·발송 기록을 비우고 새로 센다 — 구간이 바뀌면 이전 추적으로 나간 예고는 무관하다(FR-704).
     * 트립당 [MAX_SWITCH]회를 넘기면 400 — 구간을 오가며 푸시를 계속 재개방하지 않는다(2026-09-10 교훈).
     */
    @PostMapping("/api/v1/trips/{tripId}/switch-leg")
    fun switchLeg(
        @RequestHeader(value = "Authorization", required = false) auth: String?,
        @PathVariable tripId: String,
        @RequestBody request: SwitchLegRequest,
    ): StatusResponse {
        val trip = findOwned(auth, tripId)
        if (trip.phase == TripPhase.DONE) {
            throw ApiException.invalidRequest("이미 종료된 트립입니다")
        }
        val target = request.legIndex
        if (target !in trip.legs.indices || target == trip.legIndex) {
            throw ApiException.invalidRequest("legIndex가 올바르지 않습니다: $target")
        }
        if (trip.switchCount >= MAX_SWITCH) {
            throw ApiException.invalidRequest("구간을 너무 여러 번 바꿨어요. 다시 시작해주세요")
        }
        val now = LocalDateTime.now(clock)
        val leg = trip.legs[target]
        val seed = seedLeg(leg, request.location)
        val switched = trip.copy(
            legIndex = target,
            phase = TripPhase.TRACKING,
            btrainNo = null,
            candidates = seed.candidates,
            seedStop = seed.seedStop,
            heading = seed.heading,
            rejectedTrains = emptyList(),
            remainingStops = null,
            currentStop = null,
            realtimeAvailable = true,
            legStartedAt = now,
            lastSeenAt = null,
            switchCount = trip.switchCount + 1,
            undoableUntil = null,
            undoSnapshot = null,
        )
        trips.save(switched, now)
        trips.clearPushLog(tripId, target) // 그 구간 발송 기록을 비우고 새로 센다 (FR-704)
        surfaceUpdater.forceSend(tripId, now) // §9-5 — 수동 전환 직후 1회
        log.info("구간 바꾸기 — trip={} {}→{} (횟수={})", tripId, trip.legIndex, target, switched.switchCount)
        return switched.toStatusResponse(now)
    }

    /**
     * "내렸어요" (§9-3 alighted, v0.11, 2026-10-01 오너 결정) — 화면이 상류 지연으로 한 정거장쯤
     * 뒤처져도 유저가 기다리지 않게 하는 출구다. `TRACKING`·`ARRIVING`이고 `remainingStops ≤ 2`일
     * 때만 받는다 — 그 밖은 400(아직 멀리 있는데 누른 건 실수다). 환승 구간이면 `next-leg`와 같은
     * 시드로 **곧바로 다음 구간**을 시작하고(TRANSFER를 거치지 않는다), 마지막 구간이면 DONE.
     * 하차한 구간의 아직 안 나간 발송은 자연히 취소된다 — 전진한 뒤에는 scheduler가 이 트립의
     * `legIndex`(바뀐 값)만 보므로 옛 구간의 PRE·ALIGHT는 다시는 시도되지 않는다(사후 발송 금지, FR-704).
     * 되돌리기용으로 직전 구간 상태를 [AlightSnapshot]에 보관하고 `undoableUntil = now + 5분`을 채운다.
     */
    @PostMapping("/api/v1/trips/{tripId}/alighted")
    fun alighted(
        @RequestHeader(value = "Authorization", required = false) auth: String?,
        @PathVariable tripId: String,
        @RequestBody(required = false) request: StartRequest?,
    ): StatusResponse {
        val trip = findOwned(auth, tripId)
        if (trip.phase != TripPhase.TRACKING && trip.phase != TripPhase.ARRIVING) {
            throw ApiException.invalidRequest("추적 중인 구간이 아닙니다")
        }
        val remaining = trip.remainingStops
        if (remaining == null || remaining > 2) {
            throw ApiException.invalidRequest("아직 하차역에 가깝지 않습니다")
        }
        val now = LocalDateTime.now(clock)
        val snapshot = AlightSnapshot(
            legIndex = trip.legIndex,
            phase = trip.phase,
            btrainNo = trip.btrainNo,
            candidates = trip.candidates,
            rejectedTrains = trip.rejectedTrains,
            seedStop = trip.seedStop,
            heading = trip.heading,
            remainingStops = trip.remainingStops,
            currentStop = trip.currentStop,
            realtimeAvailable = trip.realtimeAvailable,
            legStartedAt = trip.legStartedAt,
            lastSeenAt = trip.lastSeenAt,
            discardedLegIndex = if (trip.isLastLeg) null else trip.legIndex + 1,
        )
        val advanced = if (trip.isLastLeg) {
            trip.copy(phase = TripPhase.DONE, remainingStops = 0)
        } else {
            val nextIndex = trip.legIndex + 1
            val nextLeg = trip.legs[nextIndex]
            val seed = seedLeg(nextLeg, request?.location)
            trip.copy(
                legIndex = nextIndex,
                phase = TripPhase.TRACKING,
                btrainNo = null,
                candidates = seed.candidates,
                seedStop = seed.seedStop,
                heading = seed.heading,
                rejectedTrains = emptyList(),
                remainingStops = null,
                currentStop = null,
                realtimeAvailable = true,
                legStartedAt = now,
                lastSeenAt = null,
            )
        }
        val withSnapshot = advanced.copy(undoableUntil = now.plus(UNDO_WINDOW), undoSnapshot = snapshot)
        trips.save(withSnapshot, now)
        surfaceUpdater.forceSend(tripId, now) // §9-5 — 수동 전환 직후 1회
        log.info("내렸어요 — trip={} leg={}→{} phase={}", tripId, trip.legIndex, withSnapshot.legIndex, withSnapshot.phase)
        return withSnapshot.toStatusResponse(now)
    }

    /**
     * "아직 안 내렸어요" (§9-3 undo-alight, v0.11) — alighted를 실수로 눌렀을 때 직전 구간을
     * **보관해 둔 그대로** 복원한다(같은 열차를 다시 특정하지 않는다). 새로 시작했던 다음 구간은
     * 버리고 그 구간의 발송 기록도 지운다. `undoableUntil`이 null이거나 지났으면 400,
     * 트립당 [MAX_UNDO]회를 넘기면 400(내렸어요↔되돌리기 반복으로 푸시를 재개방하지 않는다).
     * 마지막 구간의 DONE도 창 안이면 복원된다 — findOwned는 phase를 가리지 않는다.
     */
    @PostMapping("/api/v1/trips/{tripId}/undo-alight")
    fun undoAlight(
        @RequestHeader(value = "Authorization", required = false) auth: String?,
        @PathVariable tripId: String,
    ): StatusResponse {
        val trip = findOwned(auth, tripId)
        val now = LocalDateTime.now(clock)
        val deadline = trip.undoableUntil
        val snapshot = trip.undoSnapshot
        if (deadline == null || snapshot == null || now.isAfter(deadline)) {
            throw ApiException.invalidRequest("이미 시간이 지나 되돌릴 수 없어요 — 구간 바꾸기를 써주세요")
        }
        if (trip.undoCount >= MAX_UNDO) {
            throw ApiException.invalidRequest("너무 여러 번 되돌렸어요. 다시 시작해주세요")
        }
        val restored = trip.copy(
            legIndex = snapshot.legIndex,
            phase = snapshot.phase,
            btrainNo = snapshot.btrainNo,
            candidates = snapshot.candidates,
            rejectedTrains = snapshot.rejectedTrains,
            seedStop = snapshot.seedStop,
            heading = snapshot.heading,
            remainingStops = snapshot.remainingStops,
            currentStop = snapshot.currentStop,
            realtimeAvailable = snapshot.realtimeAvailable,
            legStartedAt = snapshot.legStartedAt,
            lastSeenAt = snapshot.lastSeenAt,
            undoCount = trip.undoCount + 1,
            undoableUntil = null,
            undoSnapshot = null,
        )
        trips.save(restored, now)
        // 새로 시작했던 다음 구간은 버린다 — 그 구간 발송 기록도 지운다 (직전 구간 발송 기록은 보존)
        snapshot.discardedLegIndex?.let { trips.clearPushLog(tripId, it) }
        surfaceUpdater.forceSend(tripId, now) // §9-5 — 수동 전환 직후 1회
        log.info("아직 안 내렸어요(되돌리기) — trip={} leg={} (횟수={})", tripId, restored.legIndex, restored.undoCount)
        return restored.toStatusResponse(now)
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
            surfaceUpdater.notifyEnd(trip, LocalDateTime.now(clock)) // §9-5 — iOS에 end 신호(삭제 전에 보낸다)
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

    /** 상태 응답 조립 공용 — undoableUntil은 지났으면 숨긴다(값은 남겨 두되 화면엔 null로 보인다) */
    private fun Trip.toStatusResponse(now: LocalDateTime): StatusResponse = StatusResponse(
        phase = phase.name,
        legIndex = legIndex,
        remainingStops = remainingStops,
        currentStop = currentStop,
        eventStop = currentLeg.alightStop,
        realtimeAvailable = realtimeAvailable,
        lastSeenAt = lastSeenAt?.format(ISO),
        undoableUntil = undoableUntil?.takeIf { it.isAfter(now) }?.format(ISO),
        fetchedAt = now.format(ISO),
    )

    private data class LegSeed(
        val heading: Heading?,
        val candidates: List<String>,
        val seedStop: String?,
        /** 위치 기반 시드일 때 후보 열차까지의 거리(m) — §9-2 "시작 구간 판정"의 구간 간 비교용. 탑승역 시드면 null */
        val distanceMeters: Double? = null,
    )

    /**
     * 구간 시작 시드 — 방면 판정 + 탑승 후보.
     * 방면: 탑승역·하차역 전광판의 역 id와 방면별 이전/다음 역 id로 서버가 정한다(Heading.kt) — 유저에게
     * 상행/하행을 묻지 않는다 (2026-09-21 QA: 충무로→교대에서 반대 방면 열차를 후보로 잡아 반대로 추적).
     * 후보: 탑승역에서 지금 도착·출발 중인 열차 중 그 방면 — 하차역 목록에 이 중 하나가 나타나면 우리 열차.
     * 상류 실패·빈 전광판이면 빈 후보/미판정 — 추적 엔진이 매 틱 재시도한다 (2026-09-16 저녁 실측 개정)
     */
    private fun seedLeg(leg: JourneyLeg, location: LocationRequest?, rejected: List<String> = emptyList()): LegSeed {
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
        ridingSeed(leg, location, heading, rejected)?.let { return it }
        return LegSeed(heading, boardingCandidates(boardRows, leg.line, heading).withoutRejected(rejected), seedStop = null)
    }

    /**
     * 위치 기반 "중간 시작" 시드 (오너 결정 2026-09-24, 규칙 개정 2026-09-29) — 유저가 탑승역을
     * 이미 지나 **탄 상태**로 시작한 경우. 판정·후보 규칙은 RidingSeedResolver가 들고 있다.
     *
     * 이 경우 탑승역 전광판은 쓰지 않는다 — 거기 있는 건 유저 뒤에 오는 열차라 하차 푸시가
     * 지나간 뒤에 온다(제보된 버그). 후보가 0대로 나와도 **위치 역을 기억한 채** 돌려준다:
     * 추적 엔진이 60초 창 안에서 같은 규칙으로 다시 잡고, 못 잡으면 뒤차를 따라가느니 LOST다 (FR-706)
     */
    private fun ridingSeed(
        leg: JourneyLeg,
        location: LocationRequest?,
        heading: Heading?,
        rejected: List<String>,
    ): LegSeed? {
        val fix = location?.toFix() ?: return null
        // 노선 위치를 먼저 받는다 — 후보의 유일한 출처이고, 역명→역 id 학습(구간 안 판정)도 여기서 채워진다
        val lineTrains = runCatching { trains.onLine(lineBase(leg.line)) }.getOrElse { emptyList() }
            .also { stationIds.learn(lineBase(leg.line), it) }
        val here = riding.locate(leg, fix) ?: return null
        val (candidatesRaw, distance) = riding.candidatesNearWithDistance(leg, fix, heading, lineTrains)
        val candidates = candidatesRaw.withoutRejected(rejected)
        if (candidates.isEmpty() && lineTrains.isNotEmpty()) {
            // 노선에 열차는 도는데 유저 주변 구간 안에 한 대도 없다 = 아직 탄 게 아니다(집·역 밖·엉뚱한 좌표).
            // 이때만 좌표를 버리고 탑승역 시드로 돌아간다 — 멀쩡한 "타기 전 시작"을 LOST로 만들지 않는다.
            // 반대로 노선 위치 자체가 비면(미지원 노선·상류 장애) 위치 역을 기억한 채 재수집·LOST로 간다:
            // 조용히 뒤차를 따라가지 않는다 (FR-706)
            log.info("위치 주변 구간에 열차 없음 — here={} ({}→{}): 탑승역 시드로", here, leg.boardStop, leg.alightStop)
            return null
        }
        log.info(
            "중간 시작(위치) — here={} ({}→{}) 후보={}대 방면={}",
            here, leg.boardStop, leg.alightStop, candidates.size, heading,
        )
        return LegSeed(heading, candidates, seedStop = here, distanceMeters = distance)
    }

    /** 좌표가 없으면(권한 거부·측위 실패) 위치 판정을 아예 건너뛴다 */
    private fun LocationRequest.toFix(): RidingSeedResolver.Fix? {
        val lat = lat ?: return null
        val lng = lng ?: return null
        return RidingSeedResolver.Fix(lat = lat, lng = lng, accuracyMeters = accuracy, ageSeconds = ageSeconds)
    }

    companion object {
        private val ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME

        /** 한 구간에서 "내가 탄 열차가 아니에요"를 받아 줄 횟수 — 넘으면 다시 시작이 맞다 */
        private const val MAX_REJECTED = 3

        /** 트립당 "구간 바꾸기" 허용 횟수 — 넘으면 다시 시작이 맞다 (2026-09-10 폭주 사고 교훈) */
        private const val MAX_SWITCH = 3

        /** 트립당 "아직 안 내렸어요" 허용 횟수 — 내렸어요↔되돌리기 반복으로 푸시를 재개방하지 않는다 */
        private const val MAX_UNDO = 2

        /** "내렸어요"를 되돌릴 수 있는 창 (v0.11) */
        private val UNDO_WINDOW: Duration = Duration.ofMinutes(5)
    }
}
