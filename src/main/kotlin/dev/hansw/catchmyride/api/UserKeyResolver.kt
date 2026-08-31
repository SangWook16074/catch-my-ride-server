package dev.hansw.catchmyride.api

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * 요청의 유저 식별 — SERVER_FEEDBACK.md §3.
 *
 * 토스 로그인(clientId)이 콘솔 약관 동의에 막혀 있어 클라이언트는 아직 Authorization 헤더를 보내지 않는다.
 * 그래서 지금은 auth.dev-user-key 고정 유저로 취급해 E2E를 가능하게 한다.
 * S-3에서 토스 토큰 검증이 붙으면: 헤더 있으면 검증→userKey, 없으면 dev-user-key가 빈 값일 때 401.
 */
@Component
class UserKeyResolver(@Value("\${auth.dev-user-key}") private val devUserKey: String) {

    fun resolve(authorizationHeader: String?): String {
        if (devUserKey.isNotBlank()) return devUserKey
        throw ApiException.unauthorized()
    }
}
