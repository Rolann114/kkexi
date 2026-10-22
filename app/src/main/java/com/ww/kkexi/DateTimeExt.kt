package com.ww.kkexi

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields

/** 日期与时间格式化的公共小工具，界面层与通知层共用同一套口径。 */
object DateTimeExt {

    private val monthDay = DateTimeFormatter.ofPattern("M月d日")
    private val monthDayWeek = DateTimeFormatter.ofPattern("M月d日 EEEE")

    private val weekNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    /** "10月14日" */
    fun formatDate(date: LocalDate): String = date.format(monthDay)

    /** "10月14日 周三" */
    fun formatDateWithWeek(date: LocalDate): String = date.format(monthDayWeek)

    /** 一周七天的中文简称。 */
    fun weekName(date: LocalDate): String = weekNames[date.dayOfWeek.value - 1]

    /** "10月14日 周三" —— 不带「今天/明天」前缀的纯日期。 */
    fun formatDateWithDay(date: LocalDate): String = "${formatDate(date)} ${weekName(date)}"

    /** 相对今天的口语化标签：今天 / 明天 / 后天 / 其余走 [formatDateWithWeek]。 */
    fun relativeLabel(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "今天"
        today.plusDays(1) -> "明天"
        today.plusDays(2) -> "后天"
        else -> formatDateWithWeek(date)
    }

    /** "第 6 周" —— 教学周，锚点在 [Semester.firstMonday]。 */
    fun weekLabel(date: LocalDate): String {
        val week = Semester.weekOf(date)
        return if (week in 1..Semester.totalWeeks) "第 $week 周" else "假期"
    }

    /**
     * 时间轴上每天的分组标题。
     *
     * 修过的 bug：`relativeLabel` 对「今天/明天/后天」之外返回的**已经包含星期**
     * （走 [formatDateWithWeek]，形如 `10月19日 星期一`），旧实现又拼了一次 [weekName]，
     * 于是显示成 `10月19日 星期一 · 10月19日 周一 · 第 7 周`——星期出现两遍。
     * 现在的口径：相对标签 + 日期 + 星期 + 教学周，且**近三天不重复日期**。
     */
    fun dayHeader(date: LocalDate, today: LocalDate): String {
        val head = when (date) {
            today -> "今天"
            today.plusDays(1) -> "明天"
            today.plusDays(2) -> "后天"
            else -> null
        }
        val body = "${formatDate(date)} ${weekName(date)}"
        return if (head == null) {
            "$body · ${weekLabel(date)}"
        } else {
            "$head · $body · ${weekLabel(date)}"
        }
    }

    /** 把 [Session] 的时间渲染成 "10:20-12:00"；具体时刻/全天走 [Session.timeLabel]。 */
    fun timeRange(session: Session): String = session.timeLabel

    /** ISO 周（Monday 起算）的周序号，供课表视图切换使用。 */
    fun isoWeek(date: LocalDate): Int = date.get(WeekFields.ISO.weekOfWeekBasedYear())

    /** 周视图的列序：周一=0 … 周日=6。 */
    fun columnOf(date: LocalDate): Int = date.dayOfWeek.value - 1
}
