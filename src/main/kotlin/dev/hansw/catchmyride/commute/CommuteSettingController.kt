package dev.hansw.catchmyride.commute

import dev.hansw.catchmyride.api.ApiException
import dev.hansw.catchmyride.api.UserKeyResolver
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * API.md §1 — (레거시) 유저당 1개 시절의 통근 설정 CRUD.
 * 다중 경로(§1-2) 도입 후에는 "첫 경로"에 대한 어댑터로 동작한다 — 배포된 구버전 번들이 이 경로를 계속 쓴다.
 * GET = 첫 경로의 설정 / PUT = 첫 경로 갱신(없으면 '출근' 라벨로 생성) / DELETE = 모든 경로 삭제(재온보딩).
 */
@RestController
@RequestMapping("/api/v1/commute-setting")
class CommuteSettingController(
    private val routes: CommuteRouteRepository,
    private val userKeys: UserKeyResolver,
) {

    @GetMapping
    fun get(@RequestHeader(value = "Authorization", required = false) auth: String?): CommuteSetting =
        routes.list(userKeys.resolve(auth)).firstOrNull()?.setting ?: throw ApiException.settingNotFound()

    @PutMapping
    fun put(
        @RequestHeader(value = "Authorization", required = false) auth: String?,
        @RequestBody setting: CommuteSetting,
    ): CommuteSetting {
        setting.validate()
        val userKey = userKeys.resolve(auth)
        val first = routes.list(userKey).firstOrNull()
        if (first == null) {
            routes.insert(userKey, CommuteRoute(UUID.randomUUID().toString(), DEFAULT_LABEL, enabled = true, setting))
        } else {
            routes.update(userKey, first.copy(setting = setting))
        }
        return setting
    }

    @DeleteMapping
    fun delete(@RequestHeader(value = "Authorization", required = false) auth: String?): ResponseEntity<Void> {
        routes.deleteAll(userKeys.resolve(auth))
        return ResponseEntity.noContent().build() // 204 — 클라이언트는 바디를 파싱하지 않는다
    }

    companion object {
        const val DEFAULT_LABEL = "출근"
    }
}
