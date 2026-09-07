package dev.hansw.catchmyride.push

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HolidayCalendarTest {

    private val calendar = HolidayCalendar(PushProperties())

    @Test
    fun `확정 공휴일과 대체공휴일을 안다`() {
        // 평일에 걸린 날들 — 스케줄러가 실제로 걸러야 하는 케이스
        assertTrue(calendar.isHoliday(LocalDate.parse("2026-02-17")), "설날")
        assertTrue(calendar.isHoliday(LocalDate.parse("2026-03-02")), "삼일절 대체공휴일")
        assertTrue(calendar.isHoliday(LocalDate.parse("2026-06-03")), "지방선거일")
        assertTrue(calendar.isHoliday(LocalDate.parse("2026-07-17")), "제헌절(2026 재지정)")
        assertTrue(calendar.isHoliday(LocalDate.parse("2026-10-05")), "개천절 대체공휴일")
        assertTrue(calendar.isHoliday(LocalDate.parse("2027-02-09")), "설 연휴 대체공휴일")
        assertTrue(calendar.isHoliday(LocalDate.parse("2027-12-27")), "성탄절 대체공휴일")
    }

    @Test
    fun `평일 출근일은 공휴일이 아니다`() {
        assertFalse(calendar.isHoliday(LocalDate.parse("2026-09-07"))) // 월
        assertFalse(calendar.isHoliday(LocalDate.parse("2026-10-06"))) // 대체공휴일 다음날
        assertFalse(calendar.isHoliday(LocalDate.parse("2027-09-17"))) // 추석 연휴 다음날
    }

    @Test
    fun `extra-holidays로 임시공휴일을 추가한다`() {
        val patched = HolidayCalendar(PushProperties(extraHolidays = "2028-03-02, 2028-05-09"))
        assertTrue(patched.isHoliday(LocalDate.parse("2028-03-02")))
        assertTrue(patched.isHoliday(LocalDate.parse("2028-05-09")))
        assertFalse(patched.isHoliday(LocalDate.parse("2028-05-10")))
    }

    @Test
    fun `extra-holidays 형식 오류는 부팅 실패로 드러난다`() {
        assertThrows<IllegalArgumentException> {
            HolidayCalendar(PushProperties(extraHolidays = "2028/03/02"))
        }
    }

    @Test
    fun `표 범위를 넘어선 날짜는 공휴일 아님으로 안전하게 처리한다`() {
        // 발송이 조용히 빠지는 쪽(과차단)이 아니라 발송되는 쪽(과발송)으로 실패한다 + 경고 로그
        assertFalse(calendar.isHoliday(LocalDate.parse("2028-01-01")))
    }
}
