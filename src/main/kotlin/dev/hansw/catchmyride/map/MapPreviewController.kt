package dev.hansw.catchmyride.map

import dev.hansw.catchmyride.api.ApiException
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.round

/**
 * API.md §6 — 지도 미리보기 프록시 (온보딩/재설정 집 위치 단계).
 * NCP Static Map을 그대로 패스스루 — 마커는 클라이언트가 오버레이하므로 순수 지도만 반환.
 * 인증 불필요(§5와 동일 — 설정 없이 호출됨). 키 미설정·상류 장애는 503 → 클라이언트 OSM 폴백.
 */
@RestController
class MapPreviewController(
    private val ncp: NcpMapClient,
    private val rateLimiter: MapRateLimiter,
    private val cache: MapPreviewCache,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/api/v1/map-preview")
    fun preview(
        @RequestParam lat: Double,
        @RequestParam lng: Double,
        @RequestParam(defaultValue = "708") w: Int,
        @RequestParam(defaultValue = "320") h: Int,
        @RequestParam(defaultValue = "16") level: Int,
        request: HttpServletRequest,
    ): ResponseEntity<ByteArray> {
        rateLimiter.check(request.remoteAddr)
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) {
            throw ApiException.invalidRequest("좌표 범위를 벗어났습니다 (lat -90~90, lng -180~180)")
        }
        if (!ncp.available()) throw ApiException.upstreamUnavailable("지도 서비스가 설정되지 않았습니다")

        // 계약: w·h는 1~1024 클램프(에러 아님), level 기본 16 — NCP 유효 범위로 보정
        val width = w.coerceIn(1, 1024)
        val height = h.coerceIn(1, 1024)
        val zoom = level.coerceIn(0, 20)

        val png = cache.getOrFetch(lat, lng, width, height, zoom) {
            try {
                ncp.staticMap(lat, lng, width, height, zoom)
            } catch (e: Exception) {
                log.warn("Static Map 상류 실패: {}", e.message)
                throw ApiException.upstreamUnavailable("지도 이미지를 가져오지 못했습니다")
            }
        }
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(png)
    }
}

/**
 * 좌표 소수 4자리(약 11m) 반올림 키 캐시 — 같은 집 위치 반복 조회의 NCP 호출량 절감 (SERVER_FEEDBACK.md 권장).
 * TTL 6시간, 최대 500장(장당 수십 KB — 힙 수십 MB 상한). 단일 인스턴스 인메모리.
 */
@Component
class MapPreviewCache(private val clock: Clock) {

    private data class Entry(val png: ByteArray, val at: Instant)

    private val entries = ConcurrentHashMap<String, Entry>()

    fun getOrFetch(lat: Double, lng: Double, w: Int, h: Int, level: Int, fetch: () -> ByteArray): ByteArray {
        val key = "${round4(lat)},${round4(lng)},$w,$h,$level"
        val now = clock.instant()
        entries[key]?.let { if (Duration.between(it.at, now) < TTL) return it.png }
        val png = fetch()
        if (png.isNotEmpty()) {
            if (entries.size >= MAX_ENTRIES) {
                entries.entries.minByOrNull { it.value.at }?.let { entries.remove(it.key) }
            }
            entries[key] = Entry(png, now)
        }
        return png
    }

    private fun round4(v: Double) = round(v * 10_000) / 10_000

    companion object {
        private val TTL = Duration.ofHours(6)
        private const val MAX_ENTRIES = 500
    }
}
