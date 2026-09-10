package dev.hansw.catchmyride.admin

import ch.qos.logback.classic.Level
import dev.hansw.catchmyride.api.ApiException
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.lang.management.ManagementFactory
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.round

/**
 * 운영 모니터링 어드민 API — /monitoring.html 대시보드가 사용한다.
 *
 * micrometer의 http.server.requests 타이머(액추에이터가 자동 기록)를 엔드포인트 단위로
 * 묶어 요청 횟수·응답 시간 분포를 한 번에 내려준다. 수치는 서버 기동 이후 누적(인메모리) —
 * 재배포하면 리셋된다. 개별 요청 이력은 RequestTimingFilter 로그로 본다.
 *
 * 보호: ADMIN_TOKEN 환경변수가 설정돼 있으면 X-Admin-Token 헤더(또는 ?token=)가 일치해야 한다.
 * 빈 값이면 개방 — dev-user-key와 같은 Phase 1 편의. VM .env에는 반드시 넣을 것.
 */
@RestController
class AdminMonitoringController(
    private val registry: MeterRegistry,
    @Value("\${admin.token}") private val adminToken: String,
) {

    private val startedAt = Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().startTime).toString()

    @GetMapping("/api/admin/monitoring")
    fun monitoring(
        @RequestHeader(value = "X-Admin-Token", required = false) headerToken: String?,
        @RequestParam(value = "token", required = false) queryToken: String?,
        @RequestParam(value = "all", defaultValue = "false") all: Boolean,
    ): MonitoringResponse {
        authorize(headerToken ?: queryToken)

        // 타이머는 (method, uri, status, outcome) 조합마다 따로 존재 — 화면용으로 (method, uri)로 합친다
        val timers = registry.find("http.server.requests").timers()
            .filter { all || !isNoise(it.id.getTag("uri").orEmpty()) }
        val endpoints = timers
            .groupBy { EndpointKey(it.id.getTag("method") ?: "?", it.id.getTag("uri") ?: "?") }
            .map { (key, group) -> toStats(key, group) }
            .sortedByDescending { it.count }

        val runtime = Runtime.getRuntime()
        return MonitoringResponse(
            serverStartedAt = startedAt,
            uptimeSeconds = ManagementFactory.getRuntimeMXBean().uptime / 1000,
            heapUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / MB,
            heapMaxMb = runtime.maxMemory() / MB,
            totalRequests = endpoints.sumOf { it.count },
            endpoints = endpoints,
        )
    }

    private fun toStats(key: EndpointKey, group: List<Timer>): EndpointStats {
        val count = group.sumOf { it.count() }
        val statusCounts = group
            .groupBy { it.id.getTag("status") ?: "?" }
            .mapValues { (_, ts) -> ts.sumOf { it.count() } }
        val snapshots = group.map { it.takeSnapshot() }
        // 상태코드별 타이머의 백분위는 정확히 합칠 수 없어 최대값을 취한다(보수적 근사)
        fun percentile(p: Double): Double = snapshots.maxOf { s ->
            s.percentileValues().firstOrNull { abs(it.percentile() - p) < 1e-9 }?.value(TimeUnit.MILLISECONDS) ?: 0.0
        }
        val meanMs = if (count == 0L) 0.0
        else group.sumOf { it.mean(TimeUnit.MILLISECONDS) * it.count() } / count

        return EndpointStats(
            method = key.method,
            uri = key.uri,
            count = count,
            statusCounts = statusCounts,
            meanMs = meanMs.round1(),
            p50Ms = percentile(0.5).round1(),
            p95Ms = percentile(0.95).round1(),
            p99Ms = percentile(0.99).round1(),
            maxMs = group.maxOf { it.max(TimeUnit.MILLISECONDS) }.round1(),
        )
    }

    /**
     * 최근 로그 조회 — LogBuffer(인메모리 링버퍼)를 최신순으로 내려준다.
     * level=WARN(기본)은 에러 전용 버퍼를 봐서 요청 INFO 로그에 밀려난 에러도 남아 있다.
     */
    @GetMapping("/api/admin/logs")
    fun logs(
        @RequestHeader(value = "X-Admin-Token", required = false) headerToken: String?,
        @RequestParam(value = "token", required = false) queryToken: String?,
        @RequestParam(value = "level", defaultValue = "WARN") level: String,
        @RequestParam(value = "limit", defaultValue = "200") limit: Int,
        @RequestParam(value = "q", required = false) q: String?,
    ): LogsResponse {
        authorize(headerToken ?: queryToken)
        val minLevel = Level.toLevel(level.uppercase(), Level.WARN)
        val entries = LogBuffer.query(minLevel, limit.coerceIn(1, 1000), q?.takeIf { it.isNotBlank() })
        return LogsResponse(level = minLevel.toString(), count = entries.size, entries = entries)
    }

    private fun authorize(token: String?) {
        if (adminToken.isNotBlank() && token != adminToken) throw ApiException.unauthorized()
    }

    /** 기본 화면에선 감시 트래픽 자신(actuator·어드민·대시보드)은 숨긴다 — ?all=true로 포함 */
    private fun isNoise(uri: String): Boolean =
        uri.startsWith("/actuator") || uri.startsWith("/api/admin") || uri == "/monitoring.html"

    private fun Double.round1(): Double = if (isNaN()) 0.0 else round(this * 10) / 10

    private data class EndpointKey(val method: String, val uri: String)

    companion object {
        private const val MB = 1024L * 1024L
    }
}

data class MonitoringResponse(
    val serverStartedAt: String,
    val uptimeSeconds: Long,
    val heapUsedMb: Long,
    val heapMaxMb: Long,
    val totalRequests: Long,
    val endpoints: List<EndpointStats>,
)

data class LogsResponse(
    val level: String,
    val count: Int,
    val entries: List<LogEntry>, // 최신순
)

data class EndpointStats(
    val method: String,
    val uri: String,
    val count: Long,
    val statusCounts: Map<String, Long>,
    val meanMs: Double,
    val p50Ms: Double,
    val p95Ms: Double,
    val p99Ms: Double,
    val maxMs: Double,
)
