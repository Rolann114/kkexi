package com.ww.kkexi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 「具体时刻」（[TimeSpec.ByPoint]）的回归测试。
 *
 * 起因是用户 2026-10-19 的反馈：「对时间节点的相关事情，
 * 我发现没有一个具体时间点的选项，截止日期居然是个时间段」。
 *
 * 这一档必须满足三件事：
 * 1. 只占一个时间点，界面上只显示「23:59」而不是「23:59-00:59」；
 * 2. 不被 [TimeSpec.isAllDay] / 课表网格误判；
 * 3. 备份往返不丢（否则换手机后又会退化成一段区间）。
 */
class PointTimeTest {

    private val timetable = Timetable.DEFAULT

    @Test
    fun point_resolves_to_the_same_minute_and_is_not_all_day() {
        val spec = TimeSpec.ByPoint(23 * 60 + 59)
        assertEquals(23 * 60 + 59 to 23 * 60 + 59, TimeSpec.resolve(spec, timetable))
        assertFalse(TimeSpec.isAllDay(spec, timetable))
        assertEquals("23:59", TimeSpec.label(spec, timetable))
    }

    @Test
    fun session_exposes_point_and_single_clock_label() {
        val session = Session(
            id = "x@2026-10-21",
            eventId = "x",
            date = LocalDate.of(2026, 10, 21),
            spec = TimeSpec.ByPoint(18 * 60),
            title = "交作业",
            kind = EventKind.DEADLINE,
            location = "",
            teacher = "",
            note = "",
            state = SessionState.NORMAL,
            done = false,
            source = "手动创建",
            sourceText = "",
            sourceImagePath = "",
            startMinute = 18 * 60,
            endMinute = 18 * 60,
        )

        assertTrue(session.isPoint)
        assertEquals("18:00", session.timeLabel)
        assertEquals("", session.periodLabel)
    }

    @Test
    fun point_round_trips_through_backup() {
        val event = Event(
            id = "p-1",
            title = "交作业",
            kind = EventKind.DEADLINE,
            anchorDate = LocalDate.of(2026, 10, 21),
            time = TimeSpec.ByPoint(23 * 60 + 59),
        )

        val imported = Backup.parse(Backup.export(listOf(event), emptyList()))

        assertEquals(1, imported.events.size)
        assertEquals(
            "备份往返后必须还是「具体时刻」，不能退化成一段时间",
            TimeSpec.ByPoint(23 * 60 + 59),
            imported.events[0].time,
        )
    }

    @Test
    fun point_in_an_exception_round_trips_through_backup() {
        val exception = EventException(
            eventId = "p-1",
            originalDate = LocalDate.of(2026, 10, 21),
            state = SessionState.MOVED,
            movedToDate = LocalDate.of(2026, 10, 22),
            movedTime = TimeSpec.ByPoint(20 * 60),
        )

        val imported = Backup.parse(Backup.export(emptyList(), listOf(exception)))

        assertEquals(
            TimeSpec.ByPoint(20 * 60),
            imported.exceptions.single().movedTime,
        )
    }

    @Test
    fun point_session_expands_like_a_single_occurrence() {
        val event = Event(
            id = "p-2",
            title = "报名",
            kind = EventKind.ACTIVITY,
            anchorDate = LocalDate.of(2026, 10, 21),
            time = TimeSpec.ByPoint(12 * 60),
        )

        val sessions = Expander.expand(
            event = event,
            from = LocalDate.of(2026, 10, 19),
            to = LocalDate.of(2026, 10, 25),
            timetable = timetable,
        )

        assertEquals(1, sessions.size)
        assertTrue(sessions.single().isPoint)
        assertEquals("12:00", sessions.single().timeLabel)
    }
}
