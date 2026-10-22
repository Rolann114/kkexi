package com.ww.kkexi

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * 解析出来的一条候选行程，与 [Event] 一一对应，外加原文片段用于人工核对。
 *
 * ## 为什么时间是分钟数而不是「第几节」（用户第二次投诉后改的）
 * 原先候选只带 `firstPeriod/periodCount`，界面上就必然显示「第 N 节」，
 * 而节次要靠作息表换算；一旦模型给的节次对不上（实测给过 `-1`，也把
 * 「晚上 7 点」硬塞成第 10 节 19:30），用户看到的时间就是**错的**。
 * 用户原话：「杨红樱讲座的时间、1024 程序员线下活动初赛的时间都对不上」。
 *
 * 现在候选直接带 [startMinute]/[endMinute]（绝对分钟数），时刻是什么就显示什么，
 * 中间不再有换算环节，也就没有换算错误。**只在解析时折算一次**：
 * 模型给节次时用当前作息表折成时刻，折出来的结果会显示给用户核对。
 */
data class Candidate(
    val title: String,
    val kind: EventKind,
    val date: LocalDate,
    val startMinute: Int,
    val endMinute: Int,
    val location: String,
    val note: String,
    val evidence: String,
    val warnings: List<String>,
    /**
     * 原文里**是否真的写了时间**。
     *
     * 和 [endIsGuessed] 的区别：那个用「时长是不是 60 分钟」去猜，会把真的一小时活动
     * 也标成「估的」；这个由解析器显式给出，是事实。
     *
     * 用途：截止型事项如果原文没写清时刻，转成日程时应该落成「全天」，
     * 而不是塞一个 08:00 或 00:00 的假时段——后者会变成一条
     * `00:00–15:45` 这样的怪数据，在课表里铺满半列（真机上见过）。
     */
    val timeWasExplicit: Boolean = true,

    /**
     * 整学期课程的重复规则；为空表示一次性事件。
     *
     * 用户反馈（2026-10-19）：「他不能设置一学期的课程，只会一节一节设置」。
     * 课表截图里本来就有「周次：5-16 周」，模型也读出来了，但它只写在描述里，
     * 没有变成真正的重复规则——于是 18 条课变成 18 个单次事件，要手动补 15 周。
     */
    val recurrence: Recurrence? = null,

    /** 这条只要一个时间点（例如「23:59 前交」），不是一个时段。 */
    val isPoint: Boolean = false,
) {
    val startLabel: String get() = Timetable.format(startMinute)
    val endLabel: String get() = Timetable.format(endMinute)

    /** 界面上的时间文案：具体时刻只显示一个钟点。 */
    val timeText: String get() = if (isPoint) startLabel else "$startLabel-$endLabel"

    /** 模型没说结束时间时，我们按 1 小时兜底——界面据此提示「结束时间是估的」。 */
    val endIsGuessed: Boolean get() = endMinute - startMinute == DEFAULT_DURATION_MINUTES

    /** 这条转成日程时该用「全天」吗。 */
    val shouldBeAllDay: Boolean
        get() = kind == EventKind.DEADLINE && !timeWasExplicit

    companion object {
        /** 模型没给结束时间时的默认时长。 */
        const val DEFAULT_DURATION_MINUTES = 60
    }
}

/**
 * 把模型吐出的 JSON 解析成**候选日程**。
 *
 * ## 为什么单独抽一个文件
 * 这里的容错逻辑踩过两个真实的坑，必须有回归测试盯着。放在 `LlmClient` 私有方法里测不到，
 * 所以抽成独立的纯函数对象——它不碰网络、不碰数据库，只做字符串到数据的转换。
 *
 * ## 踩过的坑一：日期解析失败就静默丢弃（用户反馈：「不弹出候选」，界面一片空白）
 * 旧代码是 `LocalDate.parse(item.text("date"))`，抛异常就 `continue` **整条丢掉**。
 * 实测模型对「只有日期没有时刻」的条目会返回 `"date": ""`，于是**每一条都被丢掉**、
 * 候选列表为空、界面什么都不显示，而且**没有任何报错**——最糟的失败方式。
 * 现在：能救的救回来（多种写法），救不回来的也**作为一条带警告的候选显示出来**。
 *
 * ## 踩过的坑二：`kind` 用中文（实测 `"kind":"活动"`）
 * 旧代码 `EventKind.valueOf("活动")` 直接抛异常。现在中英文都认。
 */
object CandidateParser {

    /**
     * @param content 模型返回的 JSON（可以是 `{"items":[...]}`，也可以是裸数组）
     * @param originalText 用户给的**原文**，用于核对 evidence 是否真的出现过。
     *        读图场景传空串——那时没有可比对的文本，会如实标注「无法机器核对」。
     */
    fun parse(
        content: String,
        originalText: String,
        today: LocalDate,
        timetable: Timetable,
    ): List<Candidate> {
        val json = extractJson(content)
        val items = json.optJSONArray("items") ?: JSONArray()
        val result = ArrayList<Candidate>()

        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val warnings = ArrayList<String>()

            val rawDate = firstNonEmpty(
                item.text("date"),
                item.text("startDate"),
                item.text("start_date"),
            )
            val date = parseLooseDate(rawDate, today)
            if (date == null) {
                warnings += "日期「$rawDate」没读懂，需要你手动选一天"
            }
            val effectiveDate = date ?: today

            if (date != null) {
                if (date.isBefore(today)) {
                    warnings += "这个日期已经过去了，请确认模型是不是算错了年份"
                } else if (date.isAfter(today.plusDays(90))) {
                    warnings += "这个日期在 90 天以外，超出常见排课范围，请重点核对"
                }
            }

            // 时间：三种给法都要认，**优先用具体时刻**。
            //
            // 模型有时给 start/end（"14:30"），有时给节次（firstPeriod=5）。
            // 给了时刻就直接用——那是原文里的真实信息，折算只会引入误差。
            // 只给节次时才查作息表折算，并**明确标注这是折算出来的**。
            val rawStart = firstNonEmpty(
                item.text("start"),
                item.text("startTime"),
                item.text("start_time"),
            )
            val rawEnd = firstNonEmpty(
                item.text("end"),
                item.text("endTime"),
                item.text("end_time"),
            )
            val clockStart = parseLooseMinute(rawStart)
            val clockEnd = parseLooseMinute(rawEnd)

            val firstPeriod = parseIntLoose(
                firstNonEmpty(
                    item.text("firstPeriod"),
                    item.text("first_period"),
                )
            )
            val periodCount = parseIntLoose(
                firstNonEmpty(
                    item.text("periodCount"),
                    item.text("period_count"),
                )
            )

            val kind = parseLooseKind(item.text("kind"))
            val explicitPoint = item.optBoolean("point", false)
            var isPoint = false

            val startMinute: Int
            val endMinute: Int
            when {
                // 有明确时刻：最好，直接用
                clockStart != null -> {
                    startMinute = clockStart
                    when {
                        clockEnd != null && clockEnd > clockStart -> endMinute = clockEnd
                        // 截止型 / 模型显式说「只是一个时刻」：不要再编一个结束时间。
                        // 用户反馈：「截止日期居然是个时间段」——「23:59 前交」就该只有 23:59。
                        kind == EventKind.DEADLINE || explicitPoint -> {
                            endMinute = clockStart
                            isPoint = true
                        }
                        clockEnd != null -> {
                            endMinute = clockStart + Candidate.DEFAULT_DURATION_MINUTES
                            warnings += "结束时间「$rawEnd」不晚于开始时间，请核对"
                        }
                        else -> {
                            endMinute = clockStart + Candidate.DEFAULT_DURATION_MINUTES
                            warnings += "原文没写结束时间，暂按 1 小时算，请核对"
                        }
                    }
                }

                // 只有节次：查表折算，并如实说明这是折算
                firstPeriod != null -> {
                    val resolved = timetable.resolvePeriods(firstPeriod, periodCount ?: 1)
                    if (resolved.first == 0 && resolved.second == 0) {
                        startMinute = 8 * 60
                        endMinute = startMinute + Candidate.DEFAULT_DURATION_MINUTES
                        warnings += "原文写的节次不在当前作息表里，时间已按 08:00 兜底，请手动改"
                    } else {
                        startMinute = resolved.first
                        endMinute = resolved.second
                        // 注意：这里不写「第 N 节」。用户明确要求界面上不出现节次，
                        // 折算过程只留「按作息表折算」这一句可核对的话。
                        warnings += "时间按作息表折算，请核对"
                    }
                }

                // 什么都没给：落在 08:00，让用户自己填
                else -> {
                    startMinute = 8 * 60
                    endMinute = startMinute + Candidate.DEFAULT_DURATION_MINUTES
                    warnings += "原文里没有可识别的时间，已按 08:00 占位，请手动改"
                }
            }

            val recurrence = if (kind == EventKind.COURSE) {
                parseRecurrence(item, effectiveDate, warnings)
            } else {
                null
            }

            val evidence = item.text("evidence")
            if (originalText.isEmpty()) {
                warnings += "来自图片，无法机器核对，请对着原截图确认日期与时间"
            } else {
                if (evidence.isEmpty()) warnings += "模型没有给出原文依据，请重点核对"
                if (evidence.isNotEmpty() && !originalText.contains(evidence)) {
                    warnings += "模型给出的依据在原文中找不到，可能是编造的"
                }
            }

            result += Candidate(
                title = item.text("title").ifEmpty { "（未命名）" },
                kind = kind,
                date = effectiveDate,
                startMinute = startMinute.coerceIn(0, 24 * 60 - 1),
                endMinute = endMinute.coerceIn(0, 24 * 60),
                location = item.text("location"),
                // 描述里如果模型写了「由第 5-6 节折算」，界面上会违反「不出现节次」的硬要求。
                // 解析阶段统一删掉这类夹杂，描述只留真正的内容。
                note = stripPeriodMentions(item.text("note")),
                evidence = evidence,
                warnings = warnings,
                // 只有「模型直接给了钟点」才算原文写了时间；
                // 靠节次折算、或干脆没给，都不算（那时开始时刻是占位值）。
                timeWasExplicit = clockStart != null,
                recurrence = recurrence,
                isPoint = isPoint,
            )
        }
        return result
    }

    /**
     * 从候选 JSON 里读「周次」并拼成 [Recurrence]。
     *
     * 支持模型可能给出的几种写法：
     * - `"startWeek":5,"endWeek":16`
     * - `"weeks":"5-16"` / `"5~16"` / `"第5-16周"`
     * - `"weeks":"5,7,9"`（不连续的周）
     * - `"parity":"ODD"` / `"单周"` / `"双周"`
     *
     * 读不出来返回 null（按一次性事件处理），**不编造周次**。
     */
    private fun parseRecurrence(
        item: JSONObject,
        date: LocalDate,
        warnings: MutableList<String>,
    ): Recurrence? {
        val rawStart = parseIntLoose(firstNonEmpty(item.text("startWeek"), item.text("start_week")))
        val rawEnd = parseIntLoose(firstNonEmpty(item.text("endWeek"), item.text("end_week")))
        val rawWeeks = firstNonEmpty(
            item.text("weeks"),
            item.text("weekRange"),
            item.text("week_range"),
        )
        val parity = parseParity(
            firstNonEmpty(item.text("parity"), item.text("weekParity"), item.text("week_parity"))
        )

        // ① 明确的起止周：最可靠
        if (rawStart != null && rawEnd != null) {
            return makeRule(date, rawStart, rawEnd, parity, emptyList(), warnings)
        }

        // ② 「5-16」「第5至16周」这类区间写法
        val range = Regex("""(\d{1,2})\s*[-~至到—－]\s*(\d{1,2})""").find(rawWeeks)
        if (range != null) {
            val start = range.groupValues[1].toIntOrNull() ?: return null
            val end = range.groupValues[2].toIntOrNull() ?: return null
            return makeRule(date, start, end, parity, emptyList(), warnings)
        }

        // ③ 「5,7,9」这种不连续周次
        val numbers = Regex("""\d{1,2}""").findAll(rawWeeks)
            .mapNotNull { it.value.toIntOrNull() }
            .toList()
        if (numbers.isNotEmpty()) {
            return makeRule(
                date = date,
                start = numbers.min(),
                end = numbers.max(),
                parity = parity,
                weeks = numbers.distinct().sorted(),
                warnings = warnings,
            )
        }
        return null
    }

    private fun makeRule(
        date: LocalDate,
        start: Int,
        end: Int,
        parity: WeekParity,
        weeks: List<Int>,
        warnings: MutableList<String>,
    ): Recurrence? {
        if (start < 1 || end < 1 || end < start) {
            warnings += "周次「$start-$end」看起来不对，已按单次事件处理，请核对"
            return null
        }
        if (end > Semester.totalWeeks) {
            warnings += "周次到第 $end 周，超出本学期 ${Semester.totalWeeks} 周，请核对"
        }
        return Recurrence(
            dayOfWeek = date.dayOfWeek,
            startWeek = start,
            endWeek = end,
            parity = parity,
            weeks = weeks,
        )
    }

    private fun parseParity(raw: String): WeekParity {
        val text = raw.uppercase()
        return when {
            raw.contains("单") || text.contains("ODD") -> WeekParity.ODD
            raw.contains("双") || text.contains("EVEN") -> WeekParity.EVEN
            else -> WeekParity.EVERY
        }
    }

    /**
     * 删掉描述里的「由第 N 节折算」这类夹杂。
     *
     * 用户三次要求界面上不出现「第几节」；描述是直接显示的字段，
     * 模型又特别爱在这里补一句「由第 5-6 节折算」。与其指望模型每次都听话，
     * 不如在解析这一层统一清掉。
     */
    internal fun stripPeriodMentions(text: String): String {
        if (text.isBlank()) return ""
        var result = text
        result = result.replace(
            Regex("""[（(]?\s*由\s*第\s*\d{1,2}(?:\s*[-~至到—－]\s*\d{1,2})?\s*节\s*折算\s*[）)]?"""),
            "",
        )
        result = result.replace(
            Regex("""[（(]?\s*第\s*\d{1,2}(?:\s*[-~至到—－]\s*\d{1,2})?\s*节\s*[）)]?"""),
            "",
        )
        return result.replace(Regex("""\s{2,}"""), " ").trim()
    }

    /**
     * 从「5-16」「5,7,9」「第5至16周」这类文本拼出重复规则。
     *
     * 供聊天里的 `create_event` 工具复用：模型给 weeks 字符串即可建整学期课程，
     * 不用再走「先建一节、再手动补周次」的老路。
     */
    fun recurrenceFromText(rawWeeks: String, rawParity: String, date: LocalDate): Recurrence? {
        val parity = parseParity(rawParity)
        val range = Regex("""(\d{1,2})\s*[-~至到—－]\s*(\d{1,2})""").find(rawWeeks)
        if (range != null) {
            val start = range.groupValues[1].toIntOrNull() ?: return null
            val end = range.groupValues[2].toIntOrNull() ?: return null
            if (start < 1 || end < start) return null
            return Recurrence(date.dayOfWeek, start, end, parity)
        }
        val numbers = Regex("""\d{1,2}""").findAll(rawWeeks)
            .mapNotNull { it.value.toIntOrNull() }
            .toList()
        if (numbers.isEmpty()) return null
        return Recurrence(
            dayOfWeek = date.dayOfWeek,
            startWeek = numbers.min(),
            endWeek = numbers.max(),
            parity = parity,
            weeks = numbers.distinct().sorted(),
        )
    }

    private fun extractJson(content: String): JSONObject {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start < 0 || end <= start) {
            throw IllegalArgumentException("模型没有返回 JSON：$content")
        }
        return JSONObject(content.substring(start, end + 1))
    }

    internal fun firstNonEmpty(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() && it != "null" }?.trim() ?: ""

    /** `"3"`、`"3.0"`、`""`、`"null"` 都能处理；读不出来返回 null。 */
    internal fun parseIntLoose(raw: String): Int? =
        raw.trim().substringBefore('.').trim().toIntOrNull()

    /**
     * 宽松时刻解析：把 `"14:30"`、`"1430"`、`"14：30"`（全角冒号）、
     * `"下午2点半"` 这类写法都尽量读成分钟数；读不出来返回 null。
     *
     * 之所以要容错：模型在中文语料上经常把「晚上 7 点」直接写成 `19:00`，
     * 但也见过 `"7点"`、`"19：00"`。这些都不该让整条候选变成占位时间。
     */
    internal fun parseLooseMinute(raw: String): Int? {
        val text = raw.trim()
        if (text.isEmpty() || text == "null") return null

        // 全角冒号与点号统一成半角
        val normalized = text.replace('：', ':').replace('．', '.').replace('。', '.')

        // 先试 Timetable 的标准解析（HH:mm）
        Timetable.parseMinute(normalized)?.let { return it }

        // "1430" 这种四位纯数字
        if (normalized.length == 4 && normalized.all { it.isDigit() }) {
            val hour = normalized.take(2).toInt()
            val minute = normalized.takeLast(2).toInt()
            if (hour in 0..23 && minute in 0..59) return hour * 60 + minute
        }

        // "7点" / "晚上7点" / "下午2点半"
        val half = normalized.contains("半")
        val digits = Regex("""(\d{1,2})""").find(normalized)?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        var hour = digits
        val isPm = normalized.contains("下午") || normalized.contains("晚上") ||
            normalized.contains("傍晚") || normalized.contains("夜")
        val isAm = normalized.contains("上午") || normalized.contains("早上") ||
            normalized.contains("凌晨")
        // 「晚上 7 点」= 19:00；「下午 2 点」= 14:00。
        // 但如果原文已经写了 19 这种 24 小时制，就不能再加 12。
        if (isPm && hour in 1..11) hour += 12
        if (isAm && hour == 12) hour = 0
        if (hour !in 0..23) return null

        // "14:30" 里已经有分钟的情况上面 parseMinute 就返回了，
        // 走到这里说明形如 "7点30" / "7点30分"
        val minute = Regex("""(?:点|:)\s*(\d{1,2})""").find(normalized)
            ?.groupValues?.get(1)?.toIntOrNull()
            ?: if (half) 30 else 0
        if (minute !in 0..59) return null
        return hour * 60 + minute
    }

    /**
     * 宽松日期解析。
     *
     * 模型在「只有日期没有时刻」的条目上会给空字符串，也可能给
     * `2026/10/17`、`2026.10.17`、`10月17日` 这些写法。
     * 读不出来返回 null，由调用方决定怎么提示——**不要静默丢弃**。
     */
    internal fun parseLooseDate(raw: String, today: LocalDate): LocalDate? {
        val text = raw.trim()
        if (text.isEmpty() || text == "null") return null

        runCatching { return LocalDate.parse(text) }

        val normalized = text
            .replace('/', '-')
            .replace('.', '-')
            .replace('年', '-')
            .replace('月', '-')
            .replace("日", "")
        runCatching { return LocalDate.parse(normalized) }

        // 10-17（缺年份）→ 补今年的年份
        val parts = normalized.split('-').filter { it.isNotBlank() }
        if (parts.size == 2) {
            val month = parts[0].toIntOrNull()
            val day = parts[1].toIntOrNull()
            if (month != null && day != null) {
                runCatching { return LocalDate.of(today.year, month, day) }
            }
        }
        return null
    }

    /** 中英文类别都认；认不出来按 ACTIVITY 处理，**不抛异常**。 */
    internal fun parseLooseKind(raw: String): EventKind {
        val text = raw.trim().uppercase()
        return when {
            text.isEmpty() || text == "NULL" -> EventKind.ACTIVITY
            text.contains("COURSE") || text.contains("课程") -> EventKind.COURSE
            text.contains("DEADLINE") || text.contains("截止") || text.contains("作业") -> EventKind.DEADLINE
            text.contains("OFFICE") || text.contains("答疑") -> EventKind.OFFICE_HOUR
            text.contains("ACTIVITY") || text.contains("活动") || text.contains("行程") -> EventKind.ACTIVITY
            else -> runCatching { EventKind.valueOf(text) }.getOrDefault(EventKind.ACTIVITY)
        }
    }
}
