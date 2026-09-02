package dev.hansw.catchmyride.push

import dev.hansw.catchmyride.arrivals.ArrivalsService
import dev.hansw.catchmyride.commute.CommuteSetting
import dev.hansw.catchmyride.commute.CommuteSettingRepository
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
 * S-5 알림 스케줄러 — 30초마다 전체 통근 설정을 훑어 발송 시점이 된 유저에게 푸시를 트리거한다.
 *
 * 불변 조건 (테스트가 명세):
 * - 출근 1회당 최대 2회(PRE·REMIND), 같은 스테이지 재발송 불가 — push_log PK로 강제 (FR-403)
 * - REMIND가 나간 날은 그날 발송 종료, PRE는 REMIND 이후에 나가지 않는다
 * - activeDays에 없는 요일은 발송하지 않는다 (FR-405)
 * - 시간대 밖 유저는 공공 API 폴링 자체를 하지 않는다 (NFR-08 쿼터 보호)
 *
 * 발송 순서는 "로그 선기록 → 발송 → delivered 갱신, 실패 시 로그 롤백" — 어떤 크래시 시점에도
 * 재발송이 최대 1회 지연될 뿐 초과 발송은 없다. 단일 인스턴스 전제(DEPLOY.md)라 분산 락은 두지 않는다.
 */
@Component
@ConditionalOnProperty("push.enabled", havingValue = "true", matchIfMissing = true)
class PushNotificationScheduler(
    private val settings: CommuteSettingRepository,
    private val pushLog: PushLogRepository,
    private val arrivalsService: ArrivalsService,
    private val timing: DepartureTimingService,
    private val client: AppsInTossPushClient,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${push.interval:PT30S}")
    fun poll() = tick()

    fun tick() {
        val now = ZonedDateTime.now(clock)
        for (stored in settings.findAll()) {
            try {
                process(stored.userKey, stored.setting, now.toLocalDate(), now.toLocalTime(), now.dayOfWeek)
            } catch (e: Exception) {
                log.warn("푸시 판단 실패 — user={} 건너뜀: {}", stored.userKey, e.message)
            }
        }
    }

    private fun process(userKey: String, setting: CommuteSetting, today: LocalDate, now: LocalTime, day: DayOfWeek) {
        if (DAY_CODES.getValue(day) !in setting.activeDays) return // FR-405
        if (!timing.withinCandidateWindow(setting, now)) return    // 공공 API 호출 전 싸구려 필터

        val sent = pushLog.sentStages(userKey, today)
        if (PushStage.REMIND in sent) return // 그날 발송 완료 (FR-401 — 이후 갱신·사후 알림 없음)

        val arrivals = arrivalsService.arrivalsFor(setting).arrivals
        val decision = timing.decide(setting, now, arrivals) ?: return
        if (decision.stage == PushStage.PRE && PushStage.PRE in sent) return

        pushLog.record(userKey, today, decision.stage, decision.routeName, delivered = false)
        try {
            if (client.send(userKey, decision)) pushLog.markDelivered(userKey, today, decision.stage)
        } catch (e: Exception) {
            pushLog.delete(userKey, today, decision.stage) // 다음 틱에 재시도
            log.warn("푸시 발송 최종 실패 — user={} stage={}, 다음 틱 재시도: {}", userKey, decision.stage, e.message)
        }
    }

    companion object {
        private val DAY_CODES = mapOf(
            DayOfWeek.MONDAY to "MON", DayOfWeek.TUESDAY to "TUE", DayOfWeek.WEDNESDAY to "WED",
            DayOfWeek.THURSDAY to "THU", DayOfWeek.FRIDAY to "FRI",
            DayOfWeek.SATURDAY to "SAT", DayOfWeek.SUNDAY to "SUN",
        )
    }
}
