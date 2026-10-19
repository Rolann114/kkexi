package com.ww.kkexi

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 演示用的示例数据。
 *
 * ## 这里**全部是虚构内容**，不含任何真实个人信息
 *
 * 这份数据会编译进 APK，拿到 APK 的人点「导入示例课表」就会看到它。
 * 所以课程名、教师名、教室号、活动名都必须是编的——不能出现任何真人的课表。
 *
 * > 历史教训：这个文件里曾经装过某个真实用户的整学期课表（真实的课程名、
 * > 真实的教师姓名、真实的教室与答疑安排），因为最初它是「把用户自己的数据灌进去」
 * > 用的。要分享/开源时，那些内容必须清掉——那属于个人信息。
 * > 保留的只有**作息表的形状**（45 分钟一节、第 2 节后休 20 分钟这种通用结构），
 * > 那个不是个人信息，也不含任何人的姓名。
 *
 * 演示数据的用处只有一个：让第一次装 App 的人一眼看懂「这个 App 是干什么的」。
 * 默认**不导入**（见 [Settings.seedSampleData]），空库时时间轴会显示引导页。
 */
object SeedData {

    /** 演示课表假设从第 3 周开始行课。 */
    private const val FIRST_WEEK = 3

    private fun weekDay(week: Int, day: DayOfWeek): LocalDate =
        Semester.mondayOf(week).plusDays((day.value - 1).toLong())

    private fun course(
        id: String,
        title: String,
        teacher: String,
        day: DayOfWeek,
        periods: Pair<Int, Int>,
        location: String,
        startWeek: Int = FIRST_WEEK,
        endWeek: Int,
        parity: WeekParity = WeekParity.EVERY,
        weeks: List<Int> = emptyList(),
        kind: EventKind = EventKind.COURSE,
    ): Event {
        val (first, count) = periods
        return Event(
            id = id,
            title = title,
            kind = kind,
            // 类别标签跟随行为类型：COURSE → 「课程」，OFFICE_HOUR → 「答疑」。
            // 曾经这里写死成 "课程"，于是答疑的标签也成了「课程」——
            // 行为类型与显示标签对不上，测试才发现。
            category = kind.defaultCategory,
            location = location,
            teacher = teacher,
            anchorDate = weekDay(startWeek, day),
            time = TimeSpec.ByPeriod(first, count),
            recurrence = Recurrence(
                dayOfWeek = day,
                startWeek = startWeek,
                endWeek = endWeek,
                parity = parity,
                weeks = weeks,
            ),
            remindMinutesBefore = 15,
            source = "示例数据（虚构）",
        )
    }

    private fun activity(
        id: String,
        title: String,
        date: LocalDate,
        periods: Pair<Int, Int>,
        location: String = "",
        note: String = "",
        kind: EventKind = EventKind.ACTIVITY,
        category: String = "",
    ): Event {
        val (first, count) = periods
        return Event(
            id = id,
            title = title,
            kind = kind,
            category = category.ifEmpty { kind.defaultCategory },
            location = location,
            note = note,
            anchorDate = date,
            time = TimeSpec.ByPeriod(first, count),
            source = "示例数据（虚构）",
        )
    }

    /** 全部示例事件。课程名、教师、教室都是编的。 */
    val events: List<Event> = listOf(
        // ---------- 课程 ----------
        course("d-math-mon", "高等数学", "示例教师甲", DayOfWeek.MONDAY, 1 to 2, "A101", endWeek = 16),
        course("d-english-mon", "大学英语", "示例教师乙", DayOfWeek.MONDAY, 5 to 2, "B203", endWeek = 16),
        course("d-program-tue", "程序设计基础", "示例教师丙", DayOfWeek.TUESDAY, 3 to 2, "C305", endWeek = 16),
        course("d-physics-wed", "大学物理", "示例教师丁", DayOfWeek.WEDNESDAY, 1 to 2, "A102", endWeek = 16),
        course(
            "d-english-wed-odd", "大学英语（单周）", "示例教师乙", DayOfWeek.WEDNESDAY, 3 to 2, "B203",
            startWeek = 3, endWeek = 15, parity = WeekParity.ODD,
        ),
        course(
            "d-english-wed-even", "大学英语（双周）", "示例教师乙", DayOfWeek.WEDNESDAY, 3 to 2, "B203",
            // 双周课从第 4 周开始，这样 parity=EVEN 与 anchorDate 是自洽的（第 4 周是双周）。
            // 曾经的写法是 startWeek=3 + parity=EVEN，那两条互相矛盾——示例数据不该留这种坑。
            startWeek = 4, endWeek = 16, parity = WeekParity.EVEN,
        ),
        course("d-lab-thu", "程序设计实验", "示例教师丙", DayOfWeek.THURSDAY, 7 to 2, "机房 D1", endWeek = 12),
        course("d-history-fri", "中国近现代史纲要", "示例教师戊", DayOfWeek.FRIDAY, 1 to 2, "B101", endWeek = 16),
        course(
            "d-officehour-mon", "课程答疑", "", DayOfWeek.MONDAY, 0 to 1, "教师休息室",
            startWeek = 6, endWeek = 16, kind = EventKind.OFFICE_HOUR,
        ),
        // ---------- 行程 ----------
        activity(
            "d-club", "社团例会", weekDay(6, DayOfWeek.THURSDAY), 9 to 2, "活动中心 201",
            note = "每周一次的例行会议，带上上次的会议记录。",
        ),
        activity(
            "d-lecture", "学术讲座：人工智能前沿", weekDay(7, DayOfWeek.WEDNESDAY), 7 to 2, "报告厅",
            note = "需提前 15 分钟入场签到。",
        ),
        activity(
            "d-exam", "高等数学期中测验", weekDay(9, DayOfWeek.MONDAY), 3 to 2, "A101",
            note = "闭卷，可带计算器。",
            kind = EventKind.DEADLINE,
        ),
        activity(
            "d-sports", "体测", weekDay(8, DayOfWeek.FRIDAY), 5 to 2, "田径场",
            note = "穿运动鞋，提前热身。",
        ),
        activity(
            "d-homework", "交程序设计作业", weekDay(6, DayOfWeek.FRIDAY), 10 to 1, "",
            note = "截止到当天 23:59，交到课程平台。",
            kind = EventKind.DEADLINE,
        ),
    )

    /**
     * 示例数据里**故意留的未确认项**——演示用，不必当真。
     * 保留这个列表是为了让「导入示例」的界面能说清楚它是什么。
     */
    val unverifiedNotes: List<String> = listOf(
        "这是一份虚构的示例课表，用来演示界面，不是任何人的真实课程。",
        "要开始记自己的安排，请先清空数据（设置 → 数据），或逐条删除。",
    )
}
