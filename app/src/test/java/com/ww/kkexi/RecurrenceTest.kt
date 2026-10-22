package com.ww.kkexi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

/**
 * [Recurrence.matchesWeek]：周次命中规则（源：`Domain.kt:96-113`）。
 *
 * 判定顺序（`Domain.kt:104-112`）：
 * 1. `weeks` 非空 → **只**看 `week in weeks`，startWeek / endWeek / parity 全部忽略；
 * 2. 否则先卡区间 `startWeek..endWeek`（含两端）；
 * 3. 区间内再按 parity：EVERY 全中，ODD 只看 `week % 2 == 1`，EVEN 只看 `week % 2 == 0`。
 *
 * 注意这里**不**看 `dayOfWeek`：星期几的筛选在 `Expander.occurrenceDates`（`Domain.kt:317`）里做。
 */
class RecurrenceTest {

    @Test
    fun default_parity_is_every_and_weeks_is_empty() {
        // Domain.kt:100-101 的默认值属于公开契约。
        val rule = Recurrence(DayOfWeek.MONDAY, 3, 16)
        assertEquals(WeekParity.EVERY, rule.parity)
        assertTrue(rule.weeks.isEmpty())
    }

    @Test
    fun every_matches_every_week_inside_the_range() {
        val rule = Recurrence(DayOfWeek.MONDAY, 3, 16, WeekParity.EVERY)
        for (week in 3..16) {
            assertTrue("第 $week 周应命中", rule.matchesWeek(week))
        }
    }

    @Test
    fun every_rejects_weeks_outside_the_range() {
        val rule = Recurrence(DayOfWeek.MONDAY, 3, 16, WeekParity.EVERY)
        for (week in listOf(0, 1, 2, 17, 18, 19, 20)) {
            assertFalse("第 $week 周不应命中", rule.matchesWeek(week))
        }
    }

    @Test
    fun start_and_end_weeks_are_inclusive() {
        val rule = Recurrence(DayOfWeek.WEDNESDAY, 6, 8)
        assertTrue(rule.matchesWeek(6))
        assertTrue(rule.matchesWeek(8))
        assertFalse(rule.matchesWeek(5))
        assertFalse(rule.matchesWeek(9))
    }

    @Test
    fun odd_matches_only_odd_weeks() {
        val rule = Recurrence(DayOfWeek.WEDNESDAY, 3, 17, WeekParity.ODD)
        for (week in 3..17) {
            assertEquals("第 $week 周", week % 2 == 1, rule.matchesWeek(week))
        }
        assertTrue(rule.matchesWeek(3))
        assertTrue(rule.matchesWeek(17))
        assertFalse(rule.matchesWeek(6))
        assertFalse(rule.matchesWeek(16))
    }

    @Test
    fun even_matches_only_even_weeks() {
        val rule = Recurrence(DayOfWeek.THURSDAY, 6, 16, WeekParity.EVEN)
        for (week in 6..16) {
            assertEquals("第 $week 周", week % 2 == 0, rule.matchesWeek(week))
        }
        assertTrue(rule.matchesWeek(6))
        assertTrue(rule.matchesWeek(16))
        assertFalse(rule.matchesWeek(7))
        assertFalse(rule.matchesWeek(17))
    }

    @Test
    fun explicit_weeks_list_overrides_start_end_and_parity() {
        // 对应课表里的「双4-6,8-10,12,14-15,17周」：parity=EVEN 但列表里 15、17 是奇数周，仍然命中。
        val rule = Recurrence(
            dayOfWeek = DayOfWeek.WEDNESDAY,
            startWeek = 1,
            endWeek = 19,
            parity = WeekParity.EVEN,
            weeks = listOf(4, 6, 8, 10, 12, 14, 15, 17),
        )
        for (week in listOf(4, 6, 8, 10, 12, 14, 15, 17)) {
            assertTrue("第 $week 周在列表里应命中", rule.matchesWeek(week))
        }
        for (week in listOf(1, 3, 5, 7, 9, 11, 13, 16, 18, 19)) {
            assertFalse("第 $week 周不在列表里不应命中", rule.matchesWeek(week))
        }
    }

    @Test
    fun explicit_weeks_list_ignores_start_and_end_completely() {
        val rule = Recurrence(
            dayOfWeek = DayOfWeek.MONDAY,
            startWeek = 6,
            endWeek = 8,
            parity = WeekParity.ODD,
            weeks = listOf(2, 20),
        )
        assertTrue("列表里的第 2 周早于 startWeek 仍命中", rule.matchesWeek(2))
        assertTrue("列表里的第 20 周晚于 endWeek 仍命中", rule.matchesWeek(20))
        assertFalse("区间内的第 7 周不在列表里，不命中", rule.matchesWeek(7))
        assertFalse(rule.matchesWeek(6))
        assertFalse(rule.matchesWeek(8))
    }

    @Test
    fun day_of_week_does_not_affect_week_matching() {
        val monday = Recurrence(DayOfWeek.MONDAY, 3, 10, WeekParity.ODD)
        val sunday = Recurrence(DayOfWeek.SUNDAY, 3, 10, WeekParity.ODD)
        for (week in 1..19) {
            assertEquals("第 $week 周", monday.matchesWeek(week), sunday.matchesWeek(week))
        }
        assertEquals(monday.dayOfWeek, DayOfWeek.MONDAY)
        assertEquals(sunday.dayOfWeek, DayOfWeek.SUNDAY)
    }
}
