package dev.hansw.catchmyride.arrivals

import dev.hansw.catchmyride.api.UserKeyResolver
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/** API.md §2 — 라이브 뷰 도착 정보. 클라이언트가 15~30초 간격 폴링 (FR-204). */
@RestController
class ArrivalsController(
    private val service: ArrivalsService,
    private val userKeys: UserKeyResolver,
) {

    @GetMapping("/api/v1/arrivals")
    fun arrivals(@RequestHeader(value = "Authorization", required = false) auth: String?): ArrivalsResponse =
        service.arrivals(userKeys.resolve(auth))
}
