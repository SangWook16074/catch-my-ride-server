package dev.hansw.catchmyride.commute

import dev.hansw.catchmyride.api.ApiException
import dev.hansw.catchmyride.api.UserKeyResolver
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * API.md §1-2 — 통근 경로 CRUD (유저당 최대 5개, 출근·퇴근 등 라벨 구분).
 * id는 서버가 발급한다. 순서는 생성순 — 첫 경로가 레거시 API·routeId 생략 시의 기본 경로.
 */
@RestController
@RequestMapping("/api/v1/commute-routes")
class CommuteRouteController(
    private val routes: CommuteRouteRepository,
    private val userKeys: UserKeyResolver,
) {

    /** 생성·수정 요청 바디 — id는 경로(path)로만 받는다 */
    data class RouteRequest(val label: String, val enabled: Boolean = true, val setting: CommuteSetting)

    data class RouteListResponse(val routes: List<CommuteRoute>)

    @GetMapping
    fun list(@RequestHeader(value = "Authorization", required = false) auth: String?): RouteListResponse =
        RouteListResponse(routes.list(userKeys.resolve(auth)))

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @RequestHeader(value = "Authorization", required = false) auth: String?,
        @RequestBody request: RouteRequest,
    ): CommuteRoute {
        val userKey = userKeys.resolve(auth)
        val existing = routes.list(userKey)
        if (existing.size >= CommuteRoute.MAX_ROUTES) {
            throw ApiException.invalidRequest("경로는 최대 ${CommuteRoute.MAX_ROUTES}개까지 저장할 수 있습니다")
        }
        val route = CommuteRoute(UUID.randomUUID().toString(), request.label.trim(), request.enabled, request.setting)
        route.validate()
        rejectDuplicateLabel(existing, route)
        routes.insert(userKey, route)
        return route
    }

    @PutMapping("/{routeId}")
    fun put(
        @RequestHeader(value = "Authorization", required = false) auth: String?,
        @PathVariable routeId: String,
        @RequestBody request: RouteRequest,
    ): CommuteRoute {
        val userKey = userKeys.resolve(auth)
        val route = CommuteRoute(routeId, request.label.trim(), request.enabled, request.setting)
        route.validate()
        rejectDuplicateLabel(routes.list(userKey), route)
        if (!routes.update(userKey, route)) throw ApiException.settingNotFound()
        return route
    }

    @DeleteMapping("/{routeId}")
    fun delete(
        @RequestHeader(value = "Authorization", required = false) auth: String?,
        @PathVariable routeId: String,
    ): ResponseEntity<Void> {
        if (!routes.delete(userKeys.resolve(auth), routeId)) throw ApiException.settingNotFound()
        return ResponseEntity.noContent().build() // 204 — 클라이언트는 바디를 파싱하지 않는다
    }

    /** 같은 이름이 2개면 알림·라이브 뷰에서 어떤 경로인지 구분할 수 없다 */
    private fun rejectDuplicateLabel(existing: List<CommuteRoute>, route: CommuteRoute) {
        if (existing.any { it.id != route.id && it.label == route.label }) {
            throw ApiException.invalidRequest("같은 이름의 경로가 이미 있습니다: ${route.label}")
        }
    }
}
