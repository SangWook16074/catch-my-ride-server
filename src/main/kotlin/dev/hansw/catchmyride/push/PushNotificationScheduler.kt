package dev.hansw.catchmyride.push

import dev.hansw.catchmyride.arrivals.ArrivalsService
import dev.hansw.catchmyride.commute.CommuteRoute
import dev.hansw.catchmyride.commute.CommuteRouteRepository
import dev.hansw.catchmyride.commute.NotificationMode
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * S-5 알림 스케줄러 — 30초마다 전체 통근 경로(enabled)를 훑어 발송 시점이 된 유저에게 푸시를 트리거한다.
 *
 * 불변 조건 (테스트가 명세):
 * - 사이클(안내 대상 차량 1대)당 최대 2회(PRE·REMIND), 같은 사이클 내 스테이지 재발송 불가 — push_log PK로 강제 (FR-403).
 *   경로가 여러 개면(출근·퇴근) 각 경로가 독립으로 판단·발송된다
 * - FIXED: REMIND가 나간 날은 그 경로의 발송 종료(하루 최대 2회), PRE는 REMIND 이후에 나가지 않는다
 * - RECOMMENDED(2026-09-09 FR-403 개정): 시간대 안이면 REMIND 후에도 다음 차 기준 새 사이클(PRE→REMIND)을
 *   반복한다. REMIND 직후 곧바로 REMIND는 금지 — 반드시 PRE를 거친다
 * - 사이클 폭주 제동 (2026-09-10 사고 — 러시아워 배차 2~3분에 시간당 30건 발송):
 *   ① 새 사이클은 직전 REMIND 후 10분 경과해야 시작 ② 경로·하루 최대 3사이클
 *   ③ 당일 "탔어요"(BOARDED) 피드백이 있으면 추가 사이클 중단 (첫 사이클은 경로별 독립이라 허용)
 * - activeDays에 없는 요일은 발송하지 않는다 (FR-405)
 * - 평일만 출근하는 경로는 공휴일에도 발송하지 않는다 (FR-405 확장, 2026-09-07 명세서 §9).
 *   주말이 포함된 경로(교대 근무 등)는 달력을 따르지 않는 근무라 공휴일에도 그대로 보낸다
 * - 시간대 밖 경로는 공공 API 폴링 자체를 하지 않는다 (NFR-08 쿼터 보호)
 *
 * 발송 순서는 "로그 선기록 → 발송 → delivered 갱신, 실패 시 로그 롤백" — 어떤 크래시 시점에도
 * 재발송이 최대 1회 지연될 뿐 초과 발송은 없다. 단일 인스턴스 전제(DEPLOY.md)라 분산 락은 두지 않는다.
 */
@Component
@ConditionalOnProperty("push.enabled", havingValue = "true", matchIfMissing = true)
class PushNotificationScheduler(
    private val routes: CommuteRouteRepository,
    private val pushLog: PushLogRepository,
    private val arrivalsService: ArrivalsService,
    private val timing: DepartureTimingService,
    private val client: AppsInTossPushClient,
    private val pushTokens: PushTokenRepository,
    private val fcm: FcmPushClient,
    private val holidays: HolidayCalendar,
    private val clock: Clock,
    private val feedback: dev.hansw.catchmyride.feedback.BoardingFeedbackRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${push.interval:PT30S}")
    fun poll() = tick()

    fun tick() {
        val now = ZonedDateTime.now(clock)
        for (stored in routes.findAllEnabled()) {
            try {
                process(stored.userKey, stored.route, now.toLocalDate(), now.toLocalDateTime(), now.dayOfWeek)
            } catch (e: Exception) {
                log.warn("푸시 판단 실패 — user={} route={} 건너뜀: {}", stored.userKey, stored.route.id, e.message)
            }
        }
    }

    private fun process(userKey: String, route: CommuteRoute, today: LocalDate, nowDateTime: java.time.LocalDateTime, day: DayOfWeek) {
        val setting = route.setting
        val now: LocalTime = nowDateTime.toLocalTime()
        if (DAY_CODES.getValue(day) !in setting.activeDays) return // FR-405
        if (setting.weekdaysOnly && holidays.isHoliday(today)) return // FR-405 확장 — 공휴일 미발송
        if (!timing.withinCandidateWindow(setting, now)) return    // 공공 API 호출 전 싸구려 필터

        val last = pushLog.lastEntry(userKey, route.id, today)
        // FIXED는 REMIND로 그날 발송 종료 (FR-401 — 이후 갱신·사후 알림 없음).
        // RECOMMENDED는 시간대 안이면 다음 차로 새 사이클을 돈다 (2026-09-09 FR-403 개정)
        if (last?.stage == PushStage.REMIND && setting.notificationMode == NotificationMode.FIXED) return
        // 폭주 제동 (2026-09-10 사고): 새 사이클을 시작할 수 없는 상태면 공공 API 폴링도 하지 않는다
        if (last?.stage == PushStage.REMIND) {
            if (last.cycle + 1 >= MAX_DAILY_CYCLES) return // 경로·하루 3사이클 상한
            if (java.time.Duration.between(last.sentAt, nowDateTime) < MIN_CYCLE_GAP) return // 리마인드 후 10분 침묵
            if (feedback.boardedOn(userKey, today)) return // 이미 탔다고 답한 유저 — 다음 차 안내는 소음
        }

        val arrivals = arrivalsService.arrivalsFor(setting).arrivals
        val decision = timing.decide(setting, now, arrivals) ?: return
        val cycle = when {
            last == null -> 0
            // 같은 사이클 내 PRE 중복 금지 — REMIND만 통과
            last.stage == PushStage.PRE -> if (decision.stage == PushStage.REMIND) last.cycle else return
            // REMIND 직후 REMIND 금지 — PRE로 시작하는 새 사이클만 (RECOMMENDED만 여기 도달)
            else -> if (decision.stage == PushStage.PRE) last.cycle + 1 else return
        }

        pushLog.record(
            userKey, route.id, today, decision.stage, decision.routeName,
            delivered = false, cycle = cycle, sentAt = nowDateTime,
        )
        try {
            // 발송 분기 (API.md §4-1): FCM 토큰이 있는 유저(스토어앱)는 FCM, 없으면 앱인토스.
            // "선기록 → 발송 → delivered 갱신 → 실패 시 롤백" 불변식은 양쪽 공통
            val delivered = pushTokens.find(userKey)
                ?.let { fcm.send(userKey, it, decision, today) }
                ?: client.send(userKey, decision)
            if (delivered) pushLog.markDelivered(userKey, route.id, today, decision.stage, cycle)
        } catch (e: Exception) {
            pushLog.delete(userKey, route.id, today, decision.stage, cycle) // 다음 틱에 재시도
            log.warn(
                "푸시 발송 최종 실패 — user={} route={} stage={}, 다음 틱 재시도: {}",
                userKey, route.id, decision.stage, e.message,
            )
        }
    }

    companion object {
        /** 경로·하루 최대 사이클 수 — 최대 푸시 6건 (2026-09-10 폭주 사고 상한) */
        private const val MAX_DAILY_CYCLES = 3

        /** 직전 리마인드 → 다음 사이클 사전 알림 최소 간격 — 러시아워 배차(2~3분)가 스로틀이 못 되므로 명시 */
        private val MIN_CYCLE_GAP: java.time.Duration = java.time.Duration.ofMinutes(10)

        private val DAY_CODES = mapOf(
            DayOfWeek.MONDAY to "MON", DayOfWeek.TUESDAY to "TUE", DayOfWeek.WEDNESDAY to "WED",
            DayOfWeek.THURSDAY to "THU", DayOfWeek.FRIDAY to "FRI",
            DayOfWeek.SATURDAY to "SAT", DayOfWeek.SUNDAY to "SUN",
        )
    }
}
