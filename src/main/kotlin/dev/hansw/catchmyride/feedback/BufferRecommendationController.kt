package dev.hansw.catchmyride.feedback

import dev.hansw.catchmyride.api.UserKeyResolver
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/**
 * API.md §3-1 — 여유 버퍼 자동 추천 (2026-09-07 오너 결정, 명세서 §9).
 * 정확성 보정 루프의 첫 조각: 최근 피드백 5건 중 MISSED가 2건 이상이면(표본 3건 이상) 버퍼 +5분을 제안한다.
 *
 * 서버는 제안만 한다 — 적용은 클라이언트가 유저 확인을 받아 §1-2c 경로 수정으로 반영하고,
 * "괜찮아요" 거절 기억(7일)도 클라이언트 몫이라 서버에는 상태가 없다.
 */
@RestController
class BufferRecommendationController(
    private val repository: BoardingFeedbackRepository,
    private val userKeys: UserKeyResolver,
) {

    data class Response(
        val recommend: Boolean,
        val missedCount: Int,
        val sampleSize: Int,
        val suggestedIncrementMinutes: Int,
    )

    @GetMapping("/api/v1/buffer-recommendation")
    fun get(@RequestHeader(value = "Authorization", required = false) auth: String?): Response =
        evaluate(repository.recentResults(userKeys.resolve(auth), SAMPLE_LIMIT))

    companion object {
        const val SAMPLE_LIMIT = 5
        const val MIN_SAMPLE = 3
        const val MIN_MISSED = 2
        const val INCREMENT_MINUTES = 5

        /** 순수 판정 — 규칙이 한눈에 보이게 유지한다 (단위 테스트 대상) */
        fun evaluate(recentResults: List<String>): Response {
            val missed = recentResults.count { it == "MISSED" }
            return Response(
                recommend = recentResults.size >= MIN_SAMPLE && missed >= MIN_MISSED,
                missedCount = missed,
                sampleSize = recentResults.size,
                suggestedIncrementMinutes = INCREMENT_MINUTES,
            )
        }
    }
}
