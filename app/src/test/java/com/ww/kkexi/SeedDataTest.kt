package com.ww.kkexi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [SeedData] 的两类校验。
 *
 * ## 一、结构自洽性
 * 逐条对齐 `SeedData.kt` 的构造器约束：
 * - `course()` 必定带 recurrence，anchorDate = 该周该星期，提醒提前 15 分钟；
 * - `activity()` 必定没有 recurrence，走 [Event] 的默认值（提前 30 分钟、参与提醒、未完成）；
 * - 每条都必须有非空 source 与 title。
 *
 * ## 二、**隐私**：示例数据里不许出现任何真实个人信息
 *
 * 这一条是 2026-10-15 加的，起因很具体：这个文件原先装的是某个真实用户的整学期课表
 * （真实课程名、真实教师姓名、真实教室号）。App 要分享出去、代码要开源，
 * 那些内容就必须清掉——它们属于个人信息。
 *
 * 所以现在这里有一组**机械的隐私断言**：教师名必须是「示例教师X」这种明显的占位，
 * 教室号必须是 A101/B203 这种明显编造的号，且整个源文件里不许出现真实校名、
 * 真实人名、以及任何 `sk-` 开头的 API Key。
 */
class SeedDataTest {

    private fun seed(id: String): Event = SeedData.events.first { it.id == id }

    private val courses: List<Event> get() = SeedData.events.filter { it.kind == EventKind.COURSE }

    private val officeHours: List<Event> get() = SeedData.events.filter { it.kind == EventKind.OFFICE_HOUR }

    private fun seedSource(): String {
        // 工作目录可能是仓库根（app/src/...）也可能是模块根（src/...），两种都试。
        val candidates = listOf(
            File("app/src/main/java/com/ww/kkexi/SeedData.kt"),
            File("src/main/java/com/ww/kkexi/SeedData.kt"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到 SeedData.kt；试过 ${candidates.map { it.absolutePath }}")
        return file.readText()
    }

    // ================================================================ 一、结构

    @Test
    fun events_is_not_empty_and_covers_every_kind_in_use() {
        assertFalse(SeedData.events.isEmpty())
        assertTrue("示例课表必须有课", courses.isNotEmpty())
        assertTrue("示例里要有行程", SeedData.events.any { it.kind == EventKind.ACTIVITY })
        assertTrue("示例里要有截止型事项", SeedData.events.any { it.kind == EventKind.DEADLINE })
        assertTrue("示例里要有答疑", officeHours.isNotEmpty())
    }

    @Test
    fun every_id_is_unique_and_non_blank() {
        val ids = SeedData.events.map { it.id }
        assertEquals("id 必须唯一", ids.distinct().size, ids.size)
        assertTrue("id 不能为空", ids.none { it.isBlank() })
    }

    @Test
    fun every_first_period_exists_in_the_periods_table() {
        for (event in SeedData.events) {
            assertNotNull(
                "${event.id} 的 firstPeriod=${event.specFirstPeriodForTest} 不在作息表里",
                Timetable.DEFAULT.startMinute(event.specFirstPeriodForTest),
            )
        }
    }

    @Test
    fun every_period_range_stays_inside_the_periods_table() {
        for (event in SeedData.events) {
            assertTrue("${event.id} 的 periodCount 必须至少 1", event.specPeriodCountForTest >= 1)
            val lastPeriod = event.specFirstPeriodForTest + event.specPeriodCountForTest - 1
            assertNotNull(
                "${event.id} 的末日节次 $lastPeriod 不在作息表里",
                Timetable.DEFAULT.endMinute(lastPeriod),
            )
        }
    }

    @Test
    fun every_course_has_a_recurrence() {
        for (event in courses) {
            assertNotNull("${event.id} 是课程但没有 recurrence", event.recurrence)
        }
    }

    @Test
    fun every_activity_has_no_recurrence() {
        // 判据是「一次性」而不是「不是课程」——答疑也是按周重复的。
        val oneShot = SeedData.events.filter {
            it.kind != EventKind.COURSE && it.kind != EventKind.OFFICE_HOUR
        }
        for (event in oneShot) {
            assertNull("${event.id} 是一次性行程，不该带 recurrence", event.recurrence)
        }
        for (event in officeHours) {
            assertNotNull("${event.id} 是按周重复的答疑，必须带 recurrence", event.recurrence)
        }
    }

    @Test
    fun recurrence_weeks_are_sane() {
        for (event in SeedData.events) {
            val rule = event.recurrence ?: continue
            assertTrue("${event.id} 的 startWeek 必须 >= 1", rule.startWeek >= 1)
            assertTrue("${event.id} 的 endWeek >= startWeek", rule.endWeek >= rule.startWeek)
            assertTrue(
                "${event.id} 的 endWeek 超出第 ${Semester.totalWeeks} 周",
                rule.endWeek <= Semester.totalWeeks,
            )
            for (week in rule.weeks) {
                assertTrue("${event.id} 的显式周次 $week 必须 >= 1", week >= 1)
            }
            assertEquals("${event.id} 的显式周次必须升序录入", rule.weeks.sorted(), rule.weeks)
        }
    }

    @Test
    fun every_course_starts_at_the_first_week_or_later() {
        val starts = SeedData.events.mapNotNull { it.recurrence?.startWeek }
        assertTrue("第 3 周开课的不止一门", courses.count { it.recurrence!!.startWeek == FIRST_WEEK } > 1)
        for (event in courses) {
            assertTrue("${event.id} 早于第 $FIRST_WEEK 周", event.recurrence!!.startWeek >= FIRST_WEEK)
        }
        assertEquals(FIRST_WEEK, starts.min())
    }

    @Test
    fun course_anchor_dates_match_their_recurrence() {
        for (event in courses) {
            val rule = event.recurrence!!
            assertEquals("${event.id} 的 anchorDate 星期不对", rule.dayOfWeek, event.anchorDate.dayOfWeek)
            assertEquals(
                "${event.id} 的 anchorDate 不在 startWeek 那一周",
                rule.startWeek,
                Semester.weekOf(event.anchorDate),
            )
            assertEquals(
                "${event.id} 的 anchorDate 应等于该周该星期",
                Semester.mondayOf(rule.startWeek).plusDays((rule.dayOfWeek.value - 1).toLong()),
                event.anchorDate,
            )
        }
    }

    @Test
    fun every_event_has_a_title_and_a_source() {
        for (event in SeedData.events) {
            assertTrue("${event.id} 的 title 为空", event.title.isNotBlank())
            assertTrue("${event.id} 没有 source", event.source.isNotBlank())
        }
    }

    @Test
    fun course_reminders_fire_15_minutes_early() {
        for (event in courses) {
            assertEquals("${event.id} 的提醒提前量", 15, event.remindMinutesBefore)
        }
    }

    @Test
    fun one_shot_activities_keep_the_event_defaults() {
        val oneShot = SeedData.events.filter {
            it.kind != EventKind.COURSE && it.kind != EventKind.OFFICE_HOUR
        }
        for (event in oneShot) {
            assertEquals("${event.id} 的默认提醒提前量", 30, event.remindMinutesBefore)
            assertTrue("${event.id} 默认参与提醒", event.reminderEnabled)
            assertFalse("${event.id} 默认未完成", event.done)
        }
    }

    @Test
    fun every_anchor_date_lies_inside_the_semester() {
        val semesterFrom = Semester.mondayOf(1)
        val semesterTo = Semester.sundayOf(Semester.totalWeeks)
        for (event in SeedData.events) {
            assertTrue(
                "${event.id} 的 anchorDate ${event.anchorDate} 在教学时间之外",
                event.anchorDate >= semesterFrom && event.anchorDate <= semesterTo,
            )
        }
    }

    @Test
    fun office_hours_are_marked_as_office_hours_not_courses() {
        for (event in officeHours) {
            // 「答疑」是一种**行为**（固定时段、按时重复），不是普通课程：
            // 它不该跟课程共用同一种颜色与提醒提前量。
            assertEquals("${event.id} 不该被算成课程", EventKind.OFFICE_HOUR, event.kind)
            // 类别标签没显式设时要能回退成「答疑」，界面才不会显示成空。
            assertEquals("${event.id} 的类别标签", "答疑", event.displayCategory)
        }
    }

    @Test
    fun unverified_notes_is_not_empty() {
        assertFalse("说明文案必须存在，不许留空", SeedData.unverifiedNotes.isEmpty())
        assertTrue("每条说明都要有正文", SeedData.unverifiedNotes.none { it.isBlank() })
        assertTrue(
            "必须明确告诉用户这是虚构示例，实际 ${SeedData.unverifiedNotes}",
            SeedData.unverifiedNotes.any { it.contains("虚构") },
        )
    }

    // ================================================================ 二、隐私（关键防线）

    @Test
    fun teachers_are_obviously_placeholder_names() {
        // 教师名必须是「示例教师X」这种一眼假的名字。
        // 这样即使有人误用了示例数据，也不会有任何真人的姓名出现在 App 里。
        for (event in SeedData.events) {
            if (event.teacher.isEmpty()) continue
            assertTrue(
                "${event.id} 的教师名「${event.teacher}」看起来像真名——" +
                    "示例数据里的教师必须写成「示例教师甲」这种明显的占位",
                event.teacher.startsWith("示例教师"),
            )
        }
    }

    @Test
    fun locations_are_obviously_fake() {
        // 教室号用 A101/B203/C305 这种明显编造的编号；
        // 真实课表的教室号通常是「字母 + 四位数字」，不能让它们出现在示例里。
        val allowed = Regex("""^(A|B|C|D)\d{3}$|^机房 D1$|^活动中心 \d+$|^报告厅$|^田径场$|^教师休息室$""")
        for (event in SeedData.events) {
            if (event.location.isEmpty()) continue
            assertTrue(
                "${event.id} 的地点「${event.location}」不像占位值——" +
                    "示例数据的地点必须用 A101 这类明显编造的编号",
                allowed.matches(event.location),
            )
        }
    }

    @Test
    fun seed_source_contains_no_api_key() {
        val source = seedSource()
        val keyPattern = Regex("""sk-[A-Za-z0-9]{16,}""")
        assertFalse(
            "SeedData.kt 里出现了疑似 API Key（${keyPattern.find(source)?.value}）——" +
                "密钥绝不能进代码仓库",
            keyPattern.containsMatchIn(source),
        )
    }

    @Test
    fun seed_source_names_no_real_school_or_person() {
        // 真实校名/教师名**不写进仓库**（仓库是公开的），本地跑守卫测试时用环境变量提供：
        //   $env:KKEXI_PRIVATE_WORDS = "某某大学,某某老师"
        // 不设变量时这一项只做通用检查（下面的「字母 + 四位数字」教室号）。
        val forbidden = System.getenv("KKEXI_PRIVATE_WORDS")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        val source = seedSource()
        val hits = forbidden.filter { source.contains(it) }
        assertTrue(
            "SeedData.kt 里出现了真实个人信息：$hits —— " +
                "这个文件会编译进 APK 并随代码发布，必须全部清掉",
            hits.isEmpty(),
        )

        // 不依赖具体名单的通用检查：真实教室号通常是「字母 + 四位数字」。
        val roomLike = Regex("""[A-H]\d{4}""").findAll(source).map { it.value }.toList()
        assertTrue(
            "SeedData.kt 里出现了像真实教室号的字符串：$roomLike —— 示例数据只能用 A101 这类占位",
            roomLike.isEmpty(),
        )
    }

    @Test
    fun every_event_source_says_it_is_sample_data() {
        // 每条示例数据的 source 都要写明是虚构的，避免用户误以为是真的。
        for (event in SeedData.events) {
            assertTrue(
                "${event.id} 的 source「${event.source}」应说明这是示例数据",
                event.source.contains("示例"),
            )
        }
    }

    private companion object {
        const val FIRST_WEEK = 3
    }
}
