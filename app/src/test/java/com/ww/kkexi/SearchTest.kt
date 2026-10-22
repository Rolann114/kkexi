package com.ww.kkexi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 日程搜索的匹配规则。
 *
 * 搜索解决的是一个具体的「不方便」：日程一多，只能靠时间轴一天天翻，
 * 而人找东西时记得的是**名字**（「上次那个交作业是哪天」），不是日期。
 */
class SearchTest {

    private fun session(
        title: String = "高等数学",
        location: String = "",
        note: String = "",
        teacher: String = "",
    ) = Session(
        id = "s",
        eventId = "e",
        date = LocalDate.of(2026, 10, 20),
        spec = TimeSpec.ByClock(10 * 60, 11 * 60),
        title = title,
        kind = EventKind.COURSE,
        location = location,
        teacher = teacher,
        note = note,
        state = SessionState.NORMAL,
        done = false,
        source = "",
        sourceText = "",
        sourceImagePath = "",
        startMinute = 10 * 60,
        endMinute = 11 * 60,
    )

    @Test
    fun matches_the_title() {
        assertTrue(session(title = "线代作业").matches("线代"))
        assertTrue(session(title = "C语言程序设计").matches("C语言"))
    }

    @Test
    fun matches_location_note_and_teacher() {
        assertTrue(session(location = "H2207").matches("2207"))
        assertTrue(session(note = "带计算器").matches("计算器"))
        assertTrue(session(teacher = "示例教师甲").matches("示例教师"))
    }

    @Test
    fun matching_is_case_insensitive() {
        // 英文课名和教室号的大小写常被打乱
        assertTrue(session(title = "C语言程序设计").matches("c语言"))
        assertTrue(session(location = "H2207").matches("h2207"))
    }

    @Test
    fun empty_query_matches_nothing() {
        // 空查询不该把所有日程都「搜」出来
        assertFalse(session().matches(""))
        assertFalse(session().matches("   "))
    }

    @Test
    fun unrelated_query_does_not_match() {
        assertFalse(session(title = "高等数学", location = "A101").matches("体育"))
    }

    @Test
    fun query_with_surrounding_spaces_still_works() {
        // 用户从别处粘贴过来的关键字常带空格
        assertTrue(session(title = "线代作业").matches("  线代  "))
    }
}
