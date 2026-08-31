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

/** API.md §1 — 통근 설정 CRUD (유저당 1개, PUT 업서트). */
@RestController
@RequestMapping("/api/v1/commute-setting")
class CommuteSettingController(
    private val repository: CommuteSettingRepository,
    private val userKeys: UserKeyResolver,
) {

    @GetMapping
    fun get(@RequestHeader(value = "Authorization", required = false) auth: String?): CommuteSetting =
        repository.find(userKeys.resolve(auth)) ?: throw ApiException.settingNotFound()

    @PutMapping
    fun put(
        @RequestHeader(value = "Authorization", required = false) auth: String?,
        @RequestBody setting: CommuteSetting,
    ): CommuteSetting {
        setting.validate()
        repository.upsert(userKeys.resolve(auth), setting)
        return setting
    }

    @DeleteMapping
    fun delete(@RequestHeader(value = "Authorization", required = false) auth: String?): ResponseEntity<Void> {
        repository.delete(userKeys.resolve(auth))
        return ResponseEntity.noContent().build() // 204 — 클라이언트는 바디를 파싱하지 않는다
    }
}
