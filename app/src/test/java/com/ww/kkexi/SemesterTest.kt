package com.ww.kkexi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * [Semester]：学期周次锚点（源：`Domain.kt:212-240`）。
 *
 * 锚点事实（教务处《2026-2027 学年校历》，2026-04-29 发布，见 `AGENTS.md` 第六节）：
 * 第 1 周周一 = **2026-09-07**；教学时间 2026-09-07 ~ 2027-01-15；本科新生 2026-09-21（第 3 周）开始行课；
 * 第 19 周为期末统考周；寒假 2027-01-18 起。
 * 交叉验证：桌面《公共课(高数和线代)答疑安排》记「第六周 周一 = 10月12日」，与本锚点倒推一致。
 */
class SemesterTest {

    @Test
    fun first_monday_is_2026_09_07_and_really_is_a_monday() {
        assertEquals(LocalDate.of(2026, 9, 7), Semester.firstMonday)
        assertEquals(DayOfWeek.MONDAY, Semester.firstMonday.dayOfWeek)
    }

    @Test
    fun total_weeks_is_19() {
        // Domain.kt:227：第 19 周是期末统考周。
        assertEquals(19, Semester.totalWeeks)
    }

    @Test
    fun week_of_the_anchor_week_and_its_neighbours() {
        assertEquals(1, Semester.weekOf(LocalDate.of(2026, 9, 7)))   // 周一
        assertEquals(1, Semester.weekOf(LocalDate.of(2026, 9, 13)))  // 同周周日
        assertEquals(2, Semester.weekOf(LocalDate.of(2026, 9, 14)))
        assertEquals(3, Semester.weekOf(LocalDate.of(2026, 9, 21)))  // 本科新生开始行课日
        assertEquals(3, Semester.weekOf(LocalDate.of(2026, 9, 23)))
    }

    @Test
    fun week_of_2026_10_12_is_6() {
        assertEquals(6, Semester.weekOf(LocalDate.of(2026, 10, 12)))
        assertEquals(6, Semester.weekOf(LocalDate.of(2026, 10, 14)))
        assertEquals(6, Semester.weekOf(LocalDate.of(2026, 10, 18)))
        assertEquals(7, Semester.weekOf(LocalDate.of(2026, 10, 19)))
        assertEquals(7, Semester.weekOf(LocalDate.of(2026, 10, 25)))
        assertEquals(8, Semester.weekOf(LocalDate.of(2026, 10, 26)))
    }

    @Test
    fun week_of_before_the_semester_uses_floor_division() {
        // Domain.kt:230-233 用 Math.floorDiv，所以开学前是 0 或负数，不会变成 1。
        assertEquals(0, Semester.weekOf(LocalDate.of(2026, 9, 6)))   // 开学前一天，差 -1 天
        assertEquals(0, Semester.weekOf(LocalDate.of(2026, 8, 31)))  // 差 -7 天
        assertEquals(-1, Semester.weekOf(LocalDate.of(2026, 8, 30))) // 差 -8 天 -> floorDiv(-8,7)=-2
    }

    @Test
    fun monday_of_16_is_2026_12_21() {
        assertEquals(LocalDate.of(2026, 12, 21), Semester.mondayOf(16))
        assertEquals(DayOfWeek.MONDAY, Semester.mondayOf(16).dayOfWeek)
        assertEquals(16, Semester.weekOf(Semester.mondayOf(16)))
        // 与校历两条独立来源吻合：09-07 + 15 周 = 12-21。
        assertEquals(105L, java.time.temporal.ChronoUnit.DAYS.between(Semester.firstMonday, Semester.mondayOf(16)))
    }

    @Test
    fun monday_and_sunday_boundaries_of_the_semester() {
        assertEquals(LocalDate.of(2026, 9, 7), Semester.mondayOf(1))
        assertEquals(LocalDate.of(2026, 9, 13), Semester.sundayOf(1))
        assertEquals(LocalDate.of(2027, 1, 11), Semester.mondayOf(19))
        assertEquals(LocalDate.of(2027, 1, 17), Semester.sundayOf(19))
        // 校历：教学时间截至 2027-01-15（第 19 周内），寒假 2027-01-18 起 —— 正好是第 20 周周一。
        assertEquals(19, Semester.weekOf(LocalDate.of(2027, 1, 15)))
        assertEquals(LocalDate.of(2027, 1, 18), Semester.mondayOf(20))
        assertEquals(20, Semester.weekOf(LocalDate.of(2027, 1, 18)))
    }

    @Test
    fun monday_and_sunday_are_consistent_for_every_week() {
        for (week in 1..Semester.totalWeeks) {
            val monday = Semester.mondayOf(week)
            val sunday = Semester.sundayOf(week)
            assertEquals("第 $week 周周一", DayOfWeek.MONDAY, monday.dayOfWeek)
            assertEquals("第 $week 周周日", DayOfWeek.SUNDAY, sunday.dayOfWeek)
            assertEquals("第 $week 周长度", 6L, java.time.temporal.ChronoUnit.DAYS.between(monday, sunday))
            assertEquals("weekOf(mondayOf($week))", week, Semester.weekOf(monday))
            assertEquals("weekOf(sundayOf($week))", week, Semester.weekOf(sunday))
        }
    }

    @Test
    fun weeks_are_strictly_increasing_by_seven_days() {
        var previous = Semester.mondayOf(1)
        for (week in 2..Semester.totalWeeks) {
            val current = Semester.mondayOf(week)
            assertEquals(7L, java.time.temporal.ChronoUnit.DAYS.between(previous, current))
            assertTrue(current.isAfter(previous))
            previous = current
        }
    }
}
