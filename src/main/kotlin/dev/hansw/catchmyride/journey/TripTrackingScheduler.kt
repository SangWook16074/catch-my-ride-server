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
 * §9 트립 추적 엔진 — 20초마다 진행 중 트립의 하차역 접근 열차를 조회해 상태를 전진시킨다.
 *
 * 불변 조건 (테스트가 명세):
 * - 열차 특정: 탑승역 후보(btrainNo) 중 하나가 하차역 목록에 나타나면 그 열차로 확정 —
 *   방향은 자기선택된다(탑승역에 있던 열차가 하차역에 접근 = 올바른 방향). 특정 전엔
 *   remainingStops null(위치 확인 중, API.md §9-3). 후보가 비어 있으면 특정 전까지 탑승역
 *   전광판에서 재수집한다 (2026-09-16 저녁 실측: 시작 순간 전광판 공백 = 트립이 영영 죽었다)
 * - 발송은 이벤트(구간 하차)당 최대 2회: PRE(2정거장 전)·ALIGHT(직전 역) — trip_push_log PK로
 *   강제 (FR-704, 2026-09-10 폭주 사고 재발 방지). 도착을 지나쳐 발견한 경우 사후 발송하지 않는다
 * - remaining 0 = 하차역 도착: 마지막 구간이면 DONE, 아니면 TRANSFER(수동 재개 대기, FR-703)
 * - 특정 실패(후보 없음·타임아웃)·목격 두절은 LOST — 조용히 틀리지 않는다 (FR-706, NFR-03)
 * - LOST는 종착이 아니다 — 재목격·재특정되면 TRACKING으로 복구한다 (2026-09-15 실측: 지하철
 *   실시간 피드 두절은 흔한데 복구 경로가 없으면 트립이 사실상 죽는다). 단, LOST로 머무는 동안은
 *   저장하지 않아 updated_at이 두절 시점에 묶인다 — 방치 트립 자동 정리(STALE_AFTER)가 살아있어야 한다
 * - 상류 장애는 LOST가 아니다 — realtimeAvailable=false로 표시하고 상태 유지 (NFR-03)
 * - 폴링은 역·노선 단위 스냅샷 재사용(같은 틱 안 캐시) — 같은 하차역/노선의 트립 N개 = 상류 1회 (NFR-08)
 * - 방면은 서버가 정한다 (2026-09-21 QA 개정, Heading.kt): 탑승·하차역 id 순번과 전광판 방면별 이전/다음 역 id로
 *   구간의 진행 방면(UP/DOWN)을 판정하고, 후보 수집·특정·노선 목격을 그 방면으로만 좁힌다. 판정 전엔
 *   방면 필터 없이 자기선택으로 강등하되 단일 후보 위치 표시는 하지 않는다(반대 방면 열차를 보여줬던 사고).
 *   판정되는 순간 후보를 그 방면으로 다시 잡고, 특정 전엔 매 틱 탑승역에서 후보를 보태 늦게 시작한 트립도 흡수한다
 * - 노선 전체 위치(realtimePosition)는 보강 피드다 (2026-09-16 출근 실측: 하차역 전광판은 방면당
 *   1·2번째 열차만 보여줘 특정 전엔 내내 "위치 확인 중", 특정 후에도 뒤차에 밀리면 3분 두절 LOST가 났다):
 *   특정 전 후보 목격 = 타임아웃 억제 + 단일 후보면 currentStop 제공, 특정 후 목격 = 두절 LOST 방지.
 *   정거장 카운트·발송 판정은 여전히 전광판 목격만 쓴다 (역 순서 데이터 없이 아는 척 금지, NFR-03)
 */
@Component
@ConditionalOnProperty("journey.tracking.enabled", havingValue = "true", matchIfMissing = true)
class TripTrackingScheduler(
    private val trips: TripRepository,
    private val trains: TrainPositions,
    private val stationIds: StationIdCache,
    private val pushTokens: PushTokenRepository,
    private val fcm: FcmPushClient,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${journey.tracking.interval:PT20S}")
    fun poll() = tick()

    fun tick() {
        val now = LocalDateTime.now(clock)
        // 같은 틱 안에서 역·노선 스냅샷 재사용 — 같은 하차역/노선을 보는 트립들의 상류 호출을 1회로 (NFR-08)
        val snapshots = mutableMapOf<String, Result<List<ApproachingTrain>>>()
        val lineSnapshots = mutableMapOf<String, List<LineTrain>>()
        for (trip in trips.findActive()) {
            try {
                process(trip, now, snapshots, lineSnapshots)
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
            // 방면이 이번 틱에 정해졌으면 이전 후보(방면 무관 수집)는 버리고 그 방면으로 다시 잡는다
            val seeded = boardingCandidates(boardRows, leg.line, tracked.heading)
            val candidates = if (headingResolvedNow) seeded else (tracked.candidates + seeded).distinct()
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
                if (wasLost) {
                    return // 특정 실패 LOST — 후보 재등장만 기다린다 (재LOST 판정·저장 없음)
                }
                // 전광판·노선 어디에도 없음 — 마지막 목격(없으면 구간 시작) 기준 타임아웃까지 위치 확인 중
                if (Duration.between(tracked.lastSeenAt ?: tracked.legStartedAt, now) > IDENTIFY_TIMEOUT) {
                    markLost(tracked, now, "열차 특정 실패(후보=${tracked.candidates.size})")
                    return
                }
                trips.save(tracked, now)
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
                return // 목격 두절 LOST — 재목격만 기다린다 (재LOST 판정·저장 없음)
            }
            val lastSeen = tracked.lastSeenAt
            if (lastSeen != null && Duration.between(lastSeen, now) > LOST_AFTER) {
                markLost(tracked, now, "목격 두절")
                return
            }
            if (lastSeen == null && Duration.between(tracked.legStartedAt, now) > IDENTIFY_TIMEOUT) {
                markLost(tracked, now, "특정 후 미목격")
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

    private fun markLost(trip: Trip, now: LocalDateTime, reason: String) {
        log.warn("트립 LOST — trip={} leg={}: {}", trip.tripId, trip.legIndex, reason)
        // 위치 정보는 전부 비운다 — 끊긴 채 낡은 역명을 보여주지 않는다 (NFR-03)
        trips.save(trip.copy(phase = TripPhase.LOST, remainingStops = null, currentStop = null), now)
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
         * 특정 전 무목격 제한 — 마지막 목격(없으면 구간 시작) 후 이 시간 안에 전광판·노선
         * 어디서든 열차가 보여야 한다. 후보 재수집·LOST 복구 루프가 있어 LOST는 종착이
         * 아니므로 짧게 실패를 알리는 쪽을 택했다 (오너 결정 2026-09-16: 15분 → 1분)
         */
        private val IDENTIFY_TIMEOUT: Duration = Duration.ofMinutes(1)

        /** 특정된 열차가 목록에서 사라진 채 이 시간이 지나면 LOST (순간 누락은 허용) */
        private val LOST_AFTER: Duration = Duration.ofMinutes(3)

        private val STALE_AFTER: Duration = Duration.ofHours(6)
    }
}
