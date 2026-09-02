package dev.hansw.catchmyride.push

import dev.hansw.catchmyride.arrivals.Arrival
import dev.hansw.catchmyride.commute.CommuteSetting
import dev.hansw.catchmyride.commute.NotificationMode
import org.springframework.stereotype.Service
import java.time.LocalTime

/**
 * S-4 출발 타이밍 계산 — "지금 이 순간 어떤 알림이 나가야 하는가"를 판단하는 순수 로직.
 * 스케줄러(S-5)가 30초마다 호출하며, 시각·도착 정보를 전부 인자로 받아 테스트가 명세가 된다.
 *
 * - FIXED(ⓐ 정시, FR-401): 유저가 정한 출발 시각 기준. ① 사전 = 출발−버퍼 ② 리마인드 = 출발−1분(무조건).
 * - RECOMMENDED(ⓑ 추천, FR-301): 출근 시간대 안에서 가장 가까운 "탑승 가능" 차량 기준으로
 *   출발 시각(도착까지 남은 시간 − 도보)을 역산해 같은 2단계로 알린다.
 */
@Service
class DepartureTimingService {

    /** 도착 정보 조회(공공 API) 없이 판단 가능한 싸구려 사전 필터 — 시간대 밖 유저는 폴링하지 않는다 */
    fun withinCandidateWindow(setting: CommuteSetting, now: LocalTime): Boolean = when (setting.notificationMode) {
        NotificationMode.FIXED -> {
            val departure = LocalTime.parse(setting.fixedDepartureTime!!)
            !now.isBefore(departure.minusMinutes(setting.bufferMinutes.toLong() + 1)) &&
                now.isBefore(departure.plusMinutes(1))
        }
        NotificationMode.RECOMMENDED -> {
            val window = setting.commuteWindow!!
            !now.isBefore(LocalTime.parse(window.start)) && !now.isAfter(LocalTime.parse(window.end))
        }
    }

    fun decide(setting: CommuteSetting, now: LocalTime, arrivals: List<Arrival>): PushDecision? =
        when (setting.notificationMode) {
            NotificationMode.FIXED -> decideFixed(setting, now, arrivals)
            NotificationMode.RECOMMENDED -> decideRecommended(setting, now, arrivals)
        }

    private fun decideFixed(setting: CommuteSetting, now: LocalTime, arrivals: List<Arrival>): PushDecision? {
        val departure = LocalTime.parse(setting.fixedDepartureTime!!)
        val remindFrom = departure.minusMinutes(1)
        val preFrom = departure.minusMinutes(setting.bufferMinutes.toLong())
        val stage = when {
            // 리마인드는 실시간 정보가 없어도 무조건 나간다 (FR-401 ②). 출발+1분까지는 틱 지연을 흡수한다.
            !now.isBefore(remindFrom) && now.isBefore(departure.plusMinutes(1)) -> PushStage.REMIND
            !now.isBefore(preFrom) && now.isBefore(remindFrom) -> PushStage.PRE
            else -> return null
        }
        val best = closestWithInfo(arrivals)
        return PushDecision(
            stage = stage,
            departureTime = departure,
            routeName = best?.routeName ?: setting.stops.first().routes.first(),
            minutesToArrival = best?.secondsToArrival?.let { it / 60 },
        )
    }

    private fun decideRecommended(setting: CommuteSetting, now: LocalTime, arrivals: List<Arrival>): PushDecision? {
        val window = setting.commuteWindow!!
        if (now.isBefore(LocalTime.parse(window.start)) || now.isAfter(LocalTime.parse(window.end))) return null
        // 가장 가까운 탑승 가능 차량 — 이 차를 잡으려면 (도착까지 − 도보)초 안에 나가야 한다
        val target = arrivals
            .filter { it.boardable && it.secondsToArrival != null }
            .minByOrNull { it.secondsToArrival!! }
            ?: return null // 실시간 정보 없음/전부 놓침 — 아는 척 금지 (NFR-03), 다음 틱에 재판단
        val leaveInSeconds = target.secondsToArrival!! - setting.walkMinutes * 60
        val stage = when {
            leaveInSeconds <= 60 -> PushStage.REMIND
            leaveInSeconds <= setting.bufferMinutes * 60 -> PushStage.PRE
            else -> return null // 아직 여유 — 알릴 필요 없음
        }
        return PushDecision(
            stage = stage,
            departureTime = now.plusSeconds(leaveInSeconds.toLong()).withNano(0),
            routeName = target.routeName,
            minutesToArrival = target.secondsToArrival!! / 60,
        )
    }

    /** 템플릿 변수({route}, {minutes})용 — 탑승 가능 중 최근접, 없으면 정보 있는 최근접 */
    private fun closestWithInfo(arrivals: List<Arrival>): Arrival? {
        val withInfo = arrivals.filter { it.secondsToArrival != null }
        return withInfo.filter { it.boardable }.minByOrNull { it.secondsToArrival!! }
            ?: withInfo.minByOrNull { it.secondsToArrival!! }
    }
}

/** 지금 발송해야 할 알림 1건 — 스테이지와 템플릿 변수 재료 (docs/push-templates.md) */
data class PushDecision(
    val stage: PushStage,
    val departureTime: LocalTime,
    val routeName: String?,
    val minutesToArrival: Int?, // null = 실시간 정보 없음 — 템플릿 context에서 빈 값 처리
)
