package dev.hansw.catchmyride.push

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 푸시 발송 설정 (API.md §4, S-5/S-6).
 * 발송 인증은 API 키가 아니라 mTLS 인증서(콘솔 발급, 토스 로그인과 동일 인증서)다 — 2026-09-08 스펙 확정.
 * keystore-path·템플릿 코드가 비어 있으면 dry-run — 스케줄러는 돌지만 실제 발송 없이 push_log에만 기록한다.
 * (검수 승인된 콘솔 "발송 코드"가 templateSetCode — docs/push-templates.md)
 */
@ConfigurationProperties("push")
data class PushProperties(
    val enabled: Boolean = true,
    val baseUrl: String = "https://apps-in-toss-api.toss.im",
    /** 앱인토스 파트너 mTLS 인증서(PKCS12) — auth.toss와 같은 env(TOSS_MTLS_KEYSTORE)를 쓴다 */
    val keystorePath: String = "",
    val keystorePassword: String = "",
    val templatePre: String = "",
    val templateRemind: String = "",
    /** 내장 표(HolidayCalendar) 밖의 임시공휴일 — 쉼표 구분 "YYYY-MM-DD,..." */
    val extraHolidays: String = "",
) {
    /** 실발송 가능 상태 — 피드백 §3 "발송 이력 있는 날만 접수" 검증도 이 상태에서만 켠다 */
    val live: Boolean get() = keystorePath.isNotBlank()
}
