package dev.hansw.catchmyride.api

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.env.Environment
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Duration

/**
 * 프리티어 EC2에서 장시간 유휴 후 첫 API 응답이 느려지는 문제의 완화 장치.
 *
 * compose 헬스체크(30초 주기 /actuator/health)는 헬스 경로만 데우고, 실제 API가 쓰는
 * MVC 디스패치·Jackson 직렬화·Hikari 커넥션 경로는 유휴 상태로 남는다(메모리 페이지 아웃,
 * 유휴 커넥션 정리). 그래서 실제 API 경로를 주기적으로 자기 호출해 상시 웜 상태를 유지한다.
 *
 * - 기동 직후 1회: 배포/재시작 후 첫 실요청이 초기화 비용을 내지 않게 미리 데운다.
 * - 이후 warmup.interval(기본 4분) 주기: JVM 페이지·DB 커넥션을 계속 상주시킨다.
 * - 대상은 /api/v1/commute-setting — 외부 공공 API를 타지 않아 쿼터 소모가 없다.
 *   (401/404 응답이어도 경로를 데우는 효과는 동일하므로 상태코드는 무시한다.)
 */
@Component
@ConditionalOnProperty("warmup.enabled", havingValue = "true", matchIfMissing = true)
class KeepAliveWarmer(
    private val environment: Environment,
    private val jdbc: JdbcTemplate,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var baseUrl: String? = null

    private val client = RestClient.builder()
        .requestFactory(JdkClientHttpRequestFactory().apply { setReadTimeout(Duration.ofSeconds(10)) })
        .build()

    @EventListener(ApplicationReadyEvent::class)
    fun warmAtStartup() {
        // RANDOM_PORT 환경(테스트 등)에서는 local.server.port가 실제 포트를 가진다
        val port = environment.getProperty("local.server.port")
            ?: environment.getProperty("server.port", "8080")
        baseUrl = "http://localhost:$port"
        warm("startup")
    }

    @Scheduled(fixedDelayString = "\${warmup.interval:PT4M}", initialDelayString = "\${warmup.interval:PT4M}")
    fun keepAlive() = warm("keep-alive")

    private fun warm(reason: String) {
        val base = baseUrl ?: return // 서버 기동 전 스케줄 진입 방어
        try {
            jdbc.queryForObject("SELECT 1", Int::class.java) // Hikari 커넥션 유지
            client.get().uri("$base/api/v1/commute-setting")
                .retrieve()
                .onStatus({ true }) { _, _ -> } // 어떤 상태코드든 성공으로 취급
                .toBodilessEntity()
        } catch (e: Exception) {
            log.warn("워밍업({}) 실패: {}", reason, e.message)
        }
    }
}
