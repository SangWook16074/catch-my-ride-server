package dev.hansw.catchmyride.push

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 푸시 발송 설정 (API.md §4, S-5/S-6).
 * api-key·템플릿 코드가 비어 있으면 dry-run — 스케줄러는 돌지만 실제 발송 없이 push_log에만 기록한다.
 * (P0-7 템플릿 검수·콘솔 약관 동의가 끝나면 env로 실값을 넣어 라이브 전환 — docs/push-templates.md)
 */
@ConfigurationProperties("push")
data class PushProperties(
    val enabled: Boolean = true,
    val baseUrl: String = "https://apps-in-toss-api.toss.im",
    val apiKey: String = "",
    val templatePre: String = "",
    val templateRemind: String = "",
    /** 내장 표(HolidayCalendar) 밖의 임시공휴일 — 쉼표 구분 "YYYY-MM-DD,..." */
    val extraHolidays: String = "",
) {
    /** 실발송 가능 상태 — 피드백 §3 "발송 이력 있는 날만 접수" 검증도 이 상태에서만 켠다 */
    val live: Boolean get() = apiKey.isNotBlank()
}
