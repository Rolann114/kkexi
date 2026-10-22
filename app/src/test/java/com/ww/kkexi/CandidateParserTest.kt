package com.ww.kkexi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * [CandidateParser] 的回归测试。
 *
 * 这里的每一条都对应一次**真实踩坑**，不是凭空补的覆盖率：
 *  - 日期为空字符串 → 旧代码静默丢弃整条 → 用户看到「不弹出候选」，界面一片空白；
 *  - kind 是中文「活动」 → 旧代码 `EventKind.valueOf` 抛异常；
 *  - 「依据核对」旧代码拿模型 JSON 自己当原文，等于没校。
 */
class CandidateParserTest {

    private val today = LocalDate.of(2026, 10, 14)
    private val timetable = Timetable.DEFAULT

    private fun parse(json: String, originalText: String = ""): List<Candidate> =
        CandidateParser.parse(json, originalText, today, timetable)

    // ---------- 坑一：日期为空字符串时**不能**丢条目 ----------

    @Test
    fun empty_date_does_not_drop_the_item() {
        // 实测模型对「只有日期没有时刻」的条目会返回 "date": ""
        val json = """
            {"items":[{"title":"二课社团活动","kind":"ACTIVITY","date":"",
            "firstPeriod":"","periodCount":"","location":"A101","note":"晚上7点","evidence":"10.17 晚上7点 A101"}]}
        """.trimIndent()

        val result = parse(json)

        assertEquals("空日期不能把整条丢掉", 1, result.size)
        val candidate = result[0]
        assertEquals("二课社团活动", candidate.title)
        assertEquals("A101", candidate.location)
        assertTrue(
            "必须给出「日期没读懂」的警告，而不是悄悄用别的日期",
            candidate.warnings.any { it.contains("没读懂") },
        )
    }

    @Test
    fun all_items_with_empty_dates_still_produce_candidates() {
        // 这正是用户遇到的场景：每一条都没日期，于是候选列表整个为空、界面什么都不显示。
        val json = """
            {"items":[
              {"title":"甲","kind":"ACTIVITY","date":""},
              {"title":"乙","kind":"ACTIVITY","date":""},
              {"title":"丙","kind":"ACTIVITY","date":""}
            ]}
        """.trimIndent()

        val result = parse(json)

        assertEquals("三条都不该被丢掉", 3, result.size)
    }

    // ---------- 坑二：kind 是中文 ----------

    @Test
    fun chinese_kind_is_accepted() {
        val json = """
            {"items":[{"title":"讲座","kind":"活动","date":"2026-10-16"}]}
        """.trimIndent()

        assertEquals(EventKind.ACTIVITY, parse(json)[0].kind)
    }

    @Test
    fun chinese_kind_for_deadline_and_course() {
        fun kindOf(label: String): EventKind = parse(
            """{"items":[{"title":"x","kind":"$label","date":"2026-10-16"}]}"""
        )[0].kind

        assertEquals(EventKind.DEADLINE, kindOf("截止提醒"))
        assertEquals(EventKind.DEADLINE, kindOf("作业"))
        assertEquals(EventKind.COURSE, kindOf("课程"))
        assertEquals(EventKind.OFFICE_HOUR, kindOf("答疑"))
    }

    @Test
    fun unknown_kind_falls_back_to_activity_without_throwing() {
        assertEquals(
            EventKind.ACTIVITY,
            parse("""{"items":[{"title":"x","kind":"莫名其妙的类型","date":"2026-10-16"}]}""")[0].kind,
        )
    }

    // ---------- 日期写法容错 ----------

    @Test
    fun date_accepts_iso_slash_dot_and_chinese_forms() {
        fun dateOf(raw: String): LocalDate? = CandidateParser.parseLooseDate(raw, today)

        assertEquals(LocalDate.of(2026, 10, 17), dateOf("2026-10-17"))
        assertEquals(LocalDate.of(2026, 10, 17), dateOf("2026/10/17"))
        assertEquals(LocalDate.of(2026, 10, 17), dateOf("2026.10.17"))
        assertEquals(LocalDate.of(2026, 10, 17), dateOf("10月17日"))
        // 缺年份时补 today 的年份
        assertEquals(LocalDate.of(2026, 10, 17), dateOf("10-17"))
        // 读不出来的必须是 null，由调用方给警告，而不是抛异常
        assertNull(dateOf(""))
        assertNull(dateOf("null"))
        assertNull(dateOf("下周三"))
    }

    @Test
    fun period_accepts_string_and_number_and_blank() {
        assertEquals(3, CandidateParser.parseIntLoose("3"))
        assertEquals(3, CandidateParser.parseIntLoose("3.0"))
        assertEquals(3, CandidateParser.parseIntLoose(" 3 "))
        assertNull(CandidateParser.parseIntLoose(""))
        assertNull(CandidateParser.parseIntLoose("null"))
        assertNull(CandidateParser.parseIntLoose("第五节"))
    }

    @Test
    fun missing_time_falls_back_to_a_placeholder_with_a_warning() {
        // 什么时间都没给：不能崩，要给一个 08:00 占位并明确告诉用户去改。
        val candidate = parse(
            """{"items":[{"title":"x","kind":"ACTIVITY","date":"2026-10-16","firstPeriod":""}]}"""
        )[0]

        assertEquals(8 * 60, candidate.startMinute)
        assertTrue(candidate.warnings.any { it.contains("占位") || it.contains("没有可识别的时间") })
    }

    // ---------- 时刻解析：用户要求「不要节次，要时间点」 ----------

    @Test
    fun start_and_end_are_used_directly_when_the_model_gives_clock_times() {
        val candidate = parse(
            """{"items":[{"title":"杨红樱讲座","kind":"ACTIVITY","date":"2026-10-22",
            "start":"14:30","end":"16:00","location":"国际会议厅"}]}"""
        )[0]

        // 这正是用户截图里对不上的那一条：原文写 14:30-16:00，
        // 旧代码按节次折算成了「第5-6节 14:00-15:40」，时间就错了。
        assertEquals("14:30", candidate.startLabel)
        assertEquals("16:00", candidate.endLabel)
        assertTrue(
            "直接给了时刻时不该出现「折算」这类话",
            candidate.warnings.none { it.contains("折算") },
        )
    }

    @Test
    fun clock_time_wins_over_period_when_both_are_present() {
        val candidate = parse(
            """{"items":[{"title":"x","kind":"ACTIVITY","date":"2026-10-22",
            "start":"19:00","end":"22:00","firstPeriod":10,"periodCount":2}]}"""
        )[0]

        // 用户截图里的「1024程序员活动线下初赛」就是这种：原文 19:00-22:00，
        // 旧代码把 firstPeriod=10 折算成 19:30-21:10，也对不上。
        assertEquals("19:00", candidate.startLabel)
        assertEquals("22:00", candidate.endLabel)
    }

    @Test
    fun period_only_input_is_converted_and_flagged_as_converted() {
        val candidate = parse(
            """{"items":[{"title":"x","kind":"COURSE","date":"2026-10-22",
            "firstPeriod":1,"periodCount":2}]}"""
        )[0]

        // 第 1-2 节 = 08:20-10:00（默认作息表）
        assertEquals("08:20", candidate.startLabel)
        assertEquals("10:00", candidate.endLabel)
        assertTrue(
            "折算出来的时间必须标明是折算的，让用户去核对",
            candidate.warnings.any { it.contains("折算") },
        )
    }

    @Test
    fun parse_loose_minute_handles_the_forms_the_model_actually_returns() {
        fun minute(raw: String) = CandidateParser.parseLooseMinute(raw)

        assertEquals(14 * 60 + 30, minute("14:30"))
        assertEquals(14 * 60 + 30, minute("14：30"))   // 全角冒号
        assertEquals(14 * 60 + 30, minute("1430"))     // 四位纯数字
        assertEquals(19 * 60, minute("晚上7点"))       // 中文口语
        assertEquals(14 * 60, minute("下午2点"))
        assertEquals(8 * 60 + 30, minute("上午8点半"))
        assertNull(minute(""))
        assertNull(minute("null"))
        assertNull(minute("待定"))
    }

    @Test
    fun missing_end_time_gets_one_hour_and_a_warning() {
        val candidate = parse(
            """{"items":[{"title":"x","kind":"ACTIVITY","date":"2026-10-16","start":"14:30"}]}"""
        )[0]

        assertEquals("14:30", candidate.startLabel)
        assertEquals("15:30", candidate.endLabel)
        assertTrue(candidate.endIsGuessed)
        assertTrue(candidate.warnings.any { it.contains("1 小时") })
    }

    @Test
    fun end_before_start_is_flagged() {
        val candidate = parse(
            """{"items":[{"title":"x","kind":"ACTIVITY","date":"2026-10-16",
            "start":"16:00","end":"15:00"}]}"""
        )[0]

        assertTrue(candidate.warnings.any { it.contains("不晚于") })
    }

    // ---------- 坑三：依据核对必须拿**用户原文**，不能拿模型自己的 JSON ----------

    @Test
    fun evidence_found_in_source_text_is_not_flagged() {
        val source = "10.17 晚上7点 A101（二课社团）"
        val json = """
            {"items":[{"title":"二课社团活动","kind":"ACTIVITY","date":"2026-10-17",
            "evidence":"10.17 晚上7点 A101"}]}
        """.trimIndent()

        val candidate = parse(json, source)[0]

        assertTrue(
            "依据确实在原文里，不该报「找不到」",
            candidate.warnings.none { it.contains("找不到") },
        )
    }

    @Test
    fun fabricated_evidence_is_flagged() {
        val source = "明天下午开会"
        val json = """
            {"items":[{"title":"编造的讲座","kind":"ACTIVITY","date":"2026-10-16",
            "evidence":"10月16日上午9:30学术报告厅"}]}
        """.trimIndent()

        val candidate = parse(json, source)[0]

        assertTrue(
            "依据不在原文里，必须标出来",
            candidate.warnings.any { it.contains("找不到") || it.contains("编造") },
        )
    }

    @Test
    fun empty_source_text_reports_cannot_verify_instead_of_pretending() {
        // 读图模式：没有可比对的原文，要如实说"无法机器核对"，不能假装校过了。
        val json = """{"items":[{"title":"x","kind":"ACTIVITY","date":"2026-10-16"}]}"""

        val candidate = parse(json, originalText = "")[0]

        assertTrue(candidate.warnings.any { it.contains("无法机器核对") })
    }

    // ---------- 边界 ----------

    @Test
    fun bare_array_is_accepted_via_candidatesFromJson_style_wrapping() {
        val json = """[{"title":"甲","kind":"ACTIVITY","date":"2026-10-16"}]"""
        val wrapped = if (json.trim().startsWith("[")) """{"items":$json}""" else json

        assertEquals(1, parse(wrapped).size)
    }

    @Test
    fun past_date_gets_a_warning() {
        val candidate = parse(
            """{"items":[{"title":"x","kind":"ACTIVITY","date":"2026-10-01"}]}"""
        )[0]

        assertTrue(candidate.warnings.any { it.contains("已经过去") })
    }

    @Test
    fun far_future_date_gets_a_warning() {
        val candidate = parse(
            """{"items":[{"title":"x","kind":"ACTIVITY","date":"2027-06-01"}]}"""
        )[0]

        assertTrue(candidate.warnings.any { it.contains("90 天") })
    }

    @Test
    fun missing_title_falls_back_to_a_placeholder() {
        val candidate = parse(
            """{"items":[{"title":"","kind":"ACTIVITY","date":"2026-10-16"}]}"""
        )[0]

        assertEquals("（未命名）", candidate.title)
    }

    @Test
    fun absurd_period_count_is_clamped_to_something_within_the_day() {
        // 模型偶尔会给出离谱的连堂数；折算结果必须还落在一天之内，不能跑到第二天去。
        val candidate = parse(
            """{"items":[{"title":"x","kind":"ACTIVITY","date":"2026-10-16","firstPeriod":3,"periodCount":99}]}"""
        )[0]

        assertTrue("开始时刻要在当天", candidate.startMinute in 0 until 24 * 60)
        assertTrue("结束时刻要在当天之内或正好是 24:00", candidate.endMinute in 1..24 * 60)
        assertTrue("结束不能早于开始", candidate.endMinute >= candidate.startMinute)
    }

    // ---------- 整学期课程：用户反馈「只会一节一节设置」 ----------

    @Test
    fun course_with_week_range_becomes_a_recurring_rule() {
        val candidate = parse(
            """{"items":[{"title":"线性代数","kind":"COURSE","date":"2026-10-19",
            "start":"10:20","end":"12:00","weeks":"5-16","parity":"EVERY"}]}"""
        )[0]

        val rule = candidate.recurrence
        assertNotNull("有 weeks 的课程必须变成重复规则", rule)
        assertEquals(5, rule!!.startWeek)
        assertEquals(16, rule.endWeek)
        assertEquals(java.time.DayOfWeek.MONDAY, rule.dayOfWeek)
        assertEquals(WeekParity.EVERY, rule.parity)
        assertNull("区间写法不需要展开成 weeks 列表", rule.weeks.ifEmpty { null })
    }

    @Test
    fun irregular_week_list_is_kept_as_explicit_weeks() {
        val candidate = parse(
            """{"items":[{"title":"实验","kind":"COURSE","date":"2026-10-20",
            "start":"14:00","end":"15:40","weeks":"5,7,9"}]}"""
        )[0]

        val rule = candidate.recurrence!!
        assertEquals(listOf(5, 7, 9), rule.weeks)
        assertEquals(5, rule.startWeek)
        assertEquals(9, rule.endWeek)
    }

    @Test
    fun odd_even_parity_in_chinese_is_understood() {
        val candidate = parse(
            """{"items":[{"title":"体育","kind":"COURSE","date":"2026-10-21",
            "start":"08:20","end":"09:05","weeks":"3-15","parity":"单周"}]}"""
        )[0]

        assertEquals(WeekParity.ODD, candidate.recurrence!!.parity)
    }

    @Test
    fun deadline_with_only_a_clock_is_a_point_not_a_range() {
        // 用户原话：「截止日期居然是个时间段」——「23:59 前交」就该只有一个时刻。
        val candidate = parse(
            """{"items":[{"title":"交作业","kind":"DEADLINE","date":"2026-10-21","start":"23:59"}]}"""
        )[0]

        assertTrue(candidate.isPoint)
        assertEquals(candidate.startMinute, candidate.endMinute)
        assertEquals("23:59", candidate.timeText)
        assertFalse("不能给它编一个 1 小时的结束时间", candidate.endIsGuessed)
    }

    @Test
    fun explicit_point_flag_is_honored() {
        val candidate = parse(
            """{"items":[{"title":"集合","kind":"ACTIVITY","date":"2026-10-22",
            "start":"18:00","point":true}]}"""
        )[0]

        assertTrue(candidate.isPoint)
        assertEquals("18:00", candidate.timeText)
    }

    @Test
    fun period_mentions_are_stripped_from_the_note() {
        // 模型爱写「由第 5-6 节折算」；这一句会直接显示在核对卡上，必须清掉。
        val candidate = parse(
            """{"items":[{"title":"高等数学","kind":"COURSE","date":"2026-10-19",
            "start":"15:50","end":"17:30","weeks":"3-16",
            "note":"教师：示例教师丁；周次：3-16周；由第7-8节折算"}]}"""
        )[0]

        assertFalse(candidate.note.contains("节"))
        assertTrue(candidate.note.contains("教师：示例教师丁"))
    }

    @Test
    fun recurrence_from_text_parses_a_range_for_the_chat_tool() {
        val rule = CandidateParser.recurrenceFromText("5-16", "ODD", LocalDate.of(2026, 10, 19))
        assertNotNull(rule)
        assertEquals(5, rule!!.startWeek)
        assertEquals(16, rule.endWeek)
        assertEquals(WeekParity.ODD, rule.parity)
    }
}
