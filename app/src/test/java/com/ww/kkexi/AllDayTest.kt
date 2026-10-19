package com.ww.kkexi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「全天」的判定与显示。
 *
 * 起因是一个真实看到的显示 bug：作业类死线显示成 `00:00-23:59`，
 * 读起来像「半夜零点干到半夜」，很怪。
 *
 * 根因有两层：
 * 1. 模型对没有具体时刻的事会给出 `start=00:00, end=23:59`（或整天）；
 * 2. 界面原样把两个时刻拼起来显示。
 *
 * 修法是让「全天」成为一等概念：既要有 [TimeSpec.ALL_DAY] 让用户能主动选，
 * 也要能识别模型给出的各种「整天」写法。
 */
class AllDayTest {

    private val timetable = Timetable.DEFAULT

    @Test
    fun all_day_constant_covers_the_whole_day() {
        val spec = TimeSpec.ALL_DAY
        assertTrue(TimeSpec.isAllDay(spec, timetable))
        assertEquals("全天", TimeSpec.label(spec, timetable))
    }

    @Test
    fun model_style_full_day_range_is_recognized() {
        // 模型最常见的两种「整天」写法
        assertTrue(TimeSpec.isAllDay(TimeSpec.ByClock(0, 23 * 60 + 59), timetable))
        assertTrue(TimeSpec.isAllDay(TimeSpec.ByClock(0, 24 * 60), timetable))
    }

    @Test
    fun a_normal_event_is_not_all_day() {
        val morning = TimeSpec.ByClock(8 * 60 + 20, 10 * 60)
        assertFalse(TimeSpec.isAllDay(morning, timetable))
        assertEquals("08:20-10:00", TimeSpec.label(morning, timetable))

        // 从 0 点开始但只持续一段时间的，也不算全天
        val early = TimeSpec.ByClock(0, 2 * 60)
        assertFalse(TimeSpec.isAllDay(early, timetable))
    }

    @Test
    fun nearly_full_day_is_not_mistaken_for_all_day() {
        // 22:00-23:59 是「晚上很长一段」，不是全天
        val late = TimeSpec.ByClock(22 * 60, 23 * 60 + 59)
        assertFalse(TimeSpec.isAllDay(late, timetable))
    }

    @Test
    fun session_exposes_all_day_and_a_readable_time_label() {
        val allDay = Session(
            id = "x@2026-10-21",
            eventId = "x",
            date = java.time.LocalDate.of(2026, 10, 21),
            spec = TimeSpec.ALL_DAY,
            title = "交作业",
            kind = EventKind.DEADLINE,
            location = "",
            teacher = "",
            note = "",
            state = SessionState.NORMAL,
            done = false,
            source = "",
            sourceText = "",
            sourceImagePath = "",
            startMinute = 0,
            endMinute = 24 * 60,
        )
        assertTrue(allDay.isAllDay)
        assertEquals("全天", allDay.timeLabel)

        val normal = allDay.copy(
            spec = TimeSpec.ByClock(14 * 60 + 30, 16 * 60),
            startMinute = 14 * 60 + 30,
            endMinute = 16 * 60,
        )
        assertFalse(normal.isAllDay)
        assertEquals("14:30-16:00", normal.timeLabel)
    }

    @Test
    fun all_day_requires_end_after_start_so_the_spec_is_constructible() {
        // ByClock 有 init 校验：结束必须晚于开始。ALL_DAY 必须满足它，
        // 否则一构造就抛异常（这种错误只有真跑一遍才发现）。
        val spec = TimeSpec.ALL_DAY
        assertTrue(spec.endMinute > spec.startMinute)
    }

    // ---------------------------------------------------------------- 截止型

    @Test
    fun a_deadline_without_a_clock_time_becomes_all_day() {
        /*
         * 真机上出现过脏数据：`线代作业` 被写成 `00:00–15:45`，
         * 于是在课程表里铺满半列，看着像一条长达 15 小时的安排。
         *
         * 规则：截止型 + 原文没写钟点 → 存成「全天」。
         * 靠节次折算的也不算写了钟点（那时的开始时刻是占位值）。
         */
        val noTime = CandidateParser.parse(
            content = """{"items":[{"title":"交作业","kind":"deadline","date":"2026-10-21"}]}""",
            originalText = "10月21日交作业",
            today = java.time.LocalDate.of(2026, 10, 18),
            timetable = timetable,
        )
        assertEquals(1, noTime.size)
        assertFalse("没写钟点时不该算 explicit", noTime[0].timeWasExplicit)
        assertTrue("截止型没写时刻应该落成全天", noTime[0].shouldBeAllDay)
    }

    @Test
    fun a_deadline_with_an_explicit_time_keeps_that_time() {
        val withTime = CandidateParser.parse(
            content = """{"items":[{"title":"交作业","kind":"deadline","date":"2026-10-21","start":"22:00","end":"23:00"}]}""",
            originalText = "10月21日 22:00-23:00 交作业",
            today = java.time.LocalDate.of(2026, 10, 18),
            timetable = timetable,
        )
        assertEquals(1, withTime.size)
        assertTrue(withTime[0].timeWasExplicit)
        assertFalse("写了具体时刻就按具体时刻存", withTime[0].shouldBeAllDay)
        assertEquals(22 * 60, withTime[0].startMinute)
    }

    @Test
    fun a_non_deadline_without_time_is_not_turned_into_all_day() {
        // 普通行程没写时刻时，仍然落到 08:00 占位 + 警告，由用户改。
        // 不要把它也变成全天——那会掩盖掉「这条时间没读出来」这个事实。
        val activity = CandidateParser.parse(
            content = """{"items":[{"title":"开会","kind":"activity","date":"2026-10-21"}]}""",
            originalText = "10月21日开会",
            today = java.time.LocalDate.of(2026, 10, 18),
            timetable = timetable,
        )
        assertEquals(1, activity.size)
        assertFalse(activity[0].shouldBeAllDay)
        assertTrue("应该提示时间没识别出来", activity[0].warnings.any { it.contains("08:00") })
    }
}
