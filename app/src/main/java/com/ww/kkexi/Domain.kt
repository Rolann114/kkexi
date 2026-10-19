package com.ww.kkexi

import java.time.DayOfWeek
import java.time.LocalDate

// 原先这里有一个写死的 `object Periods`（一份硬编码的作息表）。
// 用户反馈第 1 条之后它被删掉了：作息表现在是**可配置数据**，见 `Timetable.kt`。
// 时间格式化、节次查表、区间标签这些能力全部搬到了 `Timetable` 上，
// 避免同一份时间数据存在两处（复制必然漂移）。

/**
 * 一条日程「什么时候」。
 *
 * 为什么单独建模（用户提了两次的问题）：
 * 第一版把时间**写死成节次**（第几节起、连几节），于是新建行程时只能选节次，
 * 想录「14:30 开会」这种没有节次概念的事就很别扭。
 * 现在时间是一等公民，两种输入方式平权：
 *
 * - [ByClock]：直接填时刻。适合会议、面试、火车、任何不按课表走的事。
 * - [ByPeriod]：按节次填。适合课程，因为课程表本来就是按节次排的；
 *   它的具体时刻**不存下来**，而是显示时用当前作息表换算——这样学校调作息，全库时间自动跟着变。
 *
 * 注意 [ByPeriod] 的节次是「第几节课」，不是「几点」；[ByClock] 才是「几点」。
 */
sealed interface TimeSpec {

    val startMinute: Int
    val endMinute: Int

    /** 直接给一个区间。 */
    data class ByClock(
        override val startMinute: Int,
        override val endMinute: Int,
    ) : TimeSpec {
        init {
            require(endMinute > startMinute) { "结束时刻必须晚于开始时刻" }
        }
    }

    /**
     * 具体时刻：某一天的一个时间点，而不是一段区间。
     *
     * 用户反馈（2026-10-19）：「对时间节点的相关事情，我发现没有一个具体时间点的选项，
     * 截止日期居然是个时间段」。交作业、报名、集合这类事只需要一个「几点」，
     * 硬填成 18:00-19:00 会让人误以为要占用一小时。
     *
     * 单独建一个类型而不是复用 `ByClock(start, start)`：
     * - 起止相等的 ByClock 在课表网格里是零高度，布局要单独处理；
     * - 存库时能明确写成 `timeMode=POINT`，老数据不会被误读成「零点长的事件」。
     */
    data class ByPoint(val minute: Int) : TimeSpec {
        override val startMinute: Int get() = minute
        override val endMinute: Int get() = minute
    }

    /** 按节次给。[firstPeriod] 起、连续 [periodCount] 节。 */
    data class ByPeriod(
        val firstPeriod: Int,
        val periodCount: Int,
    ) : TimeSpec {
        // 时刻由作息表算，所以这里只是占位；真正的取值走 Timetable.byPeriod()。
        override val startMinute: Int get() = 0
        override val endMinute: Int get() = 0
    }

    companion object {
        /**
         * 「全天」：00:00–24:00。
         *
         * 作业死线、报名截止这类事本来就没有具体时刻。以前只能填一个具体时段，
         * 于是 AI 读出来常常是 `00:00–23:59`，界面显示成那个样子很难读。
         * 现在有这一档，新建时可以直接选「全天」，显示也是「全天」。
         */
        val ALL_DAY: ByClock get() = ByClock(0, 24 * 60)

        /** 用当前作息表把 [TimeSpec] 解析成「几点到几点」。 */
        fun resolve(spec: TimeSpec, timetable: Timetable): Pair<Int, Int> = when (spec) {
            is ByClock -> spec.startMinute to spec.endMinute
            is ByPoint -> spec.minute to spec.minute
            is ByPeriod -> timetable.resolvePeriods(spec.firstPeriod, spec.periodCount)
        }

        /**
         * 是不是「全天」。
         *
         * 判据取「起点在 0 点、终点在 23:59 或更晚」，这样
         * `00:00-23:59`、`00:00-24:00`、`00:00-00:00`（跨天写法）都算全天。
         */
        fun isAllDay(spec: TimeSpec, timetable: Timetable): Boolean {
            val (start, end) = resolve(spec, timetable)
            return start <= 0 && end >= 23 * 60 + 59
        }

        /** 界面上显示的时间文案。全天不显示成 00:00-23:59，而是「全天」；具体时刻只显示一个钟点。 */
        fun label(spec: TimeSpec, timetable: Timetable): String {
            if (isAllDay(spec, timetable)) return "全天"
            val (start, end) = resolve(spec, timetable)
            if (start == end) return Timetable.format(start)
            return "${Timetable.format(start)}-${Timetable.format(end)}"
        }
    }
}

/** 事件类别。颜色与图标由界面层决定，这里只留标识。 */
enum class EventKind {
    /** 课程：有周次规则的重复事件。 */
    COURSE,

    /** 普通行程：会议、活动、讲座等一次性安排。 */
    ACTIVITY,

    /** 截止型事项：作业、报名等「到点必须做完」的事。 */
    DEADLINE,

    /** 答疑/辅导等固定时段的服务。 */
    OFFICE_HOUR;

    /** 界面上显示用的默认类别名（用户没自定义时用它）。 */
    val defaultCategory: String
        get() = when (this) {
            ACTIVITY -> "行程"
            DEADLINE -> "截止"
            COURSE -> "课程"
            OFFICE_HOUR -> "答疑"
        }
}

/** 这条日程在界面上显示成什么类别：优先用户填的标签，没填就按 [EventKind] 回退。 */
val Event.displayCategory: String
    get() = category.ifBlank { kind.defaultCategory }

/** 周次奇偶。 */
enum class WeekParity {
    /** 每周都上。 */
    EVERY,
    /** 仅单周。 */
    ODD,
    /** 仅双周。 */
    EVEN,
}

/** 周次奇偶的中文说法（界面与 AI 回执共用，避免两处各写一套）。 */
val WeekParity.descZh: String
    get() = when (this) {
        WeekParity.EVERY -> "每周"
        WeekParity.ODD -> "单周"
        WeekParity.EVEN -> "双周"
    }

/**
 * 重复规则：从第 [startWeek] 周到第 [endWeek] 周的每个 [dayOfWeek]。
 *
 * 周次口径以校历第 1 周周一为锚（见 [Semester]）。[weeks] 非空时优先按显式周次列表匹配，
 * 用于「双4-6,8-10,12,14-15,17周」这种不成区间的写法。
 */
data class Recurrence(
    val dayOfWeek: DayOfWeek,
    val startWeek: Int,
    val endWeek: Int,
    val parity: WeekParity = WeekParity.EVERY,
    val weeks: List<Int> = emptyList(),
) {
    /** 判断第 [week] 周是否命中本规则。 */
    fun matchesWeek(week: Int): Boolean {
        if (weeks.isNotEmpty()) return week in weeks
        if (week < startWeek || week > endWeek) return false
        return when (parity) {
            WeekParity.EVERY -> true
            WeekParity.ODD -> week % 2 == 1
            WeekParity.EVEN -> week % 2 == 0
        }
    }
}

/**
 * 事件主规则。课程、活动、截止、答疑共用这一张表，
 * 「课程表」和「时间轴」只是它的两个投影（Q29 = 一张表）。
 */
data class Event(
    val id: String,
    val title: String,
    val kind: EventKind,

    /**
     * 用户可编辑的类别标签（「行程」「实验」「社团」……）。
     *
     * 用户反馈：「新建行程这里自定义程度太小了，比如类别无法自定义」。
     * 这里和 [kind] 分工明确：
     * - [kind] 决定**行为**（COURSE 按周重复、DEADLINE 进「未完成的截止」），由程序用；
     * - [category] 纯粹是**给人看的标签**，用户想加几个加几个。
     * 为空时由 [displayCategory] 回退到 [kind] 的默认名字，保证老数据不缺显示。
     */
    val category: String = "",
    val location: String = "",
    val note: String = "",
    val teacher: String = "",

    /** 首次发生日；[recurrence] 为空时它就是唯一发生日。 */
    val anchorDate: LocalDate,

    /**
     * 这条日程"什么时候"。见 [TimeSpec]。
     *
     * **用户反馈两次**：「新建行程要能直接填时间点，不要只能选第几节」。
     * 所以时间现在是独立的一等公民，节次只是它的一种输入方式（[TimeSpec.ByPeriod]）。
     */
    val time: TimeSpec,

    /** 非空表示这是一条重复规则。 */
    val recurrence: Recurrence? = null,

    /** 截止型事项的完成状态。 */
    val done: Boolean = false,

    /**
     * 「一手资料」：喂给 AI 的原文（粘贴的文字、或读图读出来的文本）。
     *
     * 用户反馈：「点击时间轴的安排时，有一个看我上传的一手资料的选项，
     * 比如我上传给 AI 的图片，我发给 AI 的文字」。
     * 手动创建的日程这两项都为空，界面就不显示那个入口。
     */
    val sourceText: String = "",

    /** 「一手资料」：原图路径（App 私有目录）。 */
    val sourceImagePath: String = "",

    /** 每条行程的提醒提前量（分钟）。 */
    val remindMinutesBefore: Int = 30,

    /** 是否参与提醒。 */
    val reminderEnabled: Boolean = true,

    /** 数据来源，便于回溯「这条是哪来的」。 */
    val source: String = "",
)

/** 单次发生的状态。 */
enum class SessionState {
    /** 正常发生。 */
    NORMAL,
    /** 被取消（放假、停课）。 */
    CANCELLED,
    /** 被改到别的时间地点。 */
    MOVED,
    /** 补课/加课，不属于原规则。 */
    EXTRA,
}

/**
 * 针对某一次发生的例外覆盖。
 *
 * 语义（Q8 = 主规则 + 例外覆盖）：主规则不动，只改这一天。
 * 「从本周起都改」走另一条路径——直接改主规则并保留历史（见 [Event.anchorDate] 之外的说明）。
 */
data class EventException(
    val eventId: String,

    /** 被覆盖的那一天（原规则命中的日期）。 */
    val originalDate: LocalDate,

    val state: SessionState,

    /** 改到哪一天；为空表示只取消。 */
    val movedToDate: LocalDate? = null,

    /** 改后的时间；为空表示沿用原时间。 */
    val movedTime: TimeSpec? = null,
    val movedLocation: String? = null,
    val note: String = "",
)

/**
 * 展开后的一次具体发生，界面只认这个。
 *
 * 起止时刻**在这里就解析好并存下来**（而不是每次显示时再去查作息表），
 * 好处是：作息表改了以后，只要重新展开一遍，所有历史与未来安排的时间会一起跟着变。
 *
 * [spec] 保留原始输入方式：按时刻填的（[TimeSpec.ByClock]）不显示节次，
 * 按节次填的（[TimeSpec.ByPeriod]）才显示「第几节」——见 [periodLabel]。
 */
data class Session(
    val id: String,
    val eventId: String,
    val date: LocalDate,
    val spec: TimeSpec,
    val title: String,
    val kind: EventKind,
    val location: String,
    val teacher: String,
    val note: String,
    val state: SessionState,
    val done: Boolean,
    val source: String,

    /**
     * 「一手资料」：这条日程是**从哪来的**。
     *
     * 用户反馈：「点击时间轴的安排时，有一个看我上传的一手资料的选项，
     * 比如我上传给 AI 的图片，我发给 AI 的文字」。
     *
     * [sourceText] 是喂给 AI 的原文（粘贴的文字、或读图读出来的文本）；
     * [sourceImagePath] 是原图在 App 私有目录里的路径。
     * 两者都为空表示这条是手动创建的，界面就不显示「看一手资料」这一项。
     */
    val sourceText: String,
    val sourceImagePath: String,

    val startMinute: Int,
    val endMinute: Int,
) {
    val startLabel: String get() = Timetable.format(startMinute)
    val endLabel: String get() = Timetable.format(endMinute)

    /**
     * 这条是不是「全天」。
     *
     * 为什么需要判断：作业、报名这类死线本来就没有具体时刻，模型也常说成
     * `00:00–23:59`（或整天）。直接原样显示会变成「00:00-23:59」——
     * 读起来像「半夜零点开始干到半夜」，很怪。
     *
     * 判据用「起点在 0 点、终点在 23:59 或更晚」，覆盖 `00:00-23:59`、`00:00-24:00`、
     * `00:00-00:00`（跨天写法）这几种常见表达。
     */
    val isAllDay: Boolean
        get() = startMinute <= 0 && endMinute >= 23 * 60 + 59

    /** 这条是不是「具体时刻」——起止相同，只占一个时间点。 */
    val isPoint: Boolean
        get() = startMinute == endMinute

    /** 时间显示：全天写「全天」，具体时刻只写一个钟点，其余「10:20-12:00」。 */
    val timeLabel: String
        get() = when {
            isAllDay -> "全天"
            isPoint -> startLabel
            else -> "$startLabel-$endLabel"
        }

    /**
     * 这条日程是否匹配搜索关键字。
     *
     * 只在**用户会记得的字段**里找：标题、地点、描述、教师、类别。
     * 不搜 id、不搜 source——那些是内部字段，搜出来只会让人困惑。
     *
     * 大小写不敏感（英文课名、教室号大小写常被打乱）。
     */
    fun matches(query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return false
        // Session 上没有 displayCategory（那是 Event 的扩展），
        // 这里用不上类别也不影响：标题/地点/描述/教师已经覆盖了用户会记的东西。
        return listOf(title, location, note, teacher)
            .any { it.contains(q, ignoreCase = true) }
    }

    /** 有没有可看的一手资料。 */
    val hasProvenance: Boolean get() = sourceText.isNotBlank() || sourceImagePath.isNotBlank()

    /**
     * "第3-4节"，**只对按节次录入的日程有意义**。
     *
     * 按时刻录入的（例如「14:30 开会」）返回空串，界面据此不显示节次——
     * 这正是用户要的「我要时间点，不要节次」。
     */
    val periodLabel: String
        get() = when (val s = spec) {
            is TimeSpec.ByClock -> ""
            is TimeSpec.ByPoint -> ""
            is TimeSpec.ByPeriod ->
                if (s.periodCount <= 1) "第${s.firstPeriod}节"
                else "第${s.firstPeriod}-${s.firstPeriod + s.periodCount - 1}节"
        }
}

/**
 * 学期锚点：第 1 周的周一。
 *
 * 这是一个常见学期结构的锚点值（开学 9 月上旬、教学 18 周 + 第 19 周统考），
 * 用户可以在设置里改（见 [Settings]）。**刻意不写任何具体学校的校历出处**：
 * 这个 App 是要分享出去的，预设值里不该出现具体学校信息。
 */
object Semester {

    val firstMonday: LocalDate = LocalDate.of(2026, 9, 7)

    /** 教学周总数（第 19 周为期末统考周）。 */
    const val totalWeeks: Int = 19

    /** [date] 落在第几教学周（第 1 周为 1，早于开学返回 0 或负数）。 */
    fun weekOf(date: LocalDate): Int {
        val days = java.time.temporal.ChronoUnit.DAYS.between(firstMonday, date).toInt()
        return Math.floorDiv(days, 7) + 1
    }

    /** 第 [week] 周的周一。 */
    fun mondayOf(week: Int): LocalDate = firstMonday.plusWeeks((week - 1).toLong())

    /** 第 [week] 周的周日。 */
    fun sundayOf(week: Int): LocalDate = mondayOf(week).plusDays(6)
}

/**
 * 展开引擎：把 [Event] + [EventException] 变成某段日期内的 [Session] 列表。
 *
 * 纯函数、不碰数据库、不碰 Android API —— 所以能被 JVM 单元测试直接覆盖。
 */
object Expander {

    /** 单次发生的稳定 ID：同一事件同一天永远得到同一个 ID。 */
    fun sessionId(eventId: String, date: LocalDate): String = "$eventId@$date"

    /**
     * 展开 [event] 在 [from]..[to]（含两端）之间的所有发生。
     *
     * @param exceptions 该事件的例外覆盖，键为 (eventId, 原日期)。
     * @param timetable 当前生效的作息表——起止时刻由它解析，所以改了作息表，所有安排的时间会一起变。
     */
    fun expand(
        event: Event,
        from: LocalDate,
        to: LocalDate,
        exceptions: List<EventException> = emptyList(),
        timetable: Timetable = Timetable.DEFAULT,
    ): List<Session> {
        val byDate: Map<LocalDate, EventException> = exceptions
            .filter { it.eventId == event.id }
            .associateBy { it.originalDate }

        val dates = occurrenceDates(event, from, to)
        val result = ArrayList<Session>(dates.size)

        for (date in dates) {
            when (val exception = byDate[date]) {
                null -> result += plainSession(event, date, timetable)
                else -> when (exception.state) {
                    SessionState.CANCELLED ->
                        result += plainSession(event, date, timetable).copy(state = SessionState.CANCELLED)

                    SessionState.MOVED -> {
                        val movedDate = exception.movedToDate ?: date
                        // 改后的时间：优先用例外里指定的；没指定就沿用原时间。
                        val spec = exception.movedTime ?: event.time
                        val (start, end) = TimeSpec.resolve(spec, timetable)
                        result += plainSession(event, movedDate, timetable).copy(
                            id = sessionId(event.id, movedDate),
                            spec = spec,
                            // 关键：时间变了，起止时刻必须跟着重算。
                            // 早期版本这里只改了节次而没动 startMinute/endMinute，
                            // 于是「调到第 7-8 节」的课仍显示第 3-4 节的时间——测试抓到过。
                            startMinute = start,
                            endMinute = end,
                            location = exception.movedLocation ?: event.location,
                            note = exception.note.ifEmpty { "由 ${date.monthValue}月${date.dayOfMonth}日 调整" },
                            state = SessionState.MOVED,
                        )
                    }

                    SessionState.EXTRA -> {
                        val extraDate = exception.movedToDate ?: date
                        val spec = exception.movedTime ?: event.time
                        val (start, end) = TimeSpec.resolve(spec, timetable)
                        result += plainSession(event, extraDate, timetable).copy(
                            id = sessionId(event.id, extraDate),
                            spec = spec,
                            startMinute = start,
                            endMinute = end,
                            location = exception.movedLocation ?: event.location,
                            note = exception.note.ifEmpty { "补课" },
                            state = SessionState.EXTRA,
                        )
                    }

                    // 覆盖状态写成 NORMAL 等价于「没有覆盖」；数据库里不该出现，但导入的表可能带进来。
                    SessionState.NORMAL -> result += plainSession(event, date, timetable)
                }
            }
        }
        return result
    }

    /** 一条事件在区间内的所有「原规则命中日期」，不含例外带来的额外日期。 */
    private fun occurrenceDates(event: Event, from: LocalDate, to: LocalDate): List<LocalDate> {
        val rule = event.recurrence
        if (rule == null) {
            return if (event.anchorDate in from..to) listOf(event.anchorDate) else emptyList()
        }

        val result = ArrayList<LocalDate>()
        val startWeek = maxOf(Semester.weekOf(from) - 1, 1)
        val endWeek = minOf(Semester.weekOf(to) + 1, Semester.totalWeeks)
        for (week in startWeek..endWeek) {
            if (!rule.matchesWeek(week)) continue
            val monday = Semester.mondayOf(week)
            val date = monday.plusDays((rule.dayOfWeek.value - 1).toLong())
            if (date in from..to) result += date
        }
        // 规则命中日期可能早于 anchorDate（例如中途新增一门课），按 anchorDate 截断
        return result.filter { !it.isBefore(event.anchorDate) }.sorted()
    }

    /**
     * 构造一条「按主规则正常发生」的 session。
     *
     * 起止时刻在这里就用 [timetable] 解析成分钟存下来。
     * 若节次在当前作息表里不存在（用户改了作息表、或导入的数据节次越界），
     * 退化为 0 分钟并让界面显示成「?」，**不编造时间**。
     */
    private fun plainSession(event: Event, date: LocalDate, timetable: Timetable): Session {
        val (start, end) = TimeSpec.resolve(event.time, timetable)
        return Session(
            id = sessionId(event.id, date),
            eventId = event.id,
            date = date,
            spec = event.time,
            title = event.title,
            kind = event.kind,
            location = event.location,
            teacher = event.teacher,
            note = event.note,
            state = SessionState.NORMAL,
            done = event.done,
            source = event.source,
            sourceText = event.sourceText,
            sourceImagePath = event.sourceImagePath,
            startMinute = start,
            endMinute = end,
        )
    }
}
