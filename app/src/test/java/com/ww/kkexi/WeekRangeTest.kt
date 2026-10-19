package com.ww.kkexi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 课程表网格的时间范围计算。
 *
 * 这组测试来自一个**真机上看到的 bug**：晚上 9 点打开课程表，
 * 网格被拉成 06:00–24:00，早上全空、而且滚不动多少，
 * 当前时间线还被挤到屏幕外看不见。
 *
 * 根因是「全天」型日程的区间是 00:00–24:00，被算进了 min/max。
 * 全天型没有「几点」，不该参与时间轴范围。
 */
class WeekRangeTest {

    private fun session(
        startMinute: Int,
        endMinute: Int,
        allDay: Boolean = false,
    ) = Session(
        id = "s$startMinute-$endMinute",
        eventId = "e",
        date = LocalDate.of(2026, 10, 18),
        spec = if (allDay) TimeSpec.ALL_DAY else TimeSpec.ByClock(startMinute, endMinute),
        title = "测试",
        kind = EventKind.ACTIVITY,
        location = "",
        teacher = "",
        note = "",
        state = SessionState.NORMAL,
        done = false,
        source = "",
        sourceText = "",
        sourceImagePath = "",
        startMinute = startMinute,
        endMinute = endMinute,
    )

    @Test
    fun range_covers_the_earliest_and_latest_timed_session() {
        val range = computeVisibleRange(
            listOf(session(10 * 60 + 20, 11 * 60 + 5), session(15 * 60 + 50, 16 * 60 + 35)),
            nowMinute = null,
        )
        // 早的往前留 30 分钟并向下取整到整点 → 09:00
        assertEquals(9 * 60, range.first)
        // 晚的往后留 30 分钟（16:35+30=17:05）并**向上**取整到整点 → 18:00。
        // 终点必须向上取整：改成向下取整时，如果最后一条正好在整点结束，
        // 它的下半截会被切到网格外面去。
        assertEquals(18 * 60, range.last)
        // 两个边界都必须真的被覆盖到
        assertTrue(10 * 60 + 20 in range)
        assertTrue(16 * 60 + 35 in range)
    }

    @Test
    fun now_is_included_so_the_current_time_line_is_always_visible() {
        // 只有上午的课，但「现在」是 21:00 → 范围必须延伸到 21:00 之后
        val range = computeVisibleRange(
            listOf(session(10 * 60 + 20, 11 * 60 + 5)),
            nowMinute = 21 * 60,
        )
        assertTrue("当前时刻必须落在范围内：$range", 21 * 60 in range.first..range.last)
    }

    @Test
    fun grid_extends_comfortably_past_now_so_the_time_line_is_not_glued_to_the_bottom() {
        /*
         * 回归测试，来自真机截图：滚动只能滚到「网格末端贴住屏幕底」，
         * 所以如果范围刚好到「现在」，滚到底时时间线就在屏幕最下面一行，
         * 看着像卡住了。
         *
         * 要求：范围末端比「现在」至少多 60 分钟。
         */
        for (now in listOf(8 * 60, 14 * 60 + 30, 21 * 60, 23 * 60)) {
            val range = computeVisibleRange(
                listOf(session(10 * 60 + 20, 11 * 60 + 5)),
                nowMinute = now,
            )
            assertTrue(
                "现在=${now / 60}:${now % 60} 时，范围末端 ${range.last / 60}:00 距现在太近",
                range.last - now >= 60,
            )
        }
    }

    @Test
    fun all_day_sessions_never_reach_this_function_and_do_not_stretch_the_grid() {
        // 这条是那个 bug 的回归测试：全天型被调用方过滤掉，
        // 所以即使「今天只有一件全天的事」，范围也不该被拉到 24:00。
        val range = computeVisibleRange(emptyList(), nowMinute = null)
        assertTrue("没有任何定时日程时，范围应该是一个合理的默认窗口：$range", range.last - range.first <= 12 * 60)

        // 对照：如果错误地把全天型（0..1440）算进来，范围会变成几乎整天
        val withAllDayMistake = computeVisibleRange(listOf(session(0, 24 * 60, allDay = true)), null)
        assertEquals("这就是不排除全天型时的错误结果（0..1440 被夹到 06:00-24:00）", 24 * 60, withAllDayMistake.last)
    }

    @Test
    fun early_morning_session_is_inside_the_visible_range() {
        // 这条曾经是「凌晨 3 点的事不该把网格从 03:00 开始」的旧约定（强制从 06:00 起）。
        // 真机上因此出过 bug：02:00 的课在时间轴里有、课程表里完全看不见。
        // 现在改为「有早于 06:00 的日程就从它前面开始」，宁可多画一段空白，也不能藏掉日程。
        val early = computeVisibleRange(listOf(session(3 * 60, 4 * 60)), nowMinute = null)
        assertTrue("范围必须覆盖 03:00 的日程：$early", early.first <= 3 * 60)
        assertTrue("且不能把网格拉到 0 点以前：$early", early.first >= 0)

        // 深夜的事也不该超过 24:00
        val late = computeVisibleRange(listOf(session(23 * 60, 24 * 60)), nowMinute = null)
        assertEquals(24 * 60, late.last)
    }

    @Test
    fun range_is_never_shorter_than_one_hour() {
        val range = computeVisibleRange(emptyList(), nowMinute = null)
        assertTrue("范围太小会让网格高得离谱或负高：$range", range.last - range.first >= 60)
    }

    // ---------------------------------------------------------------- 裁剪

    @Test
    fun clip_keeps_a_session_that_sits_inside_the_grid() {
        // 网格 14:00 起、8 小时；日程 15:00-16:00 原样保留
        val clipped = clipToRange(15 * 60, 16 * 60, rangeStart = 14 * 60, rangeMinutes = 8 * 60)
        assertEquals(15 * 60 to 16 * 60, clipped)
    }

    @Test
    fun clip_trims_a_session_that_starts_before_the_grid() {
        /*
         * 这就是那个「整列被盖住」的 bug：
         * 库里一条死线是 00:00–15:45，而网格从 14:00 开始。
         * 裁剪后应该只剩 14:00–15:45（1 小时 45 分），而不是 15.75 小时。
         */
        val clipped = clipToRange(0, 15 * 60 + 45, rangeStart = 14 * 60, rangeMinutes = 10 * 60)
        assertEquals(14 * 60 to 15 * 60 + 45, clipped)
        assertEquals("裁剪后的时长必须是 1:45，不是 15:45", 105, clipped!!.second - clipped.first)
    }

    @Test
    fun clip_trims_a_session_that_ends_after_the_grid() {
        // 21:00-24:00 的日程，网格到 22:00 → 只剩 21:00-22:00
        val clipped = clipToRange(21 * 60, 24 * 60, rangeStart = 14 * 60, rangeMinutes = 8 * 60)
        assertEquals(21 * 60 to 22 * 60, clipped)

        // 正好从网格结束时刻开始的日程，可见高度为 0 → 不画
        assertEquals(null, clipToRange(22 * 60, 24 * 60, rangeStart = 14 * 60, rangeMinutes = 8 * 60))
    }

    @Test
    fun clip_drops_a_session_entirely_outside_the_grid() {
        // 早上 8 点的课，网格是 14:00-22:00 → 不该画
        assertEquals(null, clipToRange(8 * 60, 9 * 60, rangeStart = 14 * 60, rangeMinutes = 8 * 60))
        // 晚上 23 点的课，网格到 22:00 → 也不该画
        assertEquals(null, clipToRange(23 * 60, 24 * 60, rangeStart = 14 * 60, rangeMinutes = 8 * 60))
    }

    @Test
    fun clip_result_is_never_inverted() {
        // 防御性：无论输入多离谱，结果要么为空、要么 start < end
        val cases = listOf(
            Triple(0, 1440, 14 * 60),
            Triple(600, 600, 9 * 60),
            Triple(-100, 100, 9 * 60),
            Triple(1400, 1300, 9 * 60),
        )
        for ((start, end, rangeStart) in cases) {
            val clipped = clipToRange(start, end, rangeStart, 8 * 60)
            if (clipped != null) {
                assertTrue("裁剪结果反了：$clipped（输入 $start-$end）", clipped.first < clipped.second)
            }
        }
    }
}
