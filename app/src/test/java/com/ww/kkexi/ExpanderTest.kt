package com.ww.kkexi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * [Expander]：把 [Event] + [EventException] 展开成 [Session]（源：`Domain.kt:242-339`）。
 *
 * 展开的两段逻辑必须分开看，否则会误判：
 * - `occurrenceDates`（`Domain.kt:305-322`）：先按周次生成「原规则命中日期」，
 *   区间扫描范围是 `maxOf(weekOf(from) - 1, 1) .. minOf(weekOf(to) + 1, totalWeeks)`（`Domain.kt:312-313`），
 *   再用 `date in from..to` 过滤，最后 `filter { !it.isBefore(event.anchorDate) }` 做 **anchorDate 截断**（`Domain.kt:321`）。
 * - 例外覆盖（`Domain.kt:263-299`）：只对上面这些日期生效 —— 例外**不能**凭空添加规则外的日期，
 *   也不能删除某次（CANCELLED 只是改状态），MOVED 只是换日期。
 */
class ExpanderTest {

    private val windowFrom = LocalDate.of(2026, 9, 7)
    private val windowTo = LocalDate.of(2026, 10, 31)

    /** 2026-09-21 / 09-28 / 10-05 / 10-12 / 10-19 / 10-26 —— 第 3-16 周每周一课程在该窗口内的命中日。 */
    private val expectedMondays = listOf(
        LocalDate.of(2026, 9, 21),
        LocalDate.of(2026, 9, 28),
        LocalDate.of(2026, 10, 5),
        LocalDate.of(2026, 10, 12),
        LocalDate.of(2026, 10, 19),
        LocalDate.of(2026, 10, 26),
    )

    private fun seed(id: String): Event = SeedData.events.first { it.id == id }

    private fun courseEvent(
        id: String = "e-mon",
        day: DayOfWeek = DayOfWeek.MONDAY,
        startWeek: Int = 3,
        endWeek: Int = 16,
        parity: WeekParity = WeekParity.EVERY,
        weeks: List<Int> = emptyList(),
        anchorDate: LocalDate = Semester.mondayOf(startWeek).plusDays((day.value - 1).toLong()),
        firstPeriod: Int = 3,
        periodCount: Int = 2,
        title: String = "测试课",
        location: String = "A101",
        teacher: String = "某老师",
        note: String = "",
        source: String = "测试来源",
    ): Event = Event(
        id = id,
        title = title,
        kind = EventKind.COURSE,
        location = location,
        note = note,
        teacher = teacher,
        anchorDate = anchorDate,
        time = TimeSpec.ByPeriod(firstPeriod, periodCount),
        recurrence = Recurrence(day, startWeek, endWeek, parity, weeks),
        source = source,
    )

    private fun singleEvent(
        anchorDate: LocalDate,
        id: String = "e-once",
        title: String = "一次性行程",
        kind: EventKind = EventKind.ACTIVITY,
        // 时间参数也换成 TimeSpec，跟生产代码保持一致（用户要求「时间点优先」之后，
        // 一次性行程既可能按节次录，也可能按时刻录——测试两种都要能表达）。
        time: TimeSpec = TimeSpec.ByPeriod(9, 1),
        location: String = "A101",
        teacher: String = "",
        note: String = "备注原文",
        done: Boolean = false,
        source: String = "测试来源",
    ): Event = Event(
        id = id,
        title = title,
        kind = kind,
        location = location,
        note = note,
        teacher = teacher,
        anchorDate = anchorDate,
        time = time,
        done = done,
        source = source,
    )

    // ---------- sessionId ----------

    @Test
    fun session_id_is_stable_and_reproducible() {
        val first = Expander.sessionId("x", LocalDate.of(2026, 10, 14))
        val second = Expander.sessionId("x", LocalDate.of(2026, 10, 14))
        assertEquals("x@2026-10-14", first)
        assertEquals("同一事件同一天两次调用必须相等", first, second)
        assertNotEquals(first, Expander.sessionId("x", LocalDate.of(2026, 10, 15)))
        assertNotEquals(first, Expander.sessionId("y", LocalDate.of(2026, 10, 14)))
    }

    @Test
    fun expanded_sessions_reuse_the_same_id_as_session_id() {
        val event = seed("d-math-mon")
        val sessions = Expander.expand(event, windowFrom, windowTo)
        assertEquals(6, sessions.size)
        for (session in sessions) {
            assertEquals(Expander.sessionId(event.id, session.date), session.id)
        }
        // 两次独立展开必须给出逐条相同的 id（界面靠它对上「取消/改期」）。
        val again = Expander.expand(event, windowFrom, windowTo)
        assertEquals(sessions.map { it.id }, again.map { it.id })
    }

    // ---------- 单次事件 ----------

    @Test
    fun single_event_appears_only_on_its_own_date() {
        val event = singleEvent(
            anchorDate = LocalDate.of(2026, 10, 14),
            id = "e-committee",
            title = "示例会议",
            kind = EventKind.DEADLINE,
            time = TimeSpec.ByPeriod(9, 1),
            location = "A101",
            note = "原文：周四晚上5点 A101",
            done = true,
            source = "示例来源",
        )
        val sessions = Expander.expand(event, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))
        assertEquals(1, sessions.size)
        val session = sessions[0]
        assertEquals("e-committee@2026-10-14", session.id)
        assertEquals("e-committee", session.eventId)
        assertEquals(LocalDate.of(2026, 10, 14), session.date)
        assertEquals(9, session.specFirstPeriodForTest)
        assertEquals(1, session.specPeriodCountForTest)
        assertEquals("示例会议", session.title)
        assertEquals(EventKind.DEADLINE, session.kind)
        assertEquals("A101", session.location)
        assertEquals("原文：周四晚上5点 A101", session.note)
        assertEquals("", session.teacher)
        assertEquals(SessionState.NORMAL, session.state)
        assertTrue("done 必须从事件带到这一次发生上", session.done)
        assertEquals("示例来源", session.source)
    }

    @Test
    fun single_event_outside_the_range_expands_to_nothing() {
        val event = singleEvent(anchorDate = LocalDate.of(2026, 10, 14))
        assertTrue(Expander.expand(event, LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 31)).isEmpty())
        assertTrue(Expander.expand(event, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 13)).isEmpty())
        assertTrue(Expander.expand(event, LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 31)).isEmpty())
    }

    @Test
    fun range_bounds_are_inclusive_for_single_events() {
        val event = singleEvent(anchorDate = LocalDate.of(2026, 10, 14))
        val day = LocalDate.of(2026, 10, 14)
        assertEquals(1, Expander.expand(event, day, day).size)
        assertEquals(1, Expander.expand(event, day, day.plusDays(30)).size)
        assertEquals(1, Expander.expand(event, day.minusDays(30), day).size)
    }

    @Test
    fun inverted_range_yields_nothing() {
        val once = singleEvent(anchorDate = LocalDate.of(2026, 10, 14))
        assertTrue(Expander.expand(once, windowTo, windowFrom).isEmpty())
        val recurring = seed("d-math-mon")
        assertTrue(Expander.expand(recurring, windowTo, windowFrom).isEmpty())
    }

    // ---------- 重复事件 ----------

    @Test
    fun recurring_monday_course_hits_exactly_six_dates_in_the_autumn_window() {
        // 第 3 周开始、第 16 周结束、每周一 —— anchorDate = 第 3 周周一 = 2026-09-21。
        val event = courseEvent(id = "e-mon", startWeek = 3, endWeek = 16, day = DayOfWeek.MONDAY)
        assertEquals(LocalDate.of(2026, 9, 21), event.anchorDate)
        val sessions = Expander.expand(event, windowFrom, windowTo)
        assertEquals(expectedMondays, sessions.map { it.date })
        assertEquals(6, sessions.size)
        for (session in sessions) {
            assertEquals(SessionState.NORMAL, session.state)
            assertEquals(3, session.specFirstPeriodForTest)
            assertEquals(2, session.specPeriodCountForTest)
        }
    }

    @Test
    fun dates_before_the_rule_start_week_are_never_generated() {
        // 09-07（第 1 周周一）与 09-14（第 2 周周一）落在窗口内，但 startWeek=3，
        // Recurrence.matchesWeek 直接判否（Domain.kt:106），所以它们不是被 anchorDate 滤掉的。
        val event = courseEvent(id = "e-mon", startWeek = 3, endWeek = 16, day = DayOfWeek.MONDAY)
        val dates = Expander.expand(event, windowFrom, windowTo).map { it.date }
        assertFalse("第 1 周周一不应出现", dates.contains(LocalDate.of(2026, 9, 7)))
        assertFalse("第 2 周周一不应出现", dates.contains(LocalDate.of(2026, 9, 14)))
        assertFalse(event.recurrence!!.matchesWeek(1))
        assertFalse(event.recurrence!!.matchesWeek(2))
        assertTrue(event.recurrence!!.matchesWeek(3))
    }

    @Test
    fun dates_before_anchor_date_are_truncated() {
        // 这条才是 anchorDate 截断：规则从第 1 周起命中，但首次发生日被写成第 3 周周一，
        // 于是 09-07、09-14 先被生成、再被 Domain.kt:321 的 filter 去掉。
        val fromWeek1 = courseEvent(
            id = "e-mon-w1",
            startWeek = 1,
            endWeek = 16,
            day = DayOfWeek.MONDAY,
        )
        assertEquals(LocalDate.of(2026, 9, 7), fromWeek1.anchorDate)
        val withoutTruncation = Expander.expand(fromWeek1, windowFrom, windowTo).map { it.date }
        assertEquals(
            listOf(
                LocalDate.of(2026, 9, 7),
                LocalDate.of(2026, 9, 14),
                LocalDate.of(2026, 9, 21),
                LocalDate.of(2026, 9, 28),
                LocalDate.of(2026, 10, 5),
                LocalDate.of(2026, 10, 12),
                LocalDate.of(2026, 10, 19),
                LocalDate.of(2026, 10, 26),
            ),
            withoutTruncation,
        )

        val truncated = courseEvent(
            id = "e-mon-w1-late-anchor",
            startWeek = 1,
            endWeek = 16,
            day = DayOfWeek.MONDAY,
            anchorDate = LocalDate.of(2026, 9, 21),
        )
        val withTruncation = Expander.expand(truncated, windowFrom, windowTo)
        assertEquals(expectedMondays, withTruncation.map { it.date })
        assertFalse(withTruncation.map { it.date }.contains(LocalDate.of(2026, 9, 7)))
        assertFalse(withTruncation.map { it.date }.contains(LocalDate.of(2026, 9, 14)))
    }

    @Test
    fun expansion_window_filters_out_dates_outside_from_and_to() {
        val event = seed("d-math-mon")
        // 10-12 是周一（第 6 周）；窗口从 10-13（周二）起就不该再出现。
        assertEquals(1, Expander.expand(event, LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 14)).size)
        assertTrue(Expander.expand(event, LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 14)).isEmpty())
        assertTrue(Expander.expand(event, LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 18)).isEmpty())
    }

    @Test
    fun odd_week_wednesday_course_skips_week6_and_appears_in_week7() {
        val event = seed("d-english-wed-odd")
        assertEquals(WeekParity.ODD, event.recurrence!!.parity)
        assertEquals(DayOfWeek.WEDNESDAY, event.recurrence!!.dayOfWeek)
        val sessions = Expander.expand(event, LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 25))
        // 2026-10-14 是第 6 周（双周）→ 不出现；2026-10-21 是第 7 周（单周）→ 出现。
        assertEquals(6, Semester.weekOf(LocalDate.of(2026, 10, 14)))
        assertEquals(7, Semester.weekOf(LocalDate.of(2026, 10, 21)))
        assertEquals(listOf(LocalDate.of(2026, 10, 21)), sessions.map { it.date })
        assertFalse(sessions.any { it.date == LocalDate.of(2026, 10, 14) })
    }

    @Test
    fun even_week_wednesday_course_appears_on_2026_10_14() {
        val event = seed("d-english-wed-even")
        // 双周课：第 6 周（2026-10-14 所在周）是双周，应该出现；第 7 周不该出现。
        assertEquals("示例数据里双周课必须从双周开始", 0, event.recurrence!!.startWeek % 2)
        assertTrue("第 6 周应命中", event.recurrence!!.matchesWeek(6))
        assertFalse("第 7 周不该命中", event.recurrence!!.matchesWeek(7))
        val sessions = Expander.expand(event, LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 25))
        // 第 8 周那次是 2026-10-28，落在窗口外。
        assertEquals(listOf(LocalDate.of(2026, 10, 14)), sessions.map { it.date })
    }

    @Test
    fun weeks_after_total_weeks_are_not_generated() {
        val event = courseEvent(id = "e-mon-long", startWeek = 3, endWeek = 30, day = DayOfWeek.MONDAY)
        val sessions = Expander.expand(event, Semester.mondayOf(1), Semester.sundayOf(19))
        // 第 3..19 周共 17 次；第 20 周起被 minOf(..., Semester.totalWeeks) 截住（Domain.kt:313）。
        assertEquals(17, sessions.size)
        assertEquals(Semester.mondayOf(19), sessions.last().date)
        assertEquals(LocalDate.of(2027, 1, 11), sessions.last().date)
        assertFalse(sessions.any { it.date == LocalDate.of(2026, 9, 7) })
        assertFalse(sessions.any { it.date == LocalDate.of(2026, 9, 14) })
    }

    @Test
    fun sessions_are_sorted_and_free_of_duplicates() {
        val event = seed("d-program-tue")
        val sessions = Expander.expand(event, Semester.mondayOf(1), Semester.sundayOf(19))
        val dates = sessions.map { it.date }
        assertEquals(dates.sorted(), dates)
        assertEquals(dates.distinct().size, dates.size)
        // 次数按规则的周数算出来，不写死数字——写死的话，改示例数据就会假失败，
        // 而假失败会训练人去改断言而不是查问题。
        val rule = event.recurrence!!
        assertEquals(rule.endWeek - rule.startWeek + 1, dates.size)
    }

    @Test
    fun whole_semester_occurrence_counts_of_seed_courses() {
        val semesterFrom = Semester.mondayOf(1)
        val semesterTo = Semester.sundayOf(19)

        // 期望值一律**由规则的周数算出来**，不写死数字。
        // 用规则算的好处：断言检查的是「展开结果与规则一致」这个真正的不变量，
        // 而不是「某次转录时的数字」——后者不是 bug，只是数据换了。
        for (event in SeedData.events) {
            val rule = event.recurrence ?: continue
            val sessions = Expander.expand(event, semesterFrom, semesterTo)
            val expected = (rule.startWeek..rule.endWeek).count { rule.matchesWeek(it) }
            assertEquals("${event.id} 展开次数应与它的周次规则一致", expected, sessions.size)
        }

        // 再抽查两条具体的，确保规则本身没被写成空转
        assertEquals("第 3-16 周每周一 = 14 次", 14, Expander.expand(seed("d-math-mon"), semesterFrom, semesterTo).size)
        assertEquals("第 6-16 周每周一 = 11 次", 11, Expander.expand(seed("d-officehour-mon"), semesterFrom, semesterTo).size)
    }

    // ---------- 例外覆盖 ----------

    @Test
    fun cancelled_session_stays_in_the_result_with_cancelled_state() {
        val event = seed("d-math-mon")
        val baseline = Expander.expand(event, windowFrom, windowTo)
        val cancelledDate = LocalDate.of(2026, 10, 12)
        val exception = EventException(
            eventId = event.id,
            originalDate = cancelledDate,
            state = SessionState.CANCELLED,
        )
        val sessions = Expander.expand(event, windowFrom, windowTo, listOf(exception))
        assertEquals("取消不是删除，条数不变", baseline.size, sessions.size)
        assertEquals(baseline.map { it.date }, sessions.map { it.date })
        val cancelled = sessions.first { it.date == cancelledDate }
        assertEquals(SessionState.CANCELLED, cancelled.state)
        assertEquals(Expander.sessionId(event.id, cancelledDate), cancelled.id)
        assertEquals(event.title, cancelled.title)
        assertEquals(event.specFirstPeriodForTest, cancelled.specFirstPeriodForTest)
        for (session in sessions.filter { it.date != cancelledDate }) {
            assertEquals(SessionState.NORMAL, session.state)
        }
    }

    @Test
    fun moved_session_lands_on_the_new_date_with_an_adjustment_note() {
        val event = seed("d-math-mon")
        val original = LocalDate.of(2026, 10, 12)
        val target = LocalDate.of(2026, 10, 15)
        val exception = EventException(
            eventId = event.id,
            originalDate = original,
            state = SessionState.MOVED,
            movedToDate = target,
        )
        val sessions = Expander.expand(event, windowFrom, windowTo, listOf(exception))
        assertEquals(6, sessions.size)
        assertFalse("原日期不该再有一次发生", sessions.any { it.date == original })
        val moved = sessions.first { it.state == SessionState.MOVED }
        assertEquals(target, moved.date)
        assertEquals(Expander.sessionId(event.id, target), moved.id)
        assertTrue("note 应含「调整」字样，实际：${moved.note}", moved.note.contains("调整"))
        assertEquals("由 10月12日 调整", moved.note)
        assertEquals(event.specFirstPeriodForTest, moved.specFirstPeriodForTest)
        assertEquals(event.specPeriodCountForTest, moved.specPeriodCountForTest)
        assertEquals(event.location, moved.location)
        assertEquals(event.title, moved.title)
    }

    @Test
    fun moved_session_can_override_periods_location_and_note() {
        val event = seed("d-math-mon")
        val original = LocalDate.of(2026, 10, 12)
        val exception = EventException(
            eventId = event.id,
            originalDate = original,
            state = SessionState.MOVED,
            movedToDate = LocalDate.of(2026, 10, 15),
            movedTime = TimeSpec.ByPeriod(7, 2),
            movedLocation = "H9999",
            note = "老师另行通知",
        )
        val moved = Expander.expand(event, windowFrom, windowTo, listOf(exception))
            .first { it.state == SessionState.MOVED }
        assertEquals(LocalDate.of(2026, 10, 15), moved.date)
        assertEquals(7, moved.specFirstPeriodForTest)
        assertEquals(2, moved.specPeriodCountForTest)
        assertEquals("H9999", moved.location)
        assertEquals("老师另行通知", moved.note)
        assertEquals("15:50", moved.startLabel)
        assertEquals("17:30", moved.endLabel)
    }

    @Test
    fun moved_session_without_target_date_keeps_the_original_date() {
        val event = seed("d-math-mon")
        val original = LocalDate.of(2026, 10, 12)
        val exception = EventException(
            eventId = event.id,
            originalDate = original,
            state = SessionState.MOVED,
        )
        val moved = Expander.expand(event, windowFrom, windowTo, listOf(exception))
            .first { it.state == SessionState.MOVED }
        assertEquals("movedToDate 为空时回落原日期（Domain.kt:276）", original, moved.date)
        assertEquals(Expander.sessionId(event.id, original), moved.id)
        assertEquals("由 10月12日 调整", moved.note)
    }

    @Test
    fun extra_session_is_labelled_as_a_makeup_class() {
        val event = seed("d-math-mon")
        val exception = EventException(
            eventId = event.id,
            originalDate = LocalDate.of(2026, 10, 12),
            state = SessionState.EXTRA,
            movedToDate = LocalDate.of(2026, 10, 16),
        )
        val extra = Expander.expand(event, windowFrom, windowTo, listOf(exception))
            .first { it.state == SessionState.EXTRA }
        assertEquals(LocalDate.of(2026, 10, 16), extra.date)
        assertEquals(Expander.sessionId(event.id, LocalDate.of(2026, 10, 16)), extra.id)
        assertEquals("补课", extra.note)
    }

    @Test
    fun exception_on_a_date_the_rule_never_hits_changes_nothing() {
        // 周一的课，例外写在了 2026-10-14（周三）：byDate 只在原规则命中的日期上查（Domain.kt:270-271）。
        val event = seed("d-math-mon")
        val baseline = Expander.expand(event, windowFrom, windowTo)
        val exception = EventException(
            eventId = event.id,
            originalDate = LocalDate.of(2026, 10, 14),
            state = SessionState.CANCELLED,
        )
        val sessions = Expander.expand(event, windowFrom, windowTo, listOf(exception))
        assertEquals(baseline.map { it.date }, sessions.map { it.date })
        for (session in sessions) {
            assertEquals(SessionState.NORMAL, session.state)
        }
    }

    @Test
    fun exceptions_of_other_events_are_ignored() {
        val event = seed("d-math-mon")
        val baseline = Expander.expand(event, windowFrom, windowTo)
        val foreign = EventException(
            eventId = "someone-else",
            originalDate = LocalDate.of(2026, 10, 12),
            state = SessionState.CANCELLED,
        )
        val sessions = Expander.expand(event, windowFrom, windowTo, listOf(foreign))
        assertEquals(baseline.map { it.date }, sessions.map { it.date })
        for (session in sessions) {
            assertEquals(SessionState.NORMAL, session.state)
        }
    }

    @Test
    fun exception_state_applies_to_every_expansion_of_the_same_date() {
        val event = seed("d-math-mon")
        val exception = EventException(
            eventId = event.id,
            originalDate = LocalDate.of(2026, 10, 12),
            state = SessionState.CANCELLED,
        )
        val wide = Expander.expand(event, windowFrom, windowTo, listOf(exception))
        val narrow = Expander.expand(event, LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 12), listOf(exception))
        assertEquals(1, narrow.size)
        assertEquals(SessionState.CANCELLED, narrow[0].state)
        assertEquals(wide.first { it.date == LocalDate.of(2026, 10, 12) }.id, narrow[0].id)
    }

    // ---------- Session 派生字段 ----------

    @Test
    fun session_labels_follow_the_periods_table() {
        // d-math-mon 在第 1-2 节 → 08:20-10:00（默认作息表）。
        // 曾经这里断言的是 10:20-12:00，那是**旧示例数据**里那门课排在第 3-4 节的时刻；
        // 换数据后要对齐新数据，而不是让断言去迁就。
        val linear = Expander.expand(seed("d-math-mon"), LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 12))[0]
        assertEquals(500, linear.startMinute)
        assertEquals(600, linear.endMinute)
        assertEquals("08:20", linear.startLabel)
        assertEquals("10:00", linear.endLabel)
        assertEquals("第1-2节", linear.periodLabel)
        assertEquals("08:20-10:00", DateTimeExt.timeRange(linear))

        val officeHour = Expander.expand(
            seed("d-officehour-mon"),
            LocalDate.of(2026, 10, 12),
            LocalDate.of(2026, 10, 12),
        )[0]
        assertEquals(780, officeHour.startMinute)
        assertEquals(820, officeHour.endMinute)
        assertEquals("13:00", officeHour.startLabel)
        assertEquals("13:40", officeHour.endLabel)
        assertEquals("第0节", officeHour.periodLabel)

        val once = Expander.expand(singleEvent(anchorDate = LocalDate.of(2026, 10, 14)), windowFrom, windowTo)[0]
        assertEquals("第9节", once.periodLabel)
        assertEquals("17:40", once.startLabel)
        assertEquals("18:25", once.endLabel)
    }

    @Test
    fun session_end_time_falls_back_to_start_when_the_period_range_is_invalid() {
        // Domain.kt:202-203：startMinute 用 `?: 0`，endMinute 用 `?: startMinute`——都不报错。
        val overflow = Expander.expand(
            singleEvent(anchorDate = LocalDate.of(2026, 10, 14), time = TimeSpec.ByPeriod(12, 2)),
            windowFrom,
            windowTo,
        )[0]
        assertEquals(1280, overflow.startMinute)
        assertNull("第 13 节不在作息表里", Timetable.DEFAULT.endMinute(13))
        assertEquals(overflow.startMinute, overflow.endMinute)
        assertEquals(overflow.startLabel, overflow.endLabel)
        assertEquals("21:20", overflow.endLabel)

        val unknownPeriod = Expander.expand(
            singleEvent(anchorDate = LocalDate.of(2026, 10, 14), time = TimeSpec.ByPeriod(13, 1)),
            windowFrom,
            windowTo,
        )[0]
        assertEquals(0, unknownPeriod.startMinute)
        assertEquals("00:00", unknownPeriod.startLabel)
        assertEquals("00:00", unknownPeriod.endLabel)
    }
}
