package dev.hansw.catchmyride.journey

import dev.hansw.catchmyride.push.FcmPushClient
import dev.hansw.catchmyride.push.PushTokenRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime

/**
 * §9 트립 추적 엔진 — 10초마다(v0.12, 2026-10-01 — 종전 20초) 진행 중 트립의 하차역 접근 열차를
 * 조회해 상태를 전진시킨다. 틱은 `trips.findActive()`가 돌려주는 트립의 노선만 조회하므로, 진행
 * 중 트립이 없으면 상류 호출이 전혀 없다 — "활성 노선만 10초"(§9-5)를 트립 단위로 이미 만족한다.
 *
 * 불변 조건 (테스트가 명세):
 * - 열차 특정: 탑승역 후보(btrainNo) 중 하나가 하차역 목록에 나타나면 그 열차로 확정 —
 *   방향은 자기선택된다(탑승역에 있던 열차가 하차역에 접근 = 올바른 방향). 특정 전엔
 *   remainingStops null(위치 확인 중, API.md §9-3). 후보가 비어 있으면 특정 전까지 탑승역
 *   전광판에서 재수집한다 (2026-09-16 저녁 실측: 시작 순간 전광판 공백 = 트립이 영영 죽었다)
 * - 발송은 이벤트(구간 하차)당 최대 2회: PRE(2정거장 전)·ALIGHT(직전 역) — trip_push_log PK로
 *   강제 (FR-704, 2026-09-10 폭주 사고 재발 방지). 도착을 지나쳐 발견한 경우 사후 발송하지 않는다
 * - remaining 0 = 하차역 도착: 마지막 구간이면 DONE, 아니면 TRANSFER(수동 재개 대기, FR-703)
 * - **경로가 잡힌 트립은 LOST로 내리지 않는다** (오너 결정 2026-10-01, v0.14): 특정 전 무목격은 "위치 확인 중"(remainingStops
 *   null)을 유지하며 후보를 계속 수집한다 — 어느 열차인지 모르는 채 숫자를 지어내지 않는 것(NFR-03)으로 충분하다
 * - **특정 후 목격 두절은 LOST가 아니다** (오너 결정 2026-09-30): 한번 잡힌 트립은 유저가 요청하지
 *   않는 이상 끊지 않는다. realtimeAvailable=false로 알리고 마지막 정거장·역명을 지키며, updated_at은
 *   올리지 않아 잊힌 트립 자동 정리 시계는 살려 둔다. 잘못 잡혔을 때의 출구는 유저의 다시 잡기(§9-3)다
 * - LOST는 종착이 아니다 — 재목격·재특정되면 TRACKING으로 복구한다 (2026-09-15 실측: 지하철
 *   실시간 피드 두절은 흔한데 복구 경로가 없으면 트립이 사실상 죽는다). 단, LOST로 머무는 동안은
 *   저장하지 않아 updated_at이 두절 시점에 묶인다 — 방치 트립 자동 정리(STALE_AFTER)가 살아있어야 한다
 * - 상류 장애는 LOST가 아니다 — realtimeAvailable=false로 표시하고 상태 유지 (NFR-03)
 * - 폴링은 역·노선 단위 스냅샷 재사용(같은 틱 안 캐시) — 같은 하차역/노선의 트립 N개 = 상류 1회 (NFR-08)
 * - 방면은 서버가 정한다 (2026-09-21 QA 개정, Heading.kt): 탑승·하차역 id 순번과 전광판 방면별 이전/다음 역 id로
 *   구간의 진행 방면(UP/DOWN)을 판정하고, 후보 수집·특정·노선 목격을 그 방면으로만 좁힌다. 판정 전엔
 *   방면 필터 없이 자기선택으로 강등하되 단일 후보 위치 표시는 하지 않는다(반대 방면 열차를 보여줬던 사고).
 *   판정되는 순간 후보를 그 방면으로 다시 잡고, 특정 전엔 매 틱 탑승역에서 후보를 보태 늦게 시작한 트립도 흡수한다
 * - 유저가 "내가 탄 열차가 아니에요"로 물린 열차(rejectedTrains)는 후보에 다시 넣지 않는다 —
 *   안 그러면 다시 잡기(§9-3)가 방금 물린 열차를 그대로 재특정한다 (2026-09-30 오너 요청)
 * - 중간 시작(seedStop != null, 위치로 잡은 트립)은 탑승역 전광판을 후보로 쓰지 않는다 —
 *   유저가 이미 지난 역이라 거기 있는 건 뒤차다 (2026-09-24 오너 제보: 출발지·목적지 사이에서
 *   시작하면 열차를 못 잡았다). 후보가 비었을 때만 위치 역 주변에서 60초 안에 다시 잡는다
 *   (RidingSeedResolver — 시작 시드와 같은 규칙, 2026-09-29 개정으로 좌표 기준 창)
 * - 노선 전체 위치(realtimePosition)는 보강 피드다 (2026-09-16 출근 실측: 하차역 전광판은 방면당
 *   1·2번째 열차만 보여줘 특정 전엔 내내 "위치 확인 중", 특정 후에도 뒤차에 밀리면 3분 두절 LOST가 났다):
 *   특정 전 후보 목격 = 타임아웃 억제 + 단일 후보면 currentStop 제공, 특정 후 목격 = 두절 LOST 방지.
 *   정거장 카운트·발송 판정은 여전히 전광판 목격만 쓴다 (역 순서 데이터 없이 아는 척 금지, NFR-03)
 * - 진행 표면 원격 갱신(§9-5, v0.12)은 하차·환승 알림(FR-704)과 별개 채널이다 — 트립 하나를 처리한
 *   직후 [TripSurfaceUpdater.afterTick]이 표면 값(phase·legIndex·remainingStops·currentStop·eventStop·
 *   realtimeAvailable)이 바뀌었는지 보고, 바뀌었을 때만 그 자리에서 보낸다(틱 끝 일괄 아님)
 */
@Component
@ConditionalOnProperty("journey.tracking.enabled", havingValue = "true", matchIfMissing = true)
class TripTrackingScheduler(
    private val trips: TripRepository,
    private val trains: TrainPositions,
    private val stationIds: StationIdCache,
    private val riding: RidingSeedResolver,
    private val pushTokens: PushTokenRepository,
    private val fcm: FcmPushClient,
    private val surfaceUpdater: TripSurfaceUpdater,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${journey.tracking.interval:PT10S}")
    fun poll() = tick()

    fun tick() {
        val now = LocalDateTime.now(clock)
        // 같은 틱 안에서 역·노선 스냅샷 재사용 — 같은 하차역/노선을 보는 트립들의 상류 호출을 1회로 (NFR-08)
        val snapshots = mutableMapOf<String, Result<List<ApproachingTrain>>>()
        val lineSnapshots = mutableMapOf<String, List<LineTrain>>()
        for (trip in trips.findActive()) {
            try {
                process(trip, now, snapshots, lineSnapshots)
                // 표면 값이 이번 틱에 바뀌었으면 그 자리에서 원격 갱신 (§9-5) — process() 내부 각
                // 분기를 일일이 손대지 않도록 틱 끝이 아니라 트립 처리 직후(여전히 다른 트립 전에)에 비교한다
                surfaceUpdater.afterTick(trip.tripId, now)
            } catch (e: Exception) {
                log.warn("트립 추적 실패 — trip={} 건너뜀: {}", trip.tripId, e.message)
            }
        }
        // 종료·방치 트립 자동 정리 (§9-3 — 권장 1시간보다 보수적으로 6시간)
        trips.deleteStale(now.minus(STALE_AFTER))
    }

    private fun process(
        trip: Trip,
        now: LocalDateTime,
        snapshots: MutableMap<String, Result<List<ApproachingTrain>>>,
        lineSnapshots: MutableMap<String, List<LineTrain>>,
    ) {
        val wasLost = trip.phase == TripPhase.LOST
        val leg = trip.currentLeg
        val snapshot = snapshots.getOrPut(leg.alightStop) {
            runCatching { trains.approaching(leg.alightStop) }
        }
        val approaching = snapshot.getOrElse { error ->
            // 상류 장애 — LOST가 아니라 "실시간 정보 없음" (NFR-03).
            // 이미 LOST면 저장하지 않는다 — updated_at을 두절 시점에 묶어 자동 정리를 살린다
            log.warn("하차역 조회 실패 — station={}: {}", leg.alightStop, error.message)
            if (!wasLost) {
                trips.save(trip.copy(realtimeAvailable = false), now)
            }
            return
        }
        stationIds.learn(approaching)

        var tracked = trip.copy(realtimeAvailable = true)
        if (tracked.btrainNo == null) {
            // 특정 전엔 매 틱 탑승역 전광판도 본다 — 후보 보강(아래)과 역 id 학습(방면 판정) 겸용
            val boardRows = snapshots.getOrPut(leg.boardStop) {
                runCatching { trains.approaching(leg.boardStop) }
            }.getOrNull().orEmpty()
            stationIds.learn(boardRows)
            // 방면 판정 — 시작 때 못 정했으면 매 틱 다시 시도 (양쪽 전광판 + 학습된 역 id, 부족하면 노선 위치)
            var headingResolvedNow = false
            if (tracked.heading == null) {
                if (stationIds.get(leg.line, leg.boardStop) == null || stationIds.get(leg.line, leg.alightStop) == null) {
                    onLine(leg.line, lineSnapshots) // 역명→id 학습 부수효과
                }
                val boardId = stationIds.get(leg.line, leg.boardStop)
                val alightId = stationIds.get(leg.line, leg.alightStop)
                if (boardId != null && alightId != null) {
                    resolveHeading(leg.line, boardId, alightId, approaching + boardRows)?.let { heading ->
                        tracked = tracked.copy(heading = heading)
                        headingResolvedNow = true
                        log.info("방면 판정 — trip={} leg={} {}→{} {}", tracked.tripId, tracked.legIndex, leg.boardStop, leg.alightStop, heading)
                    }
                }
            }
            // 특정 전엔 매 틱 탑승역에서 후보를 보탠다 — 시작 순간 전광판이 비었거나(2026-09-16 저녁
            // 실측) 반대 방면 열차만 잡혔던 트립(2026-09-21 QA)이 실제 탄 열차를 뒤늦게라도 잡도록.
            // 방면이 이번 틱에 정해졌으면 이전 후보(방면 무관 수집)는 버리고 그 방면으로 다시 잡는다.
            // 단 **중간 시작**(위치로 잡은 트립, seedStop != null)은 탑승역 전광판을 보지 않는다 —
            // 유저가 이미 지난 역이라 거기 있는 건 뒤차다 (2026-09-24 오너 제보)
            val seeded = if (tracked.seedStop == null) {
                boardingCandidates(boardRows, leg.line, tracked.heading)
            } else if (tracked.candidates.isEmpty() &&
                Duration.between(tracked.legStartedAt, now) <= RIDING_RESEED_WINDOW
            ) {
                // 시작 순간 노선 위치가 비어 후보를 못 잡은 경우만 짧게 재수집한다 — 위치 역 창을
                // 계속 보면 뒤따라 들어온 열차를 유저 열차로 착각한다
                ridingWindow(tracked, leg, lineSnapshots)
            } else {
                emptyList()
            }
            val candidates = when {
                !headingResolvedNow -> (tracked.candidates + seeded).distinct()
                // 중간 시작은 위치로 잡은 후보가 전부라 버리지 않고, 방면이 반대로 목격된 것만 쳐낸다
                tracked.seedStop != null ->
                    dropWrongHeading((tracked.candidates + seeded).distinct(), tracked.heading, onLine(leg.line, lineSnapshots))
                else -> seeded
                // 유저가 "내가 탄 열차가 아니에요"로 물린 열차는 어느 경로로도 다시 들어오지 않는다 (§9-3)
            }.withoutRejected(tracked.rejectedTrains)
            if (candidates != tracked.candidates) {
                tracked = tracked.copy(candidates = candidates)
                log.info("후보 갱신 — trip={} 후보={}대 방면={}", tracked.tripId, candidates.size, tracked.heading)
            }
            val matching = approaching.filter { it.matchesLine(leg.line) && it.matchesHeading(tracked.heading) }
            val candidateKeys = tracked.candidates.map(::trainNoKey).toSet()
            val found = matching.firstOrNull { trainNoKey(it.trainNo) in candidateKeys }
            if (found == null) {
                // 하차역 전광판(방면당 1·2번째 열차만)엔 아직 없음 — 노선 전체 위치로 후보를 계속
                // 목격한다 (2026-09-16 출근 실측: 전광판만 보면 하차역 근처까지 내내 "위치 확인 중")
                val sighted = onLine(leg.line, lineSnapshots)
                    .filter { it.matchesExpress(leg.line) && it.matchesHeading(tracked.heading) && trainNoKey(it.trainNo) in candidateKeys }
                if (sighted.isNotEmpty()) {
                    tracked = tracked.copy(
                        phase = TripPhase.TRACKING,
                        lastSeenAt = now,
                        // 후보가 1대이고 방면이 정해졌을 때만 위치를 보여준다 — 여러 대면 유저가 탄 열차를
                        // 몰라 아는 척하지 않고(NFR-03), 방면 미판정 단일 후보는 반대 방면 열차일 수 있다
                        // (2026-09-21 QA). 카운트다운은 여전히 전광판 목격부터
                        currentStop = if (tracked.candidates.size == 1 && tracked.heading != null) {
                            sighted.first().station ?: tracked.currentStop
                        } else {
                            null
                        },
                    )
                    if (wasLost) {
                        log.info("트립 LOST 복구(노선 목격) — trip={} leg={}", tracked.tripId, tracked.legIndex)
                    }
                    trips.save(tracked, now)
                    return
                }
                // 전광판·노선 어디에도 없음 — **LOST로 내리지 않고 "위치 확인 중"을 유지한다**
                // (오너 결정 2026-10-01 "한번 경로가 잡힌 상태에서 끊기지만 않으면 된다", API.md v0.14).
                // 실측: 밤 배차(6~10분)에 플랫폼에서 시작하면 2분 안 도착 열차가 없어 1분 만에 LOST가 났다
                // (2026-10-01 21:48·21:50 "열차 특정 실패(후보=0)"). 후보 수집은 위에서 매 틱 계속되므로
                // 열차가 들어오면 그대로 잡힌다. 이미 LOST였던 트립(이전 규칙)도 여기서 TRACKING으로 되돌린다.
                // 오래 못 잡으면 updated_at을 올리지 않아 잊힌 트립 자동 정리(STALE_AFTER) 시계는 살려 둔다
                val unseenFor = Duration.between(tracked.lastSeenAt ?: tracked.legStartedAt, now)
                trips.save(tracked.copy(phase = TripPhase.TRACKING), now, touch = unseenFor <= IDENTIFY_TIMEOUT)
                return
            }
            tracked = tracked.copy(btrainNo = found.trainNo, phase = TripPhase.TRACKING)
            log.info("열차 특정 — trip={} btrainNo={} leg={}", tracked.tripId, found.trainNo, tracked.legIndex)
        }

        val trackedKey = trainNoKey(tracked.btrainNo!!)
        val matching = approaching.filter { it.matchesLine(leg.line) && it.matchesHeading(tracked.heading) }
        val train = matching.firstOrNull { trainNoKey(it.trainNo) == trackedKey }
        if (train == null) {
            // 하차역 통과·도착 후엔 목록에서 사라진다 — 직전에 1정거장 이내였다면 도착으로 본다
            // (LOST였다면 remainingStops가 비워져 있어 이 판정을 타지 않는다)
            if (tracked.remainingStops != null && tracked.remainingStops!! <= 1) {
                arriveAtEvent(tracked, now)
                return
            }
            // 뒤차에 밀려 전광판(방면당 2대)에서 빠질 수 있다 — 노선 전체 위치로 계속 목격 (2026-09-16)
            val onLineTrain = onLine(leg.line, lineSnapshots)
                .firstOrNull { it.matchesExpress(leg.line) && it.matchesHeading(tracked.heading) && trainNoKey(it.trainNo) == trackedKey }
            if (onLineTrain != null) {
                tracked = tracked.copy(
                    currentStop = onLineTrain.station ?: tracked.currentStop,
                    lastSeenAt = now,
                )
                if (tracked.phase == TripPhase.LOST) {
                    tracked = tracked.copy(phase = TripPhase.TRACKING)
                    log.info(
                        "트립 LOST 복구(노선 목격) — trip={} btrainNo={} leg={}",
                        tracked.tripId, tracked.btrainNo, tracked.legIndex,
                    )
                }
                trips.save(tracked, now)
                return
            }
            if (wasLost) {
                // 예전 규칙으로 LOST가 된 특정 트립 — 끊지 않는다(v0.14): 추적 중 + 실시간 정보 없음으로 되돌린다.
                // 정리 시계는 살려 둔다(touch=false)
                trips.save(tracked.copy(phase = TripPhase.TRACKING, realtimeAvailable = false), now, touch = false)
                return
            }
            // **한번 특정된 트립은 유저가 요청하지 않는 이상 끊지 않는다** (오너 결정 2026-09-30):
            // 목격이 멈추면 LOST로 내리지 않고 "실시간 정보 없음"으로 알리며 마지막 값(정거장·역명)을
            // 지킨다. 지하철 실시간 피드는 원래 자주 끊기는데, 끊길 때마다 화면을 특정 전으로 되돌리면
            // 유저는 "다시 위치를 잡는다"고 느낀다. 틀린 알림 위험은 없다 — 발송 판정은 여전히 실제
            // 전광판 목격만 쓰므로 안 보이는 동안은 푸시가 나가지 않는다 (FR-704).
            // 잘못 잡힌 경우의 출구는 유저의 "내가 탄 열차가 아니에요"(§9-3 다시 잡기)다.
            val unseenFor = Duration.between(tracked.lastSeenAt ?: tracked.legStartedAt, now)
            if (unseenFor > LOST_AFTER) {
                if (trip.realtimeAvailable) {
                    log.info(
                        "특정 열차 목격 두절 {}분 — trip={} btrainNo={}: 실시간 정보 없음으로 표시(추적 유지)",
                        unseenFor.toMinutes(), tracked.tripId, tracked.btrainNo,
                    )
                }
                // updated_at은 올리지 않는다 — 잊힌 트립이 자동 정리(STALE_AFTER)될 시계를 살려 둔다
                trips.save(tracked.copy(realtimeAvailable = false), now, touch = false)
                return
            }
            trips.save(tracked, now) // 순간 누락 허용 — 다음 틱에 재확인
            return
        }

        val remaining = train.stationsAway ?: tracked.remainingStops // 모르면 직전 값 유지 (아는 척 금지)
        // 현재 위치 역명(arvlMsg3)도 같은 규칙 — 목격 값만 쓰고, 모르면 직전 값 유지 (§9-3 currentStop)
        tracked = tracked.copy(
            remainingStops = remaining,
            currentStop = train.currentStation ?: tracked.currentStop,
            lastSeenAt = now,
        )
        if (tracked.phase == TripPhase.LOST) {
            // 재목격 — LOST 복구 (끊겼다 돌아오면 다시 이어간다, §9-3)
            tracked = tracked.copy(phase = TripPhase.TRACKING)
            log.info("트립 LOST 복구 — trip={} btrainNo={} leg={}", tracked.tripId, tracked.btrainNo, tracked.legIndex)
        }

        if (remaining != null) {
            if (remaining in 1..2) {
                // FR-704 ① 예고 — 처음 2정거장 이내로 들어온 시점 1회
                maybePush(tracked, TripPushStage.PRE, now, title = "곧 내려요", body = preBody(tracked, remaining))
            }
            if (remaining <= 1) {
                tracked = tracked.copy(phase = TripPhase.ARRIVING)
                // FR-704 ② 하차 — 직전 역 1회
                maybePush(
                    tracked, TripPushStage.ALIGHT, now,
                    title = "다음 역에서 내리세요",
                    body = alightBody(tracked),
                )
            }
            if (remaining == 0) {
                arriveAtEvent(tracked, now)
                return
            }
        }
        trips.save(tracked, now)
    }

    /**
     * 중간 시작 트립의 후보 재수집 — 저장된 위치 역(seedStop) 주변의 우리 방면·구간 안 열차.
     * 시작 시드와 같은 규칙(RidingSeedResolver, §9-2)이라 결과가 어긋나지 않는다
     */
    private fun ridingWindow(
        trip: Trip,
        leg: JourneyLeg,
        lineSnapshots: MutableMap<String, List<LineTrain>>,
    ): List<String> {
        val seedStop = trip.seedStop ?: return emptyList()
        return riding
            .candidatesAround(leg, seedStop, trip.heading, onLine(leg.line, lineSnapshots))
            .withoutRejected(trip.rejectedTrains)
    }

    /** 방면이 뒤늦게 정해졌을 때 — 반대 방면으로 목격된 후보만 쳐낸다 (모르는 열차는 남긴다, NFR-03) */
    private fun dropWrongHeading(candidates: List<String>, heading: Heading?, lineTrains: List<LineTrain>): List<String> {
        if (heading == null) {
            return candidates
        }
        val wrong = lineTrains
            .filter { it.heading != null && it.heading != heading }
            .map { trainNoKey(it.trainNo) }
            .toSet()
        return candidates.filterNot { trainNoKey(it) in wrong }
    }

    /** 노선 위치 스냅샷 — 같은 틱 안 노선당 상류 1회 (NFR-08). 보강 피드라 실패는 빈 목록 강등 */
    private fun onLine(legLine: String, cache: MutableMap<String, List<LineTrain>>): List<LineTrain> =
        cache.getOrPut(lineBase(legLine)) {
            runCatching { trains.onLine(lineBase(legLine)) }.getOrElse { emptyList() }
                .also { stationIds.learn(lineBase(legLine), it) }
        }

    /** 하차역 도착 — 마지막 구간이면 DONE, 아니면 TRANSFER(다음 구간 수동 재개 대기) */
    private fun arriveAtEvent(trip: Trip, now: LocalDateTime) {
        val phase = if (trip.isLastLeg) TripPhase.DONE else TripPhase.TRANSFER
        trips.save(trip.copy(phase = phase, remainingStops = 0), now)
        log.info("구간 하차 — trip={} leg={} phase={}", trip.tripId, trip.legIndex, phase)
    }

    /**
     * 발송 순서는 "로그 선기록 → 발송 → delivered 갱신, 실패 시 로그 롤백" —
     * PushNotificationScheduler와 동일 불변식 (크래시 시에도 초과 발송 없음)
     */
    private fun maybePush(trip: Trip, stage: TripPushStage, now: LocalDateTime, title: String, body: String) {
        if (trips.pushLogged(trip.tripId, trip.legIndex, stage)) {
            return
        }
        trips.recordPush(trip.tripId, trip.legIndex, stage, delivered = false, now = now)
        val token = pushTokens.find(trip.userKey)
        if (token == null) {
            log.info("[dry-run] 트립 푸시 미발송(토큰 없음) — trip={} stage={}", trip.tripId, stage)
            return
        }
        try {
            val delivered = fcm.sendMessage(
                userKey = trip.userKey,
                token = token,
                title = title,
                body = body,
                link = "catchmyride://trip?tripId=${trip.tripId}",
            )
            if (delivered) {
                trips.rollbackPush(trip.tripId, trip.legIndex, stage)
                trips.recordPush(trip.tripId, trip.legIndex, stage, delivered = true, now = now)
            }
        } catch (e: Exception) {
            trips.rollbackPush(trip.tripId, trip.legIndex, stage) // 다음 틱에 재시도
            log.warn("트립 푸시 발송 실패 — trip={} stage={}: {}", trip.tripId, stage, e.message)
        }
    }

    private fun preBody(trip: Trip, remaining: Int): String {
        val stop = trip.currentLeg.alightStop
        return if (remaining == 1) "다음 역이 ${stop}역이에요. 내릴 준비하세요." else "두 정거장 뒤 ${stop}역에서 내려요."
    }

    private fun alightBody(trip: Trip): String {
        val stop = trip.currentLeg.alightStop
        if (trip.isLastLeg) {
            return "${stop}역 도착! 내리세요."
        }
        val nextLine = trip.legs[trip.legIndex + 1].line
        return "${stop}역에서 내려 ${nextLine}으로 갈아타세요."
    }

    companion object {
        /**
         * 특정 전 무목격 — 이 시간을 넘기면 updated_at을 올리지 않는다(잊힌 트립 자동 정리용).
         * 예전엔 이 시점에 LOST로 내렸지만 경로가 잡힌 트립은 끊지 않기로 했다 (오너 결정 2026-10-01, v0.14)
         */
        private val IDENTIFY_TIMEOUT: Duration = Duration.ofMinutes(1)

        /**
         * 중간 시작 트립이 위치 역 창에서 후보를 다시 잡아 볼 수 있는 시간 — 이보다 지나면
         * 그 창에 있는 건 유저 열차가 아니라 뒤차다 (2026-09-24)
         */
        private val RIDING_RESEED_WINDOW: Duration = Duration.ofSeconds(60)

        /**
         * 특정된 열차가 전광판·노선 어디에서도 이 시간 넘게 안 보이면 "실시간 정보 없음"으로 알린다
         * (순간 누락은 그대로 추적 유지). 예전엔 이 시점에 LOST로 내렸는데, 한번 잡힌 트립은 유저가
         * 요청하지 않는 이상 끊지 않기로 했다 (오너 결정 2026-09-30)
         */
        private val LOST_AFTER: Duration = Duration.ofMinutes(3)

        private val STALE_AFTER: Duration = Duration.ofHours(6)
    }
}
