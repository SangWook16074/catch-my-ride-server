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
 *   remainingStops null(위치 확인 중, API.md §9-3)
 * - 발송은 이벤트(구간 하차)당 최대 2회: PRE(2정거장 전)·ALIGHT(직전 역) — trip_push_log PK로
 *   강제 (FR-704, 2026-09-10 폭주 사고 재발 방지). 도착을 지나쳐 발견한 경우 사후 발송하지 않는다
 * - remaining 0 = 하차역 도착: 마지막 구간이면 DONE, 아니면 TRANSFER(수동 재개 대기, FR-703)
 * - 특정 실패(후보 없음·타임아웃)·목격 두절은 LOST — 조용히 틀리지 않는다 (FR-706, NFR-03)
 * - LOST는 종착이 아니다 — 재목격·재특정되면 TRACKING으로 복구한다 (2026-09-15 실측: 지하철
 *   실시간 피드 두절은 흔한데 복구 경로가 없으면 트립이 사실상 죽는다). 단, LOST로 머무는 동안은
 *   저장하지 않아 updated_at이 두절 시점에 묶인다 — 방치 트립 자동 정리(STALE_AFTER)가 살아있어야 한다
 * - 상류 장애는 LOST가 아니다 — realtimeAvailable=false로 표시하고 상태 유지 (NFR-03)
 * - 폴링은 역 단위 스냅샷 재사용(같은 틱 안 캐시) — 같은 하차역의 트립 N개 = 상류 1회 (NFR-08)
 */
@Component
@ConditionalOnProperty("journey.tracking.enabled", havingValue = "true", matchIfMissing = true)
class TripTrackingScheduler(
    private val trips: TripRepository,
    private val trains: TrainPositions,
    private val pushTokens: PushTokenRepository,
    private val fcm: FcmPushClient,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${journey.tracking.interval:PT20S}")
    fun poll() = tick()

    fun tick() {
        val now = LocalDateTime.now(clock)
        // 같은 틱 안에서 역 스냅샷 재사용 — 같은 하차역을 보는 트립들의 상류 호출을 1회로 (NFR-08)
        val snapshots = mutableMapOf<String, Result<List<ApproachingTrain>>>()
        for (trip in trips.findActive()) {
            try {
                process(trip, now, snapshots)
            } catch (e: Exception) {
                log.warn("트립 추적 실패 — trip={} 건너뜀: {}", trip.tripId, e.message)
            }
        }
        // 종료·방치 트립 자동 정리 (§9-3 — 권장 1시간보다 보수적으로 6시간)
        trips.deleteStale(now.minus(STALE_AFTER))
    }

    private fun process(trip: Trip, now: LocalDateTime, snapshots: MutableMap<String, Result<List<ApproachingTrain>>>) {
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
        val matching = approaching.filter { it.matchesLine(leg.line) }

        var tracked = trip.copy(realtimeAvailable = true)
        if (tracked.btrainNo == null) {
            val found = matching.firstOrNull { it.trainNo in tracked.candidates }
            if (found == null) {
                if (wasLost) {
                    return // 특정 실패 LOST — 후보 재등장만 기다린다 (재LOST 판정·저장 없음)
                }
                // 아직 하차역 조회 범위에 안 들어옴 — 타임아웃까지 위치 확인 중
                if (Duration.between(tracked.legStartedAt, now) > IDENTIFY_TIMEOUT) {
                    markLost(tracked, now, "열차 특정 실패(후보=${tracked.candidates.size})")
                    return
                }
                trips.save(tracked, now)
                return
            }
            tracked = tracked.copy(btrainNo = found.trainNo, phase = TripPhase.TRACKING)
            log.info("열차 특정 — trip={} btrainNo={} leg={}", tracked.tripId, found.trainNo, tracked.legIndex)
        }

        val train = matching.firstOrNull { it.trainNo == tracked.btrainNo }
        if (train == null) {
            if (wasLost) {
                return // 목격 두절 LOST — 재목격만 기다린다 (재LOST 판정·저장 없음)
            }
            // 하차역 통과·도착 후엔 목록에서 사라진다 — 직전에 1정거장 이내였다면 도착으로 본다
            if (tracked.remainingStops != null && tracked.remainingStops!! <= 1) {
                arriveAtEvent(tracked, now)
                return
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
        tracked = tracked.copy(remainingStops = remaining, lastSeenAt = now)
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

    /** 하차역 도착 — 마지막 구간이면 DONE, 아니면 TRANSFER(다음 구간 수동 재개 대기) */
    private fun arriveAtEvent(trip: Trip, now: LocalDateTime) {
        val phase = if (trip.isLastLeg) TripPhase.DONE else TripPhase.TRANSFER
        trips.save(trip.copy(phase = phase, remainingStops = 0), now)
        log.info("구간 하차 — trip={} leg={} phase={}", trip.tripId, trip.legIndex, phase)
    }

    private fun markLost(trip: Trip, now: LocalDateTime, reason: String) {
        log.warn("트립 LOST — trip={} leg={}: {}", trip.tripId, trip.legIndex, reason)
        trips.save(trip.copy(phase = TripPhase.LOST, remainingStops = null), now)
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
        /** 열차 특정 제한 — 탑승 후 이 시간 안에 하차역 조회 범위에 들어와야 한다 */
        private val IDENTIFY_TIMEOUT: Duration = Duration.ofMinutes(15)

        /** 특정된 열차가 목록에서 사라진 채 이 시간이 지나면 LOST (순간 누락은 허용) */
        private val LOST_AFTER: Duration = Duration.ofMinutes(3)

        private val STALE_AFTER: Duration = Duration.ofHours(6)
    }
}
