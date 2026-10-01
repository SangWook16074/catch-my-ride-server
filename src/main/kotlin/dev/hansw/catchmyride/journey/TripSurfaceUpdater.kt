package dev.hansw.catchmyride.journey

import dev.hansw.catchmyride.push.ApnsClient
import dev.hansw.catchmyride.push.ApnsTokenInvalidException
import dev.hansw.catchmyride.push.FcmPushClient
import dev.hansw.catchmyride.push.PushTokenRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * API.md §9-5 (v0.12) — 진행 표면(Live Activity·Android 지속 알림) 원격 갱신 오케스트레이션.
 *
 * - **언제**: 표면에 보이는 값(phase·legIndex·remainingStops·currentStop·eventStop·realtimeAvailable)이
 *   직전 발송과 달라졌을 때만 — 같은 값 반복 발송 금지. [afterTick]이 트립 추적 틱에서 "목격 직후
 *   바로" 비교한다(틱 끝 일괄 아님 — TripTrackingScheduler가 트립 하나를 처리할 때마다 바로 부른다).
 *   `next-leg`·`re-identify`·`switch-leg`·`alighted`·`undo-alight` 직후에도 [forceSend]로 1회 보낸다.
 * - **상한**: 트립당 분당 6건(고정 1분 윈도우) — 넘으면 이번 변화는 건너뛰고 "마지막 발송값"을
 *   갱신하지 않는다(다음 변화 때 최신 값으로 다시 시도한다, 발송 실패와 동일하게 재시도 대상).
 * - **채널**: iOS는 트립별 Live Activity push token(ApnsClient, APNs 직접) — DONE이면 `end`.
 *   Android는 §4-1 기존 FCM 토큰(platform ANDROID)으로 데이터 전용 메시지. 하차·환승 알림(§9-4,
 *   FR-704 이벤트당 2회)과 카운트를 공유하지 않는다 — 이건 알림이 아니라 화면 갱신이다.
 */
@Component
class TripSurfaceUpdater(
    private val trips: TripRepository,
    private val surfaceTokens: TripSurfaceTokenRepository,
    private val surfaceStates: TripSurfaceStateRepository,
    private val pushTokens: PushTokenRepository,
    private val apns: ApnsClient,
    private val fcm: FcmPushClient,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 추적 틱에서 트립 하나를 처리한 직후 호출 — 값이 바뀌었을 때만 보낸다 */
    fun afterTick(tripId: String, now: LocalDateTime) {
        trips.find(tripId)?.let { reconcile(it, now, force = false) }
    }

    /** 수동 전환 엔드포인트(next-leg·re-identify·switch-leg·alighted·undo-alight) 직후 1회 강제 발송 */
    fun forceSend(tripId: String, now: LocalDateTime) {
        trips.find(tripId)?.let { reconcile(it, now, force = true) }
    }

    /** DONE·트립 삭제 — iOS에 `aps.event: "end"`. Android는 별도 종료 신호가 없다(표면 값 자체로 충분) */
    fun notifyEnd(trip: Trip, now: LocalDateTime) {
        surfaceTokens.find(trip.tripId)?.let { token ->
            try {
                apns.sendEnd(token.token, contentState(trip), now.atZone(ZONE).toInstant())
            } catch (e: ApnsTokenInvalidException) {
                surfaceTokens.delete(trip.tripId)
            } catch (e: Exception) {
                log.warn("APNs 종료 발송 실패 — trip={}: {}", trip.tripId, e.message)
            }
        }
        surfaceStates.delete(trip.tripId)
    }

    private fun reconcile(trip: Trip, now: LocalDateTime, force: Boolean) {
        val values = trip.surfaceValues()
        val state = surfaceStates.find(trip.tripId)
        val changed = force || state == null || state.differsFrom(values)
        if (!changed) {
            return
        }
        val windowStart = state?.windowStartedAt
        val withinWindow = windowStart != null && Duration.between(windowStart, now) < Duration.ofMinutes(1)
        val countSoFar = if (withinWindow) state!!.sentCount else 0
        if (countSoFar >= MAX_PER_MINUTE) {
            log.info("표면 갱신 상한(분당 {}건) — trip={} 이번 변화는 건너뛴다", MAX_PER_MINUTE, trip.tripId)
            return
        }
        dispatch(trip, values, now)
        surfaceStates.upsert(
            trip.tripId,
            TripSurfaceState(
                lastPhase = values.phase,
                lastLegIndex = values.legIndex,
                lastRemainingStops = values.remainingStops,
                lastCurrentStop = values.currentStop,
                lastEventStop = values.eventStop,
                lastRealtimeAvailable = values.realtimeAvailable,
                sentCount = countSoFar + 1,
                windowStartedAt = if (withinWindow) windowStart else now,
            ),
            now,
        )
    }

    private fun dispatch(trip: Trip, values: SurfaceValues, now: LocalDateTime) {
        val instant = now.atZone(ZONE).toInstant()
        surfaceTokens.find(trip.tripId)?.takeIf { it.platform == "IOS" }?.let { token ->
            try {
                if (values.phase == TripPhase.DONE.name) {
                    apns.sendEnd(token.token, contentState(trip), instant)
                } else {
                    apns.sendUpdate(token.token, contentState(trip), instant)
                }
            } catch (e: ApnsTokenInvalidException) {
                log.warn("APNs 토큰 폐기 — trip={}", trip.tripId)
                surfaceTokens.delete(trip.tripId)
            } catch (e: Exception) {
                log.warn("APNs 발송 실패 — trip={}: {}", trip.tripId, e.message)
            }
        }
        pushTokens.find(trip.userKey)?.takeIf { it.platform == "ANDROID" }?.let { token ->
            try {
                fcm.sendDataMessage(
                    trip.userKey,
                    token,
                    mapOf(
                        "type" to "TRIP_SURFACE",
                        "tripId" to trip.tripId,
                        "phase" to values.phase,
                        "legIndex" to values.legIndex.toString(),
                        "remainingStops" to (values.remainingStops?.toString() ?: ""),
                        "currentStop" to (values.currentStop ?: ""),
                        "eventStop" to values.eventStop,
                    ),
                )
            } catch (e: Exception) {
                log.warn("표면 FCM 발송 실패 — trip={}: {}", trip.tripId, e.message)
            }
        }
    }

    /** `TripActivityAttributes.ContentState`와 같은 키 — 값의 원칙은 §9-3 그대로(목격 값만, 모르면 null) */
    private fun contentState(trip: Trip): Map<String, Any?> = mapOf(
        "eventStop" to trip.currentLeg.alightStop,
        "remainingStops" to trip.remainingStops,
        "phase" to trip.phase.name,
        "currentStop" to trip.currentStop,
    )

    companion object {
        private const val MAX_PER_MINUTE = 6
        private val ZONE = ZoneId.of("Asia/Seoul")
    }
}
