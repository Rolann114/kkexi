package com.ww.kkexi

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/**
 * [DateTimeExt]：界面与通知共用的时间文案（源：`DateTimeExt.kt:8-53`）。
 *
 * 这一类是纯函数、只依赖 `java.time` 与 [Semester]，所以放进 JVM 单元测试。
 * 断言里的「第 6 周」「周三」等文案直接决定 App 上的显示，属于要锁住的对外表现。
 *
 * 为什么要固定 Locale：`formatDateWithWeek` 用 `DateTimeFormatter.ofPattern("M月d日 EEEE")`（`DateTimeExt.kt:11`），
 * 未指定 Locale 时会用 `Locale.getDefault()` —— 已用 JDK 25 实测：同一日期在 `Locale.US` 下输出
 * `10月14日 Wednesday`，在 `Locale.CHINA` 下输出 `10月14日 星期三`。测试固定为 zh_CN，避免环境差异导致误报。
 */
class DateTimeExtTest {

    private lateinit var originalLocale: Locale

    @Before
    fun pinLocale() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.CHINA)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    private val today = LocalDate.of(2026, 10, 14)

    @Test
    fun format_date_uses_chinese_month_and_day() {
        assertEquals("10月14日", DateTimeExt.formatDate(today))
        assertEquals("9月7日", DateTimeExt.formatDate(LocalDate.of(2026, 9, 7)))
        assertEquals("1月18日", DateTimeExt.formatDate(LocalDate.of(2027, 1, 18)))
    }

    @Test
    fun format_date_with_week_appends_chinese_weekday_name() {
        assertEquals("10月14日 星期三", DateTimeExt.formatDateWithWeek(today))
        assertEquals("9月7日 星期一", DateTimeExt.formatDateWithWeek(LocalDate.of(2026, 9, 7)))
    }

    @Test
    fun week_name_covers_all_seven_days() {
        val monday = LocalDate.of(2026, 9, 7)
        val expected = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        for (offset in 0..6) {
            assertEquals(expected[offset], DateTimeExt.weekName(monday.plusDays(offset.toLong())))
        }
        assertEquals("周三", DateTimeExt.weekName(today))
        assertEquals("周日", DateTimeExt.weekName(LocalDate.of(2026, 10, 18)))
    }

    @Test
    fun relative_label_handles_today_tomorrow_and_the_day_after() {
        assertEquals("今天", DateTimeExt.relativeLabel(today, today))
        assertEquals("明天", DateTimeExt.relativeLabel(today.plusDays(1), today))
        assertEquals("后天", DateTimeExt.relativeLabel(today.plusDays(2), today))
        // 其余走 formatDateWithWeek（含星期）。
        assertEquals("10月17日 星期六", DateTimeExt.relativeLabel(today.plusDays(3), today))
        assertEquals("10月13日 星期二", DateTimeExt.relativeLabel(today.minusDays(1), today))
    }

    @Test
    fun week_label_uses_the_semester_anchor() {
        assertEquals("第 1 周", DateTimeExt.weekLabel(LocalDate.of(2026, 9, 7)))
        assertEquals("第 6 周", DateTimeExt.weekLabel(today))
        assertEquals("第 6 周", DateTimeExt.weekLabel(LocalDate.of(2026, 10, 12)))
        assertEquals("第 19 周", DateTimeExt.weekLabel(LocalDate.of(2027, 1, 11)))
    }

    @Test
    fun week_label_is_holiday_outside_the_semester() {
        // DateTimeExt.kt:33-36：第 1..19 周之外一律显示「假期」。
        assertEquals("假期", DateTimeExt.weekLabel(LocalDate.of(2026, 9, 6)))
        assertEquals("假期", DateTimeExt.weekLabel(LocalDate.of(2026, 8, 30)))
        assertEquals("假期", DateTimeExt.weekLabel(LocalDate.of(2027, 1, 18)))
    }

    @Test
    fun day_header_composes_relative_date_and_week() {
        assertEquals("今天 · 10月14日 周三 · 第 6 周", DateTimeExt.dayHeader(today, today))
        assertEquals(
            "明天 · 10月15日 周四 · 第 6 周",
            DateTimeExt.dayHeader(LocalDate.of(2026, 10, 15), today),
        )
        assertEquals(
            "后天 · 10月16日 周五 · 第 6 周",
            DateTimeExt.dayHeader(LocalDate.of(2026, 10, 16), today),
        )
    }

    @Test
    fun day_header_does_not_repeat_the_weekday() {
        // 这条测试原本锁的是**缺陷**：旧 dayHeader 会输出
        //   "10月19日 星期一 · 10月19日 周一 · 第 7 周"
        // 因为 relativeLabel 的兜底分支已经带了星期，dayHeader 又拼了一次。
        // 现在源已修好，断言改为锁正确行为，并额外断言「星期只出现一次」。
        val header = DateTimeExt.dayHeader(LocalDate.of(2026, 10, 19), today)
        assertEquals("10月19日 周一 · 第 7 周", header)

        // 兜底：不管文案怎么改，同一种星期写法都不该出现两次。
        val occurrences = Regex("周一").findAll(header).count()
        assertEquals("星期在标题里出现 $occurrences 次，应为 1 次：$header", 1, occurrences)
    }

    @Test
    fun time_range_renders_a_session_from_the_periods_table() {
        val session = Expander.expand(
            SeedData.events.first { it.id == "d-math-mon" },
            LocalDate.of(2026, 10, 12),
            LocalDate.of(2026, 10, 12),
        )[0]
        // d-math-mon 在第 1-2 节，按默认作息表即 08:20-10:00。
        assertEquals("08:20-10:00", DateTimeExt.timeRange(session))
    }

    @Test
    fun column_of_maps_monday_to_zero_and_sunday_to_six() {
        // DateTimeExt.kt:53：周视图列序，周一=0。
        assertEquals(0, DateTimeExt.columnOf(LocalDate.of(2026, 9, 7)))
        assertEquals(2, DateTimeExt.columnOf(today))
        assertEquals(6, DateTimeExt.columnOf(LocalDate.of(2026, 9, 13)))
        for (offset in 0..6) {
            assertEquals(offset, DateTimeExt.columnOf(LocalDate.of(2026, 9, 7).plusDays(offset.toLong())))
        }
    }

    @Test
    fun iso_week_matches_the_iso_calendar() {
        // DateTimeExt.kt:50：WeekFields.ISO 的周序号（用 JDK 25 与 Python isocalendar 双向核对过）。
        assertEquals(42, DateTimeExt.isoWeek(today))
        assertEquals(37, DateTimeExt.isoWeek(LocalDate.of(2026, 9, 7)))
        assertEquals(1, DateTimeExt.isoWeek(LocalDate.of(2026, 1, 1)))
    }
}
