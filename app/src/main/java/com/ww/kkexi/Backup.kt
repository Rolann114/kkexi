package com.ww.kkexi

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * JSON 导出/导入（Q4 = 纯本地 + 手动备份文件）。
 *
 * 格式版本化：[FORMAT_VERSION] 变化时导入端做兼容处理，不静默丢数据。
 *
 * ## v2 加了「设置快照」
 * v1 只导出日程与改期记录。但换手机时**设置也会一起丢**：
 * 自定义作息表、类别、外观、提醒提前量都得重新配一遍。
 * 用户不一定记得自己当初把第 3 节改成过 10:20，重配一次还可能配错，
 * 而这会直接改变所有「按节次录入的课程」的实际时刻。
 *
 * 所以 v2 把设置也带上。导入端对 v1 文件是兼容的（没有 [SettingsSnapshot] 就只导日程）。
 */
object Backup {

    const val FORMAT_VERSION = 2

    /**
     * 备份里的设置快照。
     *
     * 全部字段可选：`null` 表示「这份备份没带这一项」，导入时**保留当前值**，
     * 而不是把用户现有设置覆盖成默认值。
     */
    data class SettingsSnapshot(
        val timetable: Timetable? = null,
        val categories: List<String>? = null,
        val themeMode: ThemeMode? = null,
        val dynamicColor: Boolean? = null,
        val remindersEnabled: Boolean? = null,
        val dailyBriefingEnabled: Boolean? = null,
        val dailyBriefingMinute: Int? = null,
        val courseLeadMinutes: Int? = null,
        val eventLeadMinutes: Int? = null,
    ) {
        val isEmpty: Boolean
            get() = timetable == null && categories == null && themeMode == null
    }

    fun export(
        events: List<Event>,
        exceptions: List<EventException>,
        settings: SettingsSnapshot? = null,
    ): String {
        val root = JSONObject()
        root.put("format", "kkexi-backup")
        root.put("formatVersion", FORMAT_VERSION)
        root.put("exportedAt", java.time.LocalDateTime.now().toString())
        root.put("semesterFirstMonday", Semester.firstMonday.toString())

        settings?.let { snapshot ->
            val item = JSONObject()
            snapshot.timetable?.let { item.put("timetable", it.toJson()) }
            snapshot.categories?.let { item.put("categories", JSONArray(it)) }
            snapshot.themeMode?.let { item.put("themeMode", it.name) }
            snapshot.dynamicColor?.let { item.put("dynamicColor", it) }
            snapshot.remindersEnabled?.let { item.put("remindersEnabled", it) }
            snapshot.dailyBriefingEnabled?.let { item.put("dailyBriefingEnabled", it) }
            snapshot.dailyBriefingMinute?.let { item.put("dailyBriefingMinute", it) }
            snapshot.courseLeadMinutes?.let { item.put("courseLeadMinutes", it) }
            snapshot.eventLeadMinutes?.let { item.put("eventLeadMinutes", it) }
            root.put("settings", item)
        }

        val eventArray = JSONArray()
        for (event in events) {
            val item = JSONObject()
            item.put("id", event.id)
            item.put("title", event.title)
            item.put("kind", event.kind.name)
            item.put("category", event.category)
            item.put("location", event.location)
            item.put("note", event.note)
            item.put("teacher", event.teacher)
            item.put("anchorDate", event.anchorDate.toString())
            // 时间：按时刻还是按节次。老备份文件没有这两个键，导入端会退化成按节次。
            when (val spec = event.time) {
                is TimeSpec.ByPeriod -> {
                    item.put("timeMode", "PERIOD")
                    item.put("firstPeriod", spec.firstPeriod)
                    item.put("periodCount", spec.periodCount)
                }
                is TimeSpec.ByClock -> {
                    item.put("timeMode", "CLOCK")
                    item.put("startMinute", spec.startMinute)
                    item.put("endMinute", spec.endMinute)
                }
                is TimeSpec.ByPoint -> {
                    item.put("timeMode", "POINT")
                    item.put("startMinute", spec.minute)
                    item.put("endMinute", spec.minute)
                }
            }
            item.put("done", event.done)
            item.put("remindMinutesBefore", event.remindMinutesBefore)
            item.put("reminderEnabled", event.reminderEnabled)
            item.put("source", event.source)
            event.recurrence?.let { rule ->
                val rec = JSONObject()
                rec.put("dayOfWeek", rule.dayOfWeek.value)
                rec.put("startWeek", rule.startWeek)
                rec.put("endWeek", rule.endWeek)
                rec.put("parity", rule.parity.name)
                rec.put("weeks", JSONArray(rule.weeks))
                item.put("recurrence", rec)
            }
            eventArray.put(item)
        }
        root.put("events", eventArray)

        val exceptionArray = JSONArray()
        for (exception in exceptions) {
            val item = JSONObject()
            item.put("eventId", exception.eventId)
            item.put("originalDate", exception.originalDate.toString())
            item.put("state", exception.state.name)
            exception.movedToDate?.let { item.put("movedToDate", it.toString()) }
            when (val spec = exception.movedTime) {
                null -> Unit
                is TimeSpec.ByPeriod -> {
                    item.put("movedTimeMode", "PERIOD")
                    item.put("movedFirstPeriod", spec.firstPeriod)
                    item.put("movedPeriodCount", spec.periodCount)
                }
                is TimeSpec.ByClock -> {
                    item.put("movedTimeMode", "CLOCK")
                    item.put("movedStart", spec.startMinute)
                    item.put("movedEnd", spec.endMinute)
                }
                is TimeSpec.ByPoint -> {
                    item.put("movedTimeMode", "POINT")
                    item.put("movedStart", spec.minute)
                    item.put("movedEnd", spec.minute)
                }
            }
            exception.movedLocation?.let { item.put("movedLocation", it) }
            item.put("note", exception.note)
            exceptionArray.put(item)
        }
        root.put("exceptions", exceptionArray)
        return root.toString(2)
    }

    /** 导入结果；[errors] 非空表示有行被跳过，界面必须把原文展示给用户。 */
    data class ImportResult(
        val events: List<Event>,
        val exceptions: List<EventException>,
        val errors: List<String>,
        /** v2 起带设置；v1 文件这里是空的。 */
        val settings: SettingsSnapshot = SettingsSnapshot(),
    )

    fun parse(text: String): ImportResult {
        val errors = ArrayList<String>()
        val root = try {
            JSONObject(text)
        } catch (error: Exception) {
            return ImportResult(emptyList(), emptyList(), listOf("不是合法 JSON：${error.message}"))
        }

        // 兼容 v1 与 v2：v1 只是没有 settings，其余结构一致。
        val version = root.optInt("formatVersion", 0)
        if (version !in 1..FORMAT_VERSION) {
            errors += "格式版本不认识：文件是 $version，本程序支持 1..$FORMAT_VERSION"
        }

        val settings = root.optJSONObject("settings")?.let { item ->
            SettingsSnapshot(
                timetable = item.text("timetable").takeIf { it.isNotEmpty() }
                    ?.let { Timetable.fromJson(it) },
                categories = item.optJSONArray("categories")?.let { array ->
                    buildList { for (i in 0 until array.length()) add(array.getString(i)) }
                },
                themeMode = item.text("themeMode").takeIf { it.isNotEmpty() }
                    ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() },
                dynamicColor = if (item.has("dynamicColor")) item.getBoolean("dynamicColor") else null,
                remindersEnabled = if (item.has("remindersEnabled")) item.getBoolean("remindersEnabled") else null,
                dailyBriefingEnabled = if (item.has("dailyBriefingEnabled")) {
                    item.getBoolean("dailyBriefingEnabled")
                } else {
                    null
                },
                dailyBriefingMinute = if (item.has("dailyBriefingMinute")) item.getInt("dailyBriefingMinute") else null,
                courseLeadMinutes = if (item.has("courseLeadMinutes")) item.getInt("courseLeadMinutes") else null,
                eventLeadMinutes = if (item.has("eventLeadMinutes")) item.getInt("eventLeadMinutes") else null,
            )
        } ?: SettingsSnapshot()

        val events = ArrayList<Event>()
        val eventArray = root.optJSONArray("events") ?: JSONArray()
        for (index in 0 until eventArray.length()) {
            val item = eventArray.optJSONObject(index) ?: continue
            try {
                val rule = item.optJSONObject("recurrence")?.let { rec ->
                    Recurrence(
                        dayOfWeek = java.time.DayOfWeek.of(rec.getInt("dayOfWeek")),
                        startWeek = rec.getInt("startWeek"),
                        endWeek = rec.getInt("endWeek"),
                        parity = WeekParity.valueOf(rec.text("parity").ifEmpty { WeekParity.EVERY.name }),
                        weeks = rec.optJSONArray("weeks")?.let { array ->
                            buildList { for (i in 0 until array.length()) add(array.getInt(i)) }
                        } ?: emptyList(),
                    )
                }
                // 时间：优先读新格式的 timeMode；老备份（没有 timeMode）按节次处理，
                // 因为 v1 的备份里时间就是节次，语义一致、不需要换算。
                val time: TimeSpec = when (item.text("timeMode")) {
                    "POINT" -> TimeSpec.ByPoint(item.optInt("startMinute", 0))
                    "CLOCK" -> {
                        val start = item.optInt("startMinute", -1)
                        val end = item.optInt("endMinute", -1)
                        if (start >= 0 && end > start) {
                            TimeSpec.ByClock(start, end)
                        } else {
                            TimeSpec.ByPeriod(item.getInt("firstPeriod"), item.getInt("periodCount"))
                        }
                    }
                    else -> TimeSpec.ByPeriod(item.getInt("firstPeriod"), item.getInt("periodCount"))
                }

                events += Event(
                    id = item.getString("id"),
                    title = item.getString("title"),
                    kind = EventKind.valueOf(item.getString("kind")),
                    // 老备份没有 category 键，留空字符串，界面会回退到 kind 的默认名。
                    category = item.text("category").ifEmpty { "" },
                    location = item.text("location").ifEmpty { "" },
                    note = item.text("note").ifEmpty { "" },
                    teacher = item.text("teacher").ifEmpty { "" },
                    anchorDate = LocalDate.parse(item.getString("anchorDate")),
                    time = time,
                    recurrence = rule,
                    done = item.optBoolean("done", false),
                    remindMinutesBefore = item.optInt("remindMinutesBefore", 30),
                    reminderEnabled = item.optBoolean("reminderEnabled", true),
                    source = item.text("source").ifEmpty { "" },
                )
            } catch (error: Exception) {
                errors += "第 ${index + 1} 条事件解析失败，已跳过：${error.message}"
            }
        }

        val exceptions = ArrayList<EventException>()
        val exceptionArray = root.optJSONArray("exceptions") ?: JSONArray()
        for (index in 0 until exceptionArray.length()) {
            val item = exceptionArray.optJSONObject(index) ?: continue
            try {
                exceptions += EventException(
                    eventId = item.getString("eventId"),
                    originalDate = LocalDate.parse(item.getString("originalDate")),
                    state = SessionState.valueOf(item.getString("state")),
                    movedToDate = item.text("movedToDate").takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) },
                    movedTime = run {
                        val mode = item.text("movedTimeMode")
                        val start = if (item.has("movedStart")) item.getInt("movedStart") else null
                        val end = if (item.has("movedEnd")) item.getInt("movedEnd") else null
                        when {
                            mode == "POINT" && start != null -> TimeSpec.ByPoint(start)
                            mode == "CLOCK" && start != null && end != null && end > start -> TimeSpec.ByClock(start, end)
                            item.has("movedFirstPeriod") && item.has("movedPeriodCount") ->
                                TimeSpec.ByPeriod(item.getInt("movedFirstPeriod"), item.getInt("movedPeriodCount"))
                            else -> null
                        }
                    },
                    movedLocation = item.text("movedLocation").takeIf { it.isNotEmpty() },
                    note = item.text("note").ifEmpty { "" },
                )
            } catch (error: Exception) {
                errors += "第 ${index + 1} 条例外解析失败，已跳过：${error.message}"
            }
        }

        return ImportResult(events, exceptions, errors, settings)
    }
}
