package dev.hansw.catchmyride.map

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 네이버클라우드(NCP) Maps 키 — Static Map·Geocoding 공용 (API.md §6·§7).
 * 키는 서버 env로만 관리(클라이언트·저장소 노출 금지). 미설정이면 두 엔드포인트는 503 —
 * 클라이언트가 OSM 폴백/안내 문구로 강등하므로 배포 순서 제약 없음 (SERVER_FEEDBACK.md 2026-09-02).
 */
@ConfigurationProperties("ncp.maps")
data class NcpMapProperties(
    val keyId: String = "",
    val key: String = "",
    val staticMapUrl: String = "https://maps.apigw.ntruss.com/map-static/v2/raster",
    val geocodeUrl: String = "https://maps.apigw.ntruss.com/map-geocode/v2/geocode",
)

/** NCP Maps 상류 호출 — 파싱·검증은 컨트롤러 몫, 여기는 인증 헤더와 전송만 담당한다. */
@Component
class NcpMapClient(private val props: NcpMapProperties) {

    private val rest = RestClient.create()

    fun available() = props.keyId.isNotBlank() && props.key.isNotBlank()

    /** §6 — center는 `경도,위도` 순서 (NCP 규약 주의) */
    fun staticMap(lat: Double, lng: Double, w: Int, h: Int, level: Int): ByteArray =
        get("${props.staticMapUrl}?w=$w&h=$h&center=$lng,$lat&level=$level")
            .body(ByteArray::class.java) ?: ByteArray(0)

    /** §7 — 응답 JSON 원문 반환 (파싱은 GeocodeController.parse) */
    fun geocode(query: String): String {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
        return get("${props.geocodeUrl}?query=$encoded").body(String::class.java).orEmpty()
    }

    private fun get(url: String) =
        rest.get().uri(URI.create(url))
            .header("X-NCP-APIGW-API-KEY-ID", props.keyId)
            .header("X-NCP-APIGW-API-KEY", props.key)
            .retrieve()
}
