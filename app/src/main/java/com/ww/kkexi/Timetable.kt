package com.ww.kkexi

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

/**
 * 一节的时间段。
 *
 * 用「从 0 点起的分钟数」而不是 LocalTime 存储，是为了方便做加减与排序，
 * 也便于直接序列化成 JSON 存进设置里。
 */
data class PeriodSlot(val period: Int, val startMinute: Int, val endMinute: Int) {
    init {
        require(endMinute > startMinute) { "第 $period 节的结束时间必须晚于开始时间" }
        require(startMinute in 0..1440) { "第 $period 节的开始时间越界：$startMinute" }
    }

    val startLabel: String get() = Timetable.format(startMinute)
    val endLabel: String get() = Timetable.format(endMinute)
    val label: String get() = "$startLabel-$endLabel"
}

/**
 * 作息表：第几节 = 几点到几点。
 *
 * 为什么要有这个类型（用户反馈第 1 条）：
 * 第一版把一份作息表**写死在代码里**，换个学校、或者学校调一次作息，
 * App 就全错了。现在它是一份**可配置、可保存、可导出**的数据：
 *   - 内置几个常见预设，新用户一键选；
 *   - 每节的起止时刻都能单独改；
 *   - 存进 SharedPreferences，随时能改回来。
 *
 * 这样也顺带给后面的「AI 读一张作息表图片来填」留好了落点 —— 只要生成一份 Timetable 就行。
 */
data class Timetable(
    val name: String,
    val slots: List<PeriodSlot>,
) {
    private val slotByPeriod: Map<Int, PeriodSlot> = slots.associateBy { it.period }

    /** 一门课最多能占到的节次（用于界面上的节次选择器）。 */
    val maxPeriod: Int get() = slots.maxOfOrNull { it.period } ?: 0

    fun slot(period: Int): PeriodSlot? = slotByPeriod[period]

    fun startMinute(period: Int): Int? = slotByPeriod[period]?.startMinute

    fun endMinute(period: Int): Int? = slotByPeriod[period]?.endMinute

    /** "08:20-09:05" */
    fun label(period: Int): String = slotByPeriod[period]?.label ?: "?"

    /** 连续节次的合并标签，例如 3..4 → "10:20-12:00"。 */
    fun rangeLabel(firstPeriod: Int, periodCount: Int): String {
        val start = slotByPeriod[firstPeriod]?.startMinute ?: return "?"
        val lastPeriod = if (periodCount <= 1) firstPeriod else firstPeriod + periodCount - 1
        val end = slotByPeriod[lastPeriod]?.endMinute ?: return "?"
        return "${format(start)}-${format(end)}"
    }

    /**
     * 把「第几节起、连几节」解析成「几点到几点」的分钟数对。
     *
     * 节次在当前作息表里不存在时**不编造**：退化为 (0, 0)，由界面显示成「?」。
     * （注意：这个方法名故意不叫 byPeriod——那会跟下面那个属性重名。）
     */
    fun resolvePeriods(firstPeriod: Int, periodCount: Int): Pair<Int, Int> {
        val start = slotByPeriod[firstPeriod]?.startMinute ?: return 0 to 0
        val lastPeriod = if (periodCount <= 1) firstPeriod else firstPeriod + periodCount - 1
        val end = slotByPeriod[lastPeriod]?.endMinute ?: start
        return start to end
    }

    /** 反过来：给一个时刻，找出最接近的节次（用于把用户输入的"14:30"归到第 5 节）。 */
    fun nearestPeriod(minuteOfDay: Int): Int? =
        slots.filter { it.period >= 1 }
            .minByOrNull { slot -> kotlin.math.abs(slot.startMinute - minuteOfDay) }
            ?.period

    /** 静态版本的区间标签：用默认作息表。只为兼容那些拿不到当前作息表的调用点。 */
    fun defaultRangeLabel(firstPeriod: Int, periodCount: Int): String =
        DEFAULT.rangeLabel(firstPeriod, periodCount)

    /** 只改某一节的起止时刻，其余不动。 */
    fun withSlot(period: Int, startMinute: Int, endMinute: Int): Timetable {
        val updated = slots.map { if (it.period == period) PeriodSlot(period, startMinute, endMinute) else it }
        return copy(slots = updated.sortedBy { it.period })
    }

    /** 把这份作息表渲染成给模型看的文本。 */
    fun describe(): String =
        slots.joinToString("、") { "第${it.period}节 ${it.label}" }

    fun toJson(): String = JSONObject().apply {
        put("name", name)
        put("slots", JSONArray().apply {
            for (slot in slots) {
                put(
                    JSONObject()
                        .put("period", slot.period)
                        .put("start", slot.startMinute)
                        .put("end", slot.endMinute)
                )
            }
        })
    }.toString()

    companion object {

        /** 把分钟数格式化成 "HH:mm"（显式 Locale.ROOT，避免非 ASCII 数字地区输出异常）。 */
        fun format(minuteOfDay: Int): String {
            val hour = (minuteOfDay / 60).coerceIn(0, 23)
            val minute = (minuteOfDay % 60).coerceIn(0, 59)
            return String.format(java.util.Locale.ROOT, "%02d:%02d", hour, minute)
        }

        /** "08:20" → 500。解析失败返回 null（界面据此报错，不猜）。 */
        fun parseMinute(text: String): Int? {
            val match = Regex("""^\s*(\d{1,2})\s*[:：]\s*(\d{1,2})\s*$""").find(text) ?: return null
            val hour = match.groupValues[1].toIntOrNull() ?: return null
            val minute = match.groupValues[2].toIntOrNull() ?: return null
            if (hour !in 0..23 || minute !in 0..59) return null
            return hour * 60 + minute
        }

        fun fromJson(text: String): Timetable? = try {
            val root = JSONObject(text)
            val array = root.optJSONArray("slots") ?: return null
            val slots = ArrayList<PeriodSlot>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val period = item.optInt("period", -1)
                val start = item.optInt("start", -1)
                val end = item.optInt("end", -1)
                if (period < 0 || start < 0 || end <= start) continue
                slots += PeriodSlot(period, start, end)
            }
            if (slots.isEmpty()) null else Timetable(root.optString("name", "自定义"), slots.sortedBy { it.period })
        } catch (_: Exception) {
            null
        }

        /**
         * 默认作息表：45 分钟一节，**第 2 节后休 20 分钟**、其余休 10 分钟。
         *
         * 这组时间对应国内高校最常见的一种作息（上午 08:20 开始、12 节到 22:05），
         * 所以直接作为默认值。
         *
         * **注意节间休息不等长**：第 2 节后休 20 分钟，其余休 10 分钟——
         * 所以逐节硬编码，绝不用「起始 + 45 分钟 × n」推算。
         *
         * 各校、甚至同一学校不同学期都可能不同，用户可以在设置页逐节改，
         * 或换用 [generic45] / [generic40]。
         */
        private fun defaultTimetable(): Timetable {
            fun slot(period: Int, from: String, to: String): PeriodSlot {
                val start = parseMinute(from) ?: error("预设时间写错了：$from")
                val end = parseMinute(to) ?: error("预设时间写错了：$to")
                return PeriodSlot(period, start, end)
            }
            return Timetable(
                name = "标准作息（45 分钟/节）",
                slots = listOf(
                    // 第 0 节不是教学节次，是给「不落在任何标准节次内的固定时段」留的槽，
                    // 例如 13:00-13:40 这种午间安排。用户在设置里可以改。
                    slot(0, "13:00", "13:40"),
                    slot(1, "08:20", "09:05"),
                    slot(2, "09:15", "10:00"),
                    slot(3, "10:20", "11:05"),
                    slot(4, "11:15", "12:00"),
                    slot(5, "14:00", "14:45"),
                    slot(6, "14:55", "15:40"),
                    slot(7, "15:50", "16:35"),
                    slot(8, "16:45", "17:30"),
                    slot(9, "17:40", "18:25"),
                    slot(10, "19:30", "20:15"),
                    slot(11, "20:25", "21:10"),
                    slot(12, "21:20", "22:05"),
                ),
            )
        }

        /** 常见的「整点开始、45 分钟一节」模板，别的学校多半长这样。 */
        private fun generic45(): Timetable {
            fun slot(period: Int, from: String, to: String): PeriodSlot =
                PeriodSlot(period, parseMinute(from)!!, parseMinute(to)!!)
            return Timetable(
                name = "通用（08:00 开始 · 45 分钟一节）",
                slots = listOf(
                    slot(0, "12:30", "13:10"),
                    slot(1, "08:00", "08:45"),
                    slot(2, "08:55", "09:40"),
                    slot(3, "10:00", "10:45"),
                    slot(4, "10:55", "11:40"),
                    slot(5, "14:00", "14:45"),
                    slot(6, "14:55", "15:40"),
                    slot(7, "16:00", "16:45"),
                    slot(8, "16:55", "17:40"),
                    slot(9, "19:00", "19:45"),
                    slot(10, "19:55", "20:40"),
                    slot(11, "20:50", "21:35"),
                    slot(12, "21:45", "22:30"),
                ),
            )
        }

        /** 常见高中式作息，给非大学场景也留一个。 */
        private fun generic40(): Timetable {
            fun slot(period: Int, from: String, to: String): PeriodSlot =
                PeriodSlot(period, parseMinute(from)!!, parseMinute(to)!!)
            return Timetable(
                name = "通用（08:00 开始 · 40 分钟一节）",
                slots = listOf(
                    slot(0, "12:20", "13:00"),
                    slot(1, "08:00", "08:40"),
                    slot(2, "08:50", "09:30"),
                    slot(3, "09:50", "10:30"),
                    slot(4, "10:40", "11:20"),
                    slot(5, "14:00", "14:40"),
                    slot(6, "14:50", "15:30"),
                    slot(7, "15:50", "16:30"),
                    slot(8, "16:40", "17:20"),
                    slot(9, "19:00", "19:40"),
                    slot(10, "19:50", "20:30"),
                    slot(11, "20:40", "21:20"),
                    slot(12, "21:30", "22:10"),
                ),
            )
        }

        /** 内置预设。第一个同时是**默认值**。 */
        val PRESETS: List<Timetable> = listOf(defaultTimetable(), generic45(), generic40())

        val DEFAULT: Timetable get() = PRESETS.first()

        /** 当前时间对应的分钟数，用于「现在该上什么课」。 */
        fun nowMinute(): Int {
            val now = LocalTime.now()
            return now.hour * 60 + now.minute
        }

        /** 今天是不是教学周内（供界面判断是否显示「第 N 周」）。 */
        fun inSemester(date: LocalDate): Boolean =
            Semester.weekOf(date) in 1..Semester.totalWeeks
    }
}
