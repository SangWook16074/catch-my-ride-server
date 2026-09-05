package dev.hansw.catchmyride.commute

import dev.hansw.catchmyride.api.ApiException

/**
 * API.md §1-2 CommuteRoute — 유저당 여러 경로(출근·퇴근·자유 라벨, 최대 5개).
 * 경로 하나가 정류장·도보·모드·시간·요일을 독립으로 가지며(= CommuteSetting),
 * FR-403(하루 최대 2회)·FR-405(요일)는 경로 단위로 적용된다.
 */
data class CommuteRoute(
    val id: String,        // 서버 발급 UUID — 클라이언트는 생성 응답의 id를 그대로 쓴다
    val label: String,     // "출근"/"퇴근" 프리셋 또는 자유 입력 (1~16자)
    val enabled: Boolean,  // false = 이 경로 알림 일시 중지 (라이브 뷰 조회는 가능)
    val setting: CommuteSetting,
) {
    fun validate() {
        if (label.isBlank()) throw ApiException.invalidRequest("경로 이름을 입력해야 합니다")
        if (label.length > MAX_LABEL_LENGTH) {
            throw ApiException.invalidRequest("경로 이름은 ${MAX_LABEL_LENGTH}자 이내여야 합니다")
        }
        setting.validate()
    }

    companion object {
        const val MAX_ROUTES = 5
        const val MAX_LABEL_LENGTH = 16
    }
}
