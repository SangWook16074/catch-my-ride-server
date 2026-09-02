package dev.hansw.catchmyride.map

import dev.hansw.catchmyride.api.ApiException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * §6·§7 남용 방지 — 인증 없는 엔드포인트가 유료 상류(NCP)를 프록시하므로 IP당 고정 윈도(1분) 제한.
 * 온보딩·재설정에서만 호출되는 트래픽이라 분당 60이면 실사용에 안 걸린다. 단일 인스턴스 인메모리.
 */
@Component
class MapRateLimiter(private val clock: Clock) {

    private data class Window(val startEpochMinute: Long, val count: AtomicInteger)

    private val windows = ConcurrentHashMap<String, Window>()

    fun check(clientIp: String) {
        val minute = clock.instant().epochSecond / 60
        val window = windows.compute(clientIp) { _, w ->
            if (w == null || w.startEpochMinute != minute) Window(minute, AtomicInteger(0)) else w
        }!!
        if (window.count.incrementAndGet() > LIMIT_PER_MINUTE) {
            throw ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "요청이 너무 잦습니다. 잠시 후 다시 시도해주세요")
        }
        if (windows.size > MAX_TRACKED_IPS) {
            windows.entries.removeIf { it.value.startEpochMinute != minute } // 지난 윈도 정리
        }
    }

    companion object {
        private const val LIMIT_PER_MINUTE = 60
        private const val MAX_TRACKED_IPS = 10_000
    }
}
