package dev.hansw.catchmyride.push

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * 대한민국 법정 공휴일 달력 — FR-405 확장 (2026-09-07 오너 결정, 명세서 §9):
 * 평일만 출근하는 경로는 공휴일 아침에 알림을 보내지 않는다.
 *
 * 내장 표는 2026~2027년 확정 공휴일 전체 — 대체공휴일, 2026-06-03 지방선거일,
 * 2026년 부활한 제헌절(7/17) 포함. 주말과 겹치는 날도 그대로 담았다(무해).
 * 애매한 날은 뺐다: 현충일은 대체공휴일 미적용, 근로자의 날(5/1)은 관공서 공휴일이 아니다.
 * 평일을 공휴일로 잘못 담으면 출근날 알림이 조용히 빠지는 최악의 실패라, 법령상 확실한 날만 담는다.
 *
 * 새 임시공휴일(선거·정부 지정)은 env PUSH_EXTRA_HOLIDAYS="2028-03-01,2028-05-09"처럼
 * 쉼표 구분 날짜로 코드 수정 없이 추가한다. 표 범위(2027년 말)를 넘어서면 경고 로그로 갱신을 알린다.
 */
@Component
class HolidayCalendar(properties: PushProperties) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val holidays: Set<LocalDate> = BUILT_IN + parseExtra(properties.extraHolidays)

    /** 커버리지 경고 스팸 방지 — 하루 1회만 */
    private var coverageWarnedFor: LocalDate? = null

    fun isHoliday(date: LocalDate): Boolean {
        if (date.isAfter(LAST_COVERED) && coverageWarnedFor != date) {
            coverageWarnedFor = date
            log.warn("공휴일 표 범위({}) 초과 — {}년 공휴일을 HolidayCalendar에 추가하거나 PUSH_EXTRA_HOLIDAYS로 지정할 것", LAST_COVERED, date.year)
        }
        return date in holidays
    }

    private fun parseExtra(raw: String): Set<LocalDate> =
        raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            .map {
                // 오타는 조용히 무시하지 않고 부팅 실패로 드러낸다 — 잘못된 설정으로 공휴일 발송이 새는 것 방지
                runCatching { LocalDate.parse(it) }
                    .getOrElse { _ -> throw IllegalArgumentException("PUSH_EXTRA_HOLIDAYS 날짜 형식 오류(YYYY-MM-DD): $it") }
            }
            .toSet()

    companion object {
        /** 내장 표의 마지막 날 — 이후 날짜는 매년 말 갱신 필요 */
        val LAST_COVERED: LocalDate = LocalDate.of(2027, 12, 31)

        private val BUILT_IN: Set<LocalDate> = listOf(
            // 2026
            "2026-01-01",                             // 신정
            "2026-02-16", "2026-02-17", "2026-02-18", // 설 연휴
            "2026-03-01", "2026-03-02",               // 삼일절(일) + 대체
            "2026-05-05",                             // 어린이날
            "2026-05-24", "2026-05-25",               // 부처님오신날(일) + 대체
            "2026-06-03",                             // 제9회 전국동시지방선거
            "2026-06-06",                             // 현충일(토, 대체 없음)
            "2026-07-17",                             // 제헌절 (2026년 공휴일 재지정)
            "2026-08-15", "2026-08-17",               // 광복절(토) + 대체
            "2026-09-24", "2026-09-25", "2026-09-26", // 추석 연휴(토요일 겹침은 대체 없음)
            "2026-10-03", "2026-10-05",               // 개천절(토) + 대체
            "2026-10-09",                             // 한글날
            "2026-12-25",                             // 성탄절
            // 2027
            "2027-01-01",                             // 신정
            "2027-02-06", "2027-02-07", "2027-02-08", "2027-02-09", // 설 연휴(설날 2/7 일) + 대체 2/9
            "2027-03-01",                             // 삼일절
            "2027-05-05",                             // 어린이날
            "2027-05-13",                             // 부처님오신날
            "2027-06-06",                             // 현충일(일, 대체 없음)
            "2027-07-17",                             // 제헌절(토)
            "2027-08-15", "2027-08-16",               // 광복절(일) + 대체
            "2027-09-14", "2027-09-15", "2027-09-16", // 추석 연휴
            "2027-10-03", "2027-10-04",               // 개천절(일) + 대체
            "2027-10-09", "2027-10-11",               // 한글날(토) + 대체
            "2027-12-25", "2027-12-27",               // 성탄절(토) + 대체
        ).map(LocalDate::parse).toSet()
    }
}
