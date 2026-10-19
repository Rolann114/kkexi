package com.ww.kkexi

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * [Backup]：JSON 导出/导入（源：`Backup.kt:12-143`）。
 *
 * 往返（round-trip）不变式 = `parse(export(x))` 在语义上等于 `x`：
 * `export`（`Backup.kt:16-67`）把 [Event] 的全部 14 个字段和 [EventException] 的全部 8 个字段写出，
 * `parse`（`Backup.kt:72-143`）逐字段读回；`recurrence` 为空时不写键（`Backup.kt:39`），读回也是 null（`Backup.kt:90`）。
 *
 * 唯一**不**往返的字段是 `movedLocation = ""`（空串会被归一成 null，见 `Backup.kt:134` 与
 * `round_trip_collapses_empty_moved_location_to_null`），所以本文件的样例例外只用 null 或非空串。
 *
 * `org.json` 由 `testImplementation(libs.json)`（`gradle/libs.versions.toml:23`）提供，测试里不碰 Android 实现。
 */
class BackupTest {

    /** 覆盖 [SessionState] 三种例外形态的样例，字段全部可往返。 */
    private val samples = listOf(
        EventException(
            eventId = "c-linear-mon",
            originalDate = LocalDate.of(2026, 10, 12),
            state = SessionState.CANCELLED,
            note = "放假停课",
        ),
        EventException(
            eventId = "c-linear-mon",
            originalDate = LocalDate.of(2026, 10, 19),
            state = SessionState.MOVED,
            movedToDate = LocalDate.of(2026, 10, 20),
            movedTime = TimeSpec.ByPeriod(7, 2),
            movedLocation = "H9999",
            note = "老师调课",
        ),
        EventException(
            eventId = "c-psych-thu",
            originalDate = LocalDate.of(2026, 10, 15),
            state = SessionState.EXTRA,
            movedToDate = LocalDate.of(2026, 10, 16),
            note = "补课",
        ),
    )

    @Test
    fun format_version_is_2() {
        // v1：只有日程 + 改期记录。
        // v2：多了 settings（自定义作息表/类别/外观/提醒提前量）——
        //     换手机时这些也得跟着走，否则要重配一遍，而用户未必记得自己改过哪一节。
        assertEquals(2, Backup.FORMAT_VERSION)
    }

    @Test
    fun export_declares_format_version_and_semester_anchor() {
        val root = JSONObject(Backup.export(SeedData.events, emptyList()))
        assertEquals("kkexi-backup", root.getString("format"))
        assertEquals(Backup.FORMAT_VERSION, root.getInt("formatVersion"))
        assertEquals("2026-09-07", root.getString("semesterFirstMonday"))
        assertEquals(Semester.firstMonday.toString(), root.getString("semesterFirstMonday"))
        assertTrue("导出必须带时间戳", root.has("exportedAt"))
        assertNotNull(LocalDateTime.parse(root.getString("exportedAt")))
    }

    @Test
    fun export_writes_one_entry_per_event_and_per_exception() {
        val root = JSONObject(Backup.export(SeedData.events, samples))
        val events = root.getJSONArray("events")
        val exceptions = root.getJSONArray("exceptions")
        assertEquals(SeedData.events.size, events.length())
        assertEquals(samples.size, exceptions.length())
        for (index in SeedData.events.indices) {
            val item = events.getJSONObject(index)
            assertEquals(SeedData.events[index].id, item.getString("id"))
            val rule = SeedData.events[index].recurrence
            val written = item.optJSONObject("recurrence")
            if (rule == null) {
                assertNull("没有规则就不该写 recurrence 键", written)
            } else {
                assertNotNull("有规则就必须写 recurrence 键", written)
                assertEquals(rule.dayOfWeek.value, written!!.getInt("dayOfWeek"))
                assertEquals(rule.startWeek, written.getInt("startWeek"))
                assertEquals(rule.endWeek, written.getInt("endWeek"))
                assertEquals(rule.parity.name, written.getString("parity"))
                assertEquals(rule.weeks.size, written.getJSONArray("weeks").length())
            }
        }
    }

    @Test
    fun round_trip_preserves_event_count_ids_and_order() {
        val parsed = Backup.parse(Backup.export(SeedData.events, samples))
        assertTrue("干净的往返不该产生错误：${parsed.errors}", parsed.errors.isEmpty())
        assertEquals(SeedData.events.size, parsed.events.size)
        assertEquals(SeedData.events.map { it.id }, parsed.events.map { it.id })
        assertEquals(
            SeedData.events.map { it.id }.toSet(),
            parsed.events.map { it.id }.toSet(),
        )
    }

    @Test
    fun round_trip_preserves_key_fields_of_every_event() {
        val parsed = Backup.parse(Backup.export(SeedData.events, samples))
        for (index in SeedData.events.indices) {
            val original = SeedData.events[index]
            val restored = parsed.events[index]
            assertEquals("title", original.title, restored.title)
            assertEquals("kind", original.kind, restored.kind)
            assertEquals("anchorDate", original.anchorDate, restored.anchorDate)
            assertEquals("firstPeriod", original.specFirstPeriodForTest, restored.specFirstPeriodForTest)
            assertEquals("periodCount", original.specPeriodCountForTest, restored.specPeriodCountForTest)

            val originalRule = original.recurrence
            val restoredRule = restored.recurrence
            if (originalRule == null) {
                assertNull("${original.id} 的一次性事件不该带回 recurrence", restoredRule)
            } else {
                assertNotNull("${original.id} 的 recurrence 丢了", restoredRule)
                val rule = restoredRule!!
                assertEquals("${original.id} dayOfWeek", originalRule.dayOfWeek, rule.dayOfWeek)
                assertEquals("${original.id} startWeek", originalRule.startWeek, rule.startWeek)
                assertEquals("${original.id} endWeek", originalRule.endWeek, rule.endWeek)
                assertEquals("${original.id} parity", originalRule.parity, rule.parity)
                assertEquals("${original.id} weeks", originalRule.weeks, rule.weeks)
            }
        }
    }

    @Test
    fun round_trip_of_seed_events_is_data_class_equal() {
        // 更强的一条：Event 是 data class，全部字段都必须往返（含 location/note/teacher/done/提醒/source）。
        val parsed = Backup.parse(Backup.export(SeedData.events, samples))
        assertEquals(SeedData.events, parsed.events)
    }

    @Test
    fun round_trip_preserves_exceptions() {
        val parsed = Backup.parse(Backup.export(SeedData.events, samples))
        assertEquals(samples, parsed.exceptions)
        assertEquals(SessionState.CANCELLED, parsed.exceptions[0].state)
        assertNull("只取消的例外不带 movedToDate", parsed.exceptions[0].movedToDate)
        assertEquals(LocalDate.of(2026, 10, 20), parsed.exceptions[1].movedToDate)
        assertEquals(7, parsed.exceptions[1].specMovedFirstPeriodForTest)
        assertEquals(2, parsed.exceptions[1].specMovedPeriodCountForTest)
        assertEquals("H9999", parsed.exceptions[1].movedLocation)
        assertEquals(SessionState.EXTRA, parsed.exceptions[2].state)
    }

    @Test
    fun round_trip_of_nothing_is_nothing() {
        val parsed = Backup.parse(Backup.export(emptyList(), emptyList()))
        assertTrue(parsed.errors.isEmpty())
        assertTrue(parsed.events.isEmpty())
        assertTrue(parsed.exceptions.isEmpty())
    }

    @Test
    fun parse_reports_error_and_no_events_for_broken_json() {
        val result = Backup.parse("{坏JSON")
        assertTrue("坏 JSON 必须报错", result.errors.isNotEmpty())
        assertEquals(1, result.errors.size)
        assertTrue(
            "错误信息应说明不是合法 JSON，实际：${result.errors[0]}",
            result.errors[0].startsWith("不是合法 JSON"),
        )
        assertTrue(result.events.isEmpty())
        assertTrue(result.exceptions.isEmpty())
    }

    @Test
    fun parse_accepts_both_v1_and_v2() {
        // v2 起多了 settings。v1 文件（没有 settings）必须继续能导，
        // 否则老用户换手机就导不进自己的备份了。
        val v1 = """{"format":"kkexi-backup","formatVersion":1,"events":[],"exceptions":[]}"""
        val v2 = """{"format":"kkexi-backup","formatVersion":2,"events":[],"exceptions":[]}"""
        assertTrue("v1 不该报版本错误：${Backup.parse(v1).errors}", Backup.parse(v1).errors.isEmpty())
        assertTrue("v2 不该报版本错误：${Backup.parse(v2).errors}", Backup.parse(v2).errors.isEmpty())
    }

    @Test
    fun parse_rejects_a_future_format_version_with_a_clear_message() {
        val text = """{"format":"kkexi-backup","formatVersion":999,"events":[],"exceptions":[]}"""
        val result = Backup.parse(text)
        assertTrue(
            "认不出的版本必须出现在 errors 里，实际：${result.errors}",
            result.errors.any { it.contains("格式版本不认识") },
        )
        assertTrue(result.errors.any { it.contains("文件是 999") })
    }

    @Test
    fun unknown_version_is_reported_but_events_are_still_salvaged() {
        // 刻意的取舍：版本认不出时**记一条警告、但不中断导入**。
        // 理由：用户拿到一份「版本更高的备份」，宁可先把它能认的部分捞回来，
        // 也好过一条都不给——数据丢了比格式警告严重得多。
        // 若将来改成「拒绝导入」，请同步改这条断言。
        val text = """
            {
              "format": "kkexi-backup",
              "formatVersion": 999,
              "events": [
                {
                  "id": "x", "title": "版本不符也要能看见", "kind": "ACTIVITY",
                  "anchorDate": "2026-10-14", "firstPeriod": 9, "periodCount": 1
                }
              ],
              "exceptions": []
            }
        """.trimIndent()
        val result = Backup.parse(text)
        assertTrue(result.errors.any { it.contains("格式版本不认识") })
        assertEquals(1, result.events.size)
        assertEquals("x", result.events[0].id)
    }

    @Test
    fun settings_round_trip_through_export_and_parse() {
        // 换手机场景：作息表、类别、外观、提醒提前量都要能原样回来。
        // 用 PRESETS 里第二份（generic45）当作「用户改过的作息表」，
        // 它和默认值不同，能真正验证「设置被带回来了」而不是碰巧一样。
        val customTimetable = Timetable.PRESETS[1]
        val snapshot = Backup.SettingsSnapshot(
            timetable = customTimetable,
            categories = listOf("行程", "实验", "考试"),
            themeMode = ThemeMode.DARK,
            dynamicColor = true,
            remindersEnabled = false,
            dailyBriefingEnabled = false,
            dailyBriefingMinute = 8 * 60,
            courseLeadMinutes = 20,
            eventLeadMinutes = 45,
        )
        val text = Backup.export(SeedData.events.take(2), emptyList(), snapshot)
        val parsed = Backup.parse(text)

        assertEquals("作息表节数", customTimetable.slots.size, parsed.settings.timetable?.slots?.size)
        assertEquals("第 1 节开始时刻", customTimetable.startMinute(1), parsed.settings.timetable?.startMinute(1))
        assertEquals(listOf("行程", "实验", "考试"), parsed.settings.categories)
        assertEquals(ThemeMode.DARK, parsed.settings.themeMode)
        assertEquals(true, parsed.settings.dynamicColor)
        assertEquals(false, parsed.settings.remindersEnabled)
        assertEquals(8 * 60, parsed.settings.dailyBriefingMinute)
        assertEquals(20, parsed.settings.courseLeadMinutes)
        assertEquals(45, parsed.settings.eventLeadMinutes)
    }

    @Test
    fun v1_backup_without_settings_yields_an_empty_snapshot() {
        // 导入端据此判断「这份备份没带设置」→ 保留用户当前设置，不要覆盖成默认值。
        val text = """{"format":"kkexi-backup","formatVersion":1,"events":[],"exceptions":[]}"""
        assertTrue(Backup.parse(text).settings.isEmpty)
    }

    @Test
    fun parse_skips_a_broken_event_and_keeps_the_rest() {
        val text = """
            {
              "format": "kkexi-backup",
              "formatVersion": 1,
              "events": [
                {
                  "id": "ok", "title": "正常", "kind": "ACTIVITY",
                  "anchorDate": "2026-10-14", "firstPeriod": 9, "periodCount": 1
                },
                {
                  "id": "bad", "kind": "ACTIVITY",
                  "anchorDate": "2026-10-14", "firstPeriod": 9, "periodCount": 1
                }
              ],
              "exceptions": []
            }
        """.trimIndent()
        val result = Backup.parse(text)
        assertEquals("坏行被跳过，好行保留", 1, result.events.size)
        assertEquals("ok", result.events[0].id)
        assertEquals(1, result.errors.size)
        assertTrue(
            "错误信息应指出是第 2 条，实际：${result.errors[0]}",
            result.errors[0].startsWith("第 2 条事件解析失败"),
        )
        assertTrue(result.errors[0].contains("已跳过"))
    }

    @Test
    fun parse_tolerates_missing_events_and_exceptions_arrays() {
        val result = Backup.parse("""{"format":"kkexi-backup","formatVersion":1}""")
        assertTrue(result.errors.isEmpty())
        assertTrue(result.events.isEmpty())
        assertTrue(result.exceptions.isEmpty())
    }

    @Test
    fun round_trip_collapses_empty_moved_location_to_null() {
        // Backup.kt:134：`optString(...).takeIf { it.isNotEmpty() }` 把 "" 归一成 null。
        // 也就是说 null 与 "" 在往返后不再可区分 —— 记录当前行为，别让严格相等断言踩到。
        val source = EventException(
            eventId = "c-linear-mon",
            originalDate = LocalDate.of(2026, 10, 12),
            state = SessionState.MOVED,
            movedToDate = LocalDate.of(2026, 10, 15),
            movedLocation = "",
        )
        assertEquals("", source.movedLocation)
        val parsed = Backup.parse(Backup.export(emptyList(), listOf(source)))
        assertEquals(1, parsed.exceptions.size)
        assertNull("空串地点往返后变成 null", parsed.exceptions[0].movedLocation)
        assertEquals(LocalDate.of(2026, 10, 15), parsed.exceptions[0].movedToDate)
        assertEquals(SessionState.MOVED, parsed.exceptions[0].state)
    }
}
