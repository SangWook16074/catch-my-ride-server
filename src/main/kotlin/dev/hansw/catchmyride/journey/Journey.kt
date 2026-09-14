package dev.hansw.catchmyride.journey

import java.time.LocalDateTime

/**
 * §9 하차 알림 — 여정(Journey) 도메인 (명세서 §3.7).
 * 유저가 직접 입력한 구간 리스트 — 경로 "탐색"이 아니라 입력된 여정의 검증·추적만 한다 (§1.4).
 */
data class JourneyLeg(
    val type: String,       // v1은 "SUBWAY"만 (FR-703)
    val line: String,       // §5-2 표기 그대로 ("9호선 급행")
    val boardStop: String,  // 역명 (= SUBWAY stopId)
    val alightStop: String,
)

data class Journey(
    val id: String,
    val label: String,
    val repeatDays: List<String>,   // ["MON",...] — 요일 반복(FR-702), 자동 시작 아님
    val legs: List<JourneyLeg>,
    val lastUsedAt: LocalDateTime?, // 트립 시작 시 갱신 — 히스토리 정렬 키
)

/** 트립 진행 상태 (API.md §9-3) */
enum class TripPhase { TRACKING, ARRIVING, TRANSFER, DONE, LOST }

/** FR-704 — 이벤트(구간 하차)당 최대 2회: 예고(2정거장 전)·하차(직전 역) */
enum class TripPushStage { PRE, ALIGHT }

const val MAX_JOURNEYS = 10
const val MAX_JOURNEY_LEGS = 4
const val JOURNEY_LABEL_MAX_LENGTH = 16
