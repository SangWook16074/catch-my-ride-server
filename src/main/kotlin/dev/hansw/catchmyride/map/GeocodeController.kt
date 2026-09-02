package dev.hansw.catchmyride.map

import dev.hansw.catchmyride.api.ApiException
import dev.hansw.catchmyride.spike.adapter.textOrNull
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper

/**
 * API.md §7 — 주소 검색(지오코딩) 프록시. 집 위치를 GPS 대신 주소로 등록 (FR-101 보완).
 * NCP Geocoding 응답을 계약 형태로 매핑 — 최대 10건, 결과 없음은 빈 배열(에러 아님).
 * 인증 불필요(§5·§6과 동일). 키 미설정·상류 장애는 503.
 */
@RestController
class GeocodeController(
    private val ncp: NcpMapClient,
    private val rateLimiter: MapRateLimiter,
    private val objectMapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/api/v1/geocode")
    fun geocode(@RequestParam query: String, request: HttpServletRequest): GeocodeResponse {
        rateLimiter.check(request.remoteAddr)
        if (query.trim().length < 2) throw ApiException.invalidRequest("검색어는 2자 이상이어야 합니다")
        if (!ncp.available()) throw ApiException.upstreamUnavailable("주소 검색 서비스가 설정되지 않았습니다")

        val body = try {
            ncp.geocode(query.trim())
        } catch (e: Exception) {
            log.warn("Geocoding 상류 실패: {}", e.message)
            throw ApiException.upstreamUnavailable("주소 검색에 실패했습니다")
        }
        return GeocodeResponse(parse(body))
    }

    /** 상류 `addresses[]` → 계약 매핑. x=경도·y=위도 문자열(NCP 규약) — 숫자 변환 실패 행은 제외 */
    internal fun parse(body: String): List<GeocodeResult> =
        objectMapper.readTree(body).path("addresses")
            .mapNotNull { item ->
                val longitude = item.textOrNull("x")?.toDoubleOrNull() ?: return@mapNotNull null
                val latitude = item.textOrNull("y")?.toDoubleOrNull() ?: return@mapNotNull null
                GeocodeResult(
                    roadAddress = item.path("roadAddress").asString(""),
                    jibunAddress = item.path("jibunAddress").asString(""),
                    latitude = latitude,
                    longitude = longitude,
                )
            }
            .take(MAX_RESULTS)

    companion object {
        private const val MAX_RESULTS = 10
    }
}

data class GeocodeResponse(val results: List<GeocodeResult>)

data class GeocodeResult(
    val roadAddress: String,
    val jibunAddress: String,
    val latitude: Double,
    val longitude: Double,
)
