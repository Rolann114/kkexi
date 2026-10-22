package com.ww.kkexi

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * 作息表（第几节 = 几点到几点）的测试。
 *
 * 这个文件原来叫 `PeriodsTest`，断言的是**写死在代码里的那份作息表**。
 * 用户反馈第 1 条（「默认的每节课时间和我的大学不一样，建议可以配置」）之后，
 * 作息表变成了可配置数据（[Timetable]），所以测试也随之改成两部分：
 *
 *   一、**内置预设**的数值必须稳定（这是回归基线，不能漂移）；
 *   二、**配置能力**本身必须工作（改一节、加一节、序列化往返、非法输入被拒）。
 *
 * 固定 Locale 的原因：[Timetable.format] 用 `String.format(Locale.ROOT, ...)`，
 * 但日期文案仍受默认 Locale 影响，固定住能让断言在任何机器上一致。
 */
class TimetableTest {

    private lateinit var originalLocale: Locale

    private val cuit: Timetable get() = Timetable.PRESETS.first()

    @Before
    fun setUp() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.CHINA)
    }

    @After
    fun tearDown() {
        Locale.setDefault(originalLocale)
    }

    // ================================================================ 一、内置预设的事实核对

    @Test
    fun preset_name_is_neutral_and_does_not_name_any_school() {
        // 分享给同学/上传 GitHub 的版本里**不能出现具体学校名**——
        // 那属于个人信息。预设只描述作息形状（多少分钟一节），不点名出处。
        // 具体校名不写进仓库，本地可用 KKEXI_PRIVATE_WORDS 补充。
        val forbidden = listOf("大学", "学院", "校区") +
            System.getenv("KKEXI_PRIVATE_WORDS")
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
        for (word in forbidden) {
            assertFalse(
                "第一个预设的名字不应包含「$word」：${cuit.name}",
                cuit.name.contains(word),
            )
        }
        assertTrue("名字要能说明作息形状", cuit.name.contains("45"))
        assertEquals("默认值就是第一个预设", cuit.name, Timetable.DEFAULT.name)
    }

    @Test
    fun preset_first_period_is_0820() {
        // 官方原文：第 1 节 08:20-09:05。绝不能是 08:00。
        assertEquals(500, cuit.startMinute(1) ?: -1)
        assertEquals(545, cuit.endMinute(1) ?: -1)
        assertEquals("08:20-09:05", cuit.label(1))
        assertNotEquals("08:00-09:05", cuit.label(1))
    }

    @Test
    fun preset_second_period_is_0915_because_break_is_ten_minutes() {
        assertEquals(555, cuit.startMinute(2) ?: -1)
        assertEquals(600, cuit.endMinute(2) ?: -1)
        assertEquals("09:15-10:00", cuit.label(2))
    }

    @Test
    fun preset_afternoon_and_evening_boundaries() {
        assertEquals("10:20-11:05", cuit.label(3))
        assertEquals("11:15-12:00", cuit.label(4))
        assertEquals("14:00-14:45", cuit.label(5))
        assertEquals(1060, cuit.startMinute(9) ?: -1)
        assertEquals(1105, cuit.endMinute(9) ?: -1)
        assertEquals("17:40-18:25", cuit.label(9))
        assertEquals(1280, cuit.startMinute(12) ?: -1)
        assertEquals(1325, cuit.endMinute(12) ?: -1)
        assertEquals("21:20-22:05", cuit.label(12))
    }

    @Test
    fun preset_has_a_slot_zero_for_non_standard_blocks() {
        // 第 0 节是给「不落在标准节次内的固定时段」留的槽，例如公共课答疑 13:00-13:40。
        assertEquals(780, cuit.startMinute(0) ?: -1)
        assertEquals(820, cuit.endMinute(0) ?: -1)
        assertEquals("13:00-13:40", cuit.label(0))
    }

    @Test
    fun preset_period_length_is_45_minutes_everywhere() {
        for (slot in cuit.slots.filter { it.period >= 1 }) {
            val length = slot.endMinute - slot.startMinute
            assertEquals("第 ${slot.period} 节的长度应为 45 分钟", 45, length)
        }
    }

    @Test
    fun preset_breaks_are_uneven_which_is_why_times_are_hardcoded() {
        // 这条断言是这个项目的核心教训之一：节间休息**不等长**。
        // 第 1-2 节之间休 10 分钟；第 2-3 节之间休 20 分钟。
        // 所以绝不能用「起始 + 45 分钟 × n」推算节次时间——从第 3 节起会全部错位。
        assertEquals(10, (cuit.startMinute(2) ?: 0) - (cuit.endMinute(1) ?: 0))
        assertEquals(20, (cuit.startMinute(3) ?: 0) - (cuit.endMinute(2) ?: 0))
        assertEquals(120, (cuit.startMinute(5) ?: 0) - (cuit.endMinute(4) ?: 0))
        assertEquals(65, (cuit.startMinute(10) ?: 0) - (cuit.endMinute(9) ?: 0))
    }

    @Test
    fun preset_has_twelve_periods() {
        assertEquals(12, cuit.maxPeriod)
        assertEquals(13, cuit.slots.size) // 0..12
    }

    // ================================================================ 二、可配置能力

    @Test
    fun editing_one_period_changes_only_that_period() {
        // 用户核心诉求：每节课时间可以自己改。
        val edited = cuit.withSlot(1, 8 * 60, 8 * 60 + 45) // 08:20 -> 08:00-08:45

        assertEquals("08:00-08:45", edited.label(1))
        assertEquals(480, edited.startMinute(1) ?: -1)

        // 其余节次必须原样不动（这是「只改一节」的关键，别把整张表一起动了）。
        assertEquals("09:15-10:00", edited.label(2))
        assertEquals("21:20-22:05", edited.label(12))
        assertEquals(cuit.slots.size, edited.slots.size)
    }

    @Test
    fun editing_keeps_slots_sorted_by_period() {
        val edited = cuit.withSlot(5, 13 * 60, 13 * 60 + 45)
        assertEquals(edited.slots.map { it.period }.sorted(), edited.slots.map { it.period })
    }

    @Test
    fun edited_timetable_round_trips_through_json() {
        val edited = cuit.withSlot(3, 10 * 60 + 30, 11 * 60 + 15)
        val restored = Timetable.fromJson(edited.toJson())

        assertTrue("JSON 往返后不该为 null", restored != null)
        assertEquals(edited.name, restored!!.name)
        assertEquals("第 3 节改过的时间必须保留", "10:30-11:15", restored.label(3))
        assertEquals(edited.slots.size, restored.slots.size)
        for (slot in edited.slots) {
            assertEquals("第 ${slot.period} 节开始", slot.startMinute, restored.startMinute(slot.period))
            assertEquals("第 ${slot.period} 节结束", slot.endMinute, restored.endMinute(slot.period))
        }
    }

    @Test
    fun from_json_rejects_garbage_instead_of_inventing_a_timetable() {
        assertNull("坏 JSON 应当返回 null，而不是编一份出来", Timetable.fromJson("not json at all"))
        assertNull("缺 slots 字段应当返回 null", Timetable.fromJson("""{"name":"x"}"""))
        assertNull("slots 为空应当返回 null", Timetable.fromJson("""{"name":"x","slots":[]}"""))
        assertNull(
            "结束早于开始的槽应当被丢掉；全被丢掉后返回 null",
            Timetable.fromJson("""{"name":"x","slots":[{"period":1,"start":600,"end":500}]}"""),
        )
    }

    @Test
    fun from_json_skips_bad_slots_but_keeps_good_ones() {
        val restored = Timetable.fromJson(
            """{"name":"半好半坏","slots":[
                {"period":1,"start":480,"end":525},
                {"period":2,"start":600,"end":500}
            ]}"""
        )
        assertTrue(restored != null)
        assertEquals(1, restored!!.slots.size)
        assertEquals("08:00-08:45", restored.label(1))
    }

    @Test
    fun parse_minute_accepts_common_forms_and_rejects_junk() {
        assertEquals(500, Timetable.parseMinute("08:20") ?: -1)
        assertEquals(500, Timetable.parseMinute("8:20") ?: -1)
        assertEquals(500, Timetable.parseMinute(" 08：20 ") ?: -1) // 全角冒号 + 空格
        assertEquals(0, Timetable.parseMinute("00:00") ?: -1)
        assertEquals(1439, Timetable.parseMinute("23:59") ?: -1)

        assertNull("小时越界", Timetable.parseMinute("24:00"))
        assertNull("分钟越界", Timetable.parseMinute("08:60"))
        assertNull("不是时间", Timetable.parseMinute("八点二十"))
        assertNull("空串", Timetable.parseMinute(""))
    }

    @Test
    fun label_for_unknown_period_is_question_mark_not_a_fake_time() {
        // 宁可显示「?」，也不许编一个时间出来（AGENTS.md 硬约束 5）。
        assertEquals("?", cuit.label(13))
        assertEquals("?", cuit.label(-1))
        assertNull(cuit.startMinute(13))
        assertNull(cuit.endMinute(13))
        assertEquals("?", cuit.rangeLabel(13, 1))
        assertEquals("?", cuit.rangeLabel(12, 2))
    }

    @Test
    fun range_label_merges_consecutive_periods() {
        assertEquals("10:20-12:00", cuit.rangeLabel(3, 2))
        assertEquals("08:20-09:05", cuit.rangeLabel(1, 1))
        assertEquals("08:20-10:00", cuit.rangeLabel(1, 2))
        assertEquals("08:20-12:00", cuit.rangeLabel(1, 4))
        assertEquals("13:00-13:40", cuit.rangeLabel(0, 1))
        assertEquals("21:20-22:05", cuit.rangeLabel(12, 1))

        // periodCount <= 0 时退化成单节，不再命中第 0 节造成荒唐区间。
        assertEquals("08:20-09:05", cuit.rangeLabel(1, 0))
        assertEquals("08:20-09:05", cuit.rangeLabel(1, -3))
    }

    @Test
    fun format_is_zero_padded_and_locale_independent() {
        assertEquals("00:00", Timetable.format(0))
        assertEquals("08:20", Timetable.format(500))
        assertEquals("09:05", Timetable.format(545))
        assertEquals("14:00", Timetable.format(840))
        assertEquals("21:10", Timetable.format(1270))
        assertEquals("22:05", Timetable.format(1325))

        // 换成阿拉伯语地区也必须是 ASCII 数字（Locale.ROOT 的作用）。
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale("ar", "EG"))
            assertEquals("08:20", Timetable.format(500))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun every_preset_is_internally_consistent() {
        for (preset in Timetable.PRESETS) {
            assertTrue("预设「${preset.name}」不该为空", preset.slots.isNotEmpty())
            assertTrue("预设「${preset.name}」节次必须唯一", preset.slots.map { it.period }.distinct().size == preset.slots.size)
            for (slot in preset.slots) {
                assertTrue("预设「${preset.name}」第 ${slot.period} 节时长为正", slot.endMinute > slot.startMinute)
                assertTrue("预设「${preset.name}」第 ${slot.period} 节在一天之内", slot.endMinute <= 24 * 60)
            }
        }
    }

    @Test
    fun describe_lists_every_slot_for_the_model() {
        val text = cuit.describe()
        assertTrue("描述里要带第 1 节", text.contains("第1节"))
        assertTrue("描述里要带第 1 节的真实时间", text.contains("08:20-09:05"))
        assertEquals("每个槽都要出现一次", cuit.slots.size, Regex("第\\d+节").findAll(text).count())
    }
}
