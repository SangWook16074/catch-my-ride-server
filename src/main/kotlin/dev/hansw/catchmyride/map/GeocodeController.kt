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

    /**
     * §7-1 — 좌표 → 주소(역지오코딩). GPS로 집 위치를 등록했을 때 "어디로 등록됐는지" 보여주기 위한
     * 프록시 (2026-09-09 요구). 주소를 못 찾으면 두 필드 다 빈 문자열 — 클라이언트는 일반 문구로 강등.
     */
    @GetMapping("/api/v1/reverse-geocode")
    fun reverseGeocode(
        @RequestParam latitude: Double,
        @RequestParam longitude: Double,
        request: HttpServletRequest,
    ): ReverseGeocodeResponse {
        rateLimiter.check(request.remoteAddr)
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            throw ApiException.invalidRequest("좌표가 올바르지 않습니다")
        }
        if (!ncp.available()) throw ApiException.upstreamUnavailable("주소 조회 서비스가 설정되지 않았습니다")

        val body = try {
            ncp.reverseGeocode(latitude, longitude)
        } catch (e: Exception) {
            log.warn("Reverse geocoding 상류 실패: {}", e.message)
            throw ApiException.upstreamUnavailable("주소 조회에 실패했습니다")
        }
        return parseReverse(body)
    }

    /**
     * 상류 `results[]` → 도로명/지번 한 건씩. NCP gc 응답은 name(roadaddr/addr)별로
     * region.area1~4 + land(도로명은 name+number, 지번은 number1-number2)로 쪼개져 있어 직접 조립한다.
     */
    internal fun parseReverse(body: String): ReverseGeocodeResponse {
        var road = ""
        var jibun = ""
        objectMapper.readTree(body).path("results").forEach { item ->
            val region = item.path("region")
            val areas = (1..4).mapNotNull { region.path("area$it").textOrNull("name") }
                .filter { it.isNotBlank() }
            val land = item.path("land")
            val number = listOfNotNull(land.textOrNull("number1"), land.textOrNull("number2"))
                .filter { it.isNotBlank() }
                .joinToString("-")
            when (item.textOrNull("name")) {
                "roadaddr" -> if (road.isEmpty()) {
                    // 도로명주소는 동(洞)을 쓰지 않는다 — 읍·면만 시군구 뒤에 남긴다 (도로명주소법 표기)
                    val roadAreas = areas.take(2) + areas.drop(2).filter { it.endsWith("읍") || it.endsWith("면") }
                    val roadName = land.textOrNull("name").orEmpty()
                    road = (roadAreas + roadName + number).filter { it.isNotBlank() }.joinToString(" ")
                }
                "addr" -> if (jibun.isEmpty()) {
                    jibun = (areas + number).filter { it.isNotBlank() }.joinToString(" ")
                }
            }
        }
        return ReverseGeocodeResponse(roadAddress = road, jibunAddress = jibun)
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

/** §7-1 — 좌표의 도로명/지번 주소. 못 찾은 쪽은 빈 문자열 */
data class ReverseGeocodeResponse(val roadAddress: String, val jibunAddress: String)

data class GeocodeResult(
    val roadAddress: String,
    val jibunAddress: String,
    val latitude: Double,
    val longitude: Double,
)
