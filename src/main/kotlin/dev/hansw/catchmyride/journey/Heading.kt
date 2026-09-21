package dev.hansw.catchmyride.journey

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * 열차 진행 방면 — 상류 updnLine 정규화. UP = 상행·내선·(realtimePosition) "0", DOWN = 하행·외선·"1"
 * (2026-09-21 실측: 교대 전광판 열차 번호로 두 피드를 교차 확인).
 *
 * 유저에게 상행/하행을 묻지 않는다 — 초행길 유저가 대상이라 방향은 서버가 판정한다 (오너 결정 2026-09-21).
 */
enum class Heading { UP, DOWN }

fun headingOf(updnLine: String?): Heading? = when (updnLine?.trim()) {
    "상행", "내선", "0" -> Heading.UP
    "하행", "외선", "1" -> Heading.DOWN
    else -> null
}

/**
 * 구간(탑승역→하차역)의 방면 판정 — 역 순서 데이터 없이 상류 필드만으로:
 * 역 id는 노선 순서대로 매겨져 있고(3호선 대화 309 → 오금 352), 전광판의 `statnFid → statnTid`
 * (이전 역 → 다음 역)가 각 방면이 id를 올리는지 내리는지 알려준다. 3호선·1호선은 하행이 증가,
 * 9호선은 상행이 증가라 "상행 = 감소" 같은 관례는 쓰지 않고 노선마다 이 필드로 맞춘다.
 * 2호선 본선(201~243)은 순환이라 모듈러 거리(짧은 쪽)로 본다. 판정 불가면 null — 방면 필터 없이
 * 기존 동작(자기선택)으로 강등, 아는 척하지 않는다 (NFR-03).
 *
 * @param rows 탑승역 또는 하차역 전광판 행 — 그 역에서의 방면별 이전/다음 역 id를 쓴다
 */
fun resolveHeading(legLine: String, boardId: Long, alightId: Long, rows: List<ApproachingTrain>): Heading? {
    val line = lineBase(legLine)
    val required = travelSign(line, boardId, alightId) ?: return null
    val matches = rows
        .filter { it.line == line }
        .mapNotNull { row ->
            val heading = row.heading ?: return@mapNotNull null
            val prev = row.prevStationId ?: return@mapNotNull null
            val next = row.nextStationId ?: return@mapNotNull null
            val sign = stepSign(line, prev, next) ?: return@mapNotNull null
            heading to sign
        }
        .distinct()
        .filter { it.second == required }
        .map { it.first }
        .distinct()
    return matches.singleOrNull()
}

private const val LOOP_LINE = "2호선"
private const val LOOP_FIRST = 201L
private const val LOOP_LAST = 243L
private const val LOOP_SIZE = LOOP_LAST - LOOP_FIRST + 1

/** statnId "1003000340" → 노선 안 순번 340 */
private fun ordinal(stationId: Long): Long = stationId % 10_000

private fun onLoop(line: String, stationId: Long): Boolean =
    line == LOOP_LINE && ordinal(stationId) in LOOP_FIRST..LOOP_LAST

/** 탑승→하차가 id를 올리는 방향(+1)인지 내리는 방향(-1)인지. 같은 역·판정 불가면 null */
private fun travelSign(line: String, boardId: Long, alightId: Long): Int? {
    if (boardId == alightId) {
        return null
    }
    val boardLoop = onLoop(line, boardId)
    val alightLoop = onLoop(line, alightId)
    if (boardLoop != alightLoop) {
        return null // 본선↔지선(성수·신정지선) 조합 — 순번 비교가 의미 없다
    }
    if (boardLoop) {
        val inc = Math.floorMod(ordinal(alightId) - ordinal(boardId), LOOP_SIZE)
        return if (inc <= LOOP_SIZE / 2) 1 else -1 // 짧은 쪽으로 간다고 본다
    }
    return if (alightId > boardId) 1 else -1
}

/** 전광판 방면의 이동 부호 — 이전 역 → (조회 역) → 다음 역 */
private fun stepSign(line: String, prevId: Long, nextId: Long): Int? {
    if (prevId == nextId) {
        return null
    }
    if (onLoop(line, prevId) && onLoop(line, nextId)) {
        val d = Math.floorMod(ordinal(nextId) - ordinal(prevId), LOOP_SIZE)
        return if (d <= LOOP_SIZE / 2) 1 else -1
    }
    return if (nextId > prevId) 1 else -1
}

/**
 * 노선별 역명 → 역 id 학습 캐시 — 전광판(statnId)·노선 위치(statnNm/statnId) 응답에서 보이는 대로 쌓는다.
 * 정적 데이터라 만료 없음. 같은 역명이 여러 노선에 있으므로(충무로 3·4호선) 노선으로 구분한다
 */
@Component
class StationIdCache {
    private val ids = ConcurrentHashMap<String, Long>()

    fun remember(line: String?, station: String?, stationId: Long?) {
        if (line == null || station.isNullOrBlank() || stationId == null) {
            return
        }
        ids.putIfAbsent(key(line, station), stationId)
    }

    fun learn(rows: List<ApproachingTrain>) = rows.forEach { remember(it.line, it.stationName, it.stationId) }

    fun learn(line: String, trains: List<LineTrain>) = trains.forEach { remember(line, it.station, it.stationId) }

    fun get(legLine: String, station: String): Long? = ids[key(lineBase(legLine), station)]

    private fun key(line: String, station: String) = "${lineBase(line)}|${station.trim()}"
}
