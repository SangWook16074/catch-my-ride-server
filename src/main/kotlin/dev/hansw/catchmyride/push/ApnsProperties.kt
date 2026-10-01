package dev.hansw.catchmyride.push

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * API.md §9-5 (v0.12) — iOS Live Activity 원격 갱신용 APNs 직접 발송 설정.
 * 토큰 기반 인증(.p8 키) — 넷 다(keyId·teamId·privateKey·bundleId) 채워져야 "live"다.
 * 비어 있으면 [dev.hansw.catchmyride.push.ApnsClient]는 로그만 남기고 아무것도 보내지 않는다
 * (코드베이스 공통 강등 규칙 — FcmPushClient·AppsInTossPushClient와 동일).
 */
@ConfigurationProperties("apns")
data class ApnsProperties(
    /** Apple Developer "Keys" — .p8 Key ID */
    val keyId: String = "",
    /** Apple Developer Team ID */
    val teamId: String = "",
    /** .p8 파일 내용(PEM, PKCS8) — 경로가 아니라 내용 그대로. env에 개행이 `\n` 리터럴로 들어와도 흡수한다 */
    val privateKey: String = "",
    /** Live Activity 앱 번들 ID — apns-topic은 `<bundleId>.push-type.liveactivity`로 구성 */
    val bundleId: String = "",
    /** true면 api.sandbox.push.apple.com, false면 api.push.apple.com */
    val sandbox: Boolean = false,
) {
    val live: Boolean get() = keyId.isNotBlank() && teamId.isNotBlank() && privateKey.isNotBlank() && bundleId.isNotBlank()
}
