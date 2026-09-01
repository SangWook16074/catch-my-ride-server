package dev.hansw.catchmyride.api

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 모든 API 요청의 응답 시간을 로그로 남긴다 — 프리티어 EC2의 "유휴 후 첫 호출 지연"을
 * 실측하기 위한 최소 장치. 집계(p95 등)는 /actuator/metrics/http.server.requests 로 본다.
 *
 * 로그 한 줄 형식: `GET /api/v1/arrivals 200 1234ms` — docker logs에서 grep으로 추적 가능.
 */
@Component
class RequestTimingFilter : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(javaClass)

    /** 감시 트래픽 자신은 제외 — compose 헬스체크(30초), 모니터링 대시보드 폴링(10초). */
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.requestURI.startsWith("/actuator") ||
            request.requestURI.startsWith("/api/admin/monitoring") ||
            request.requestURI == "/monitoring.html"

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val startNanos = System.nanoTime()
        try {
            filterChain.doFilter(request, response)
        } finally {
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
            val query = request.queryString?.let { "?$it" } ?: ""
            val line = "${request.method} ${request.requestURI}$query ${response.status} ${elapsedMs}ms"
            if (elapsedMs >= SLOW_THRESHOLD_MS) log.warn("SLOW {}", line) else log.info(line)
        }
    }

    companion object {
        /** 이보다 느리면 WARN — 유휴 후 지연 사례를 로그 레벨만으로 걸러낼 수 있게. */
        private const val SLOW_THRESHOLD_MS = 3_000L
    }
}
