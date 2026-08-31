package dev.hansw.catchmyride.stops

import dev.hansw.catchmyride.api.ApiException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * API.md §5 — 정류장/역 검색 (온보딩 FR-102·103).
 * 인증(토스 Bearer)은 S-3에서 일괄 적용 예정 — clientId 발급(🧑 콘솔 약관 동의) 대기 중.
 */
@RestController
@RequestMapping("/api/v1/stops")
class StopsController(private val service: StopSearchService) {

    @GetMapping("/search")
    fun search(@RequestParam query: String): StopSearchResponse =
        StopSearchResponse(service.search(query))

    @GetMapping("/routes")
    fun routes(@RequestParam type: String, @RequestParam stopId: String): StopRoutesResponse {
        val stopType = runCatching { StopType.valueOf(type) }
            .getOrElse { throw ApiException.invalidRequest("type은 SEOUL_BUS/GYEONGGI_BUS/SUBWAY 중 하나여야 합니다") }
        return StopRoutesResponse(service.routes(stopType, stopId))
    }
}
