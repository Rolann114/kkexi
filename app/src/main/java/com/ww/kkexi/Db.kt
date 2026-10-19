package com.ww.kkexi

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * SQLite 表结构。
 *
 * 为什么手写 SQLite 而不用 Room：见 README「已知局限」——
 * Room 需要 KSP 注解处理，会多引入一个必须与 Kotlin 版本严格配对的插件；
 * 本项目只有两张业务表，手写 DAO 的代码量可控，且少一层版本配对风险（AGENTS.md 硬约束 7「依赖克制」）。
 */
class Db(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    companion object {
        const val NAME = "kkexi.db"
        // v2：event 表新增「精确时刻」三列（timeMode / explicitStart / explicitEnd）。
        //     用户反馈两次：「新建行程要能直接填几点到几点，而不是只能选第几节」。
        // v3：event 表新增 category 列（用户可自定义的类别标签）。
        //     用户反馈：「新建行程这里自定义程度太小了，比如类别无法自定义」。
        // v4：event 表新增 sourceText / sourceImagePath 列（一手资料）。
        //     用户反馈：「点击时间轴的安排时，有一个看我上传的一手资料的选项」。
        const val VERSION = 4

        const val T_EVENT = "event"
        const val T_EXCEPTION = "event_exception"
        const val T_KV = "kv"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $T_EVENT (
                id TEXT PRIMARY KEY NOT NULL,
                title TEXT NOT NULL,
                kind TEXT NOT NULL,
                category TEXT NOT NULL DEFAULT '',
                sourceText TEXT NOT NULL DEFAULT '',
                sourceImagePath TEXT NOT NULL DEFAULT '',
                location TEXT NOT NULL DEFAULT '',
                note TEXT NOT NULL DEFAULT '',
                teacher TEXT NOT NULL DEFAULT '',
                anchorDate TEXT NOT NULL,
                firstPeriod INTEGER NOT NULL,
                periodCount INTEGER NOT NULL,
                recDayOfWeek INTEGER,
                recStartWeek INTEGER,
                recEndWeek INTEGER,
                recParity TEXT,
                recWeeks TEXT,
                done INTEGER NOT NULL DEFAULT 0,
                remindMinutesBefore INTEGER NOT NULL DEFAULT 30,
                reminderEnabled INTEGER NOT NULL DEFAULT 1,
                source TEXT NOT NULL DEFAULT '',
                timeMode TEXT NOT NULL DEFAULT 'PERIOD',
                explicitStart INTEGER,
                explicitEnd INTEGER
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE $T_EXCEPTION (
                eventId TEXT NOT NULL,
                originalDate TEXT NOT NULL,
                state TEXT NOT NULL,
                movedToDate TEXT,
                movedFirstPeriod INTEGER,
                movedPeriodCount INTEGER,
                movedLocation TEXT,
                note TEXT NOT NULL DEFAULT '',
                movedTimeMode TEXT,
                movedStart INTEGER,
                movedEnd INTEGER,
                PRIMARY KEY (eventId, originalDate)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE TABLE $T_KV (k TEXT PRIMARY KEY NOT NULL, v TEXT NOT NULL)")
    }

    /**
     * 增量迁移——**绝不 drop 用户数据**。
     *
     * v1 → v2：event 与 event_exception 各加「精确时刻」相关列。
     * 老数据一律视为按节次录入（`timeMode = 'PERIOD'`），
     * 因为 v1 里时间就是节次，语义完全一致，不需要换算。
     *
     * v2 → v3：event 加 category 列。老数据留空字符串，
     * 界面层用 [Event.displayCategory] 回退到 kind 的默认名字，所以显示不会变空。
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $T_EVENT ADD COLUMN timeMode TEXT NOT NULL DEFAULT 'PERIOD'")
            db.execSQL("ALTER TABLE $T_EVENT ADD COLUMN explicitStart INTEGER")
            db.execSQL("ALTER TABLE $T_EVENT ADD COLUMN explicitEnd INTEGER")
            db.execSQL("ALTER TABLE $T_EXCEPTION ADD COLUMN movedTimeMode TEXT")
            db.execSQL("ALTER TABLE $T_EXCEPTION ADD COLUMN movedStart INTEGER")
            db.execSQL("ALTER TABLE $T_EXCEPTION ADD COLUMN movedEnd INTEGER")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE $T_EVENT ADD COLUMN category TEXT NOT NULL DEFAULT ''")
        }
        if (oldVersion < 4) {
            // 老数据没有一手资料，留空即可——界面据此不显示那个入口。
            db.execSQL("ALTER TABLE $T_EVENT ADD COLUMN sourceText TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE $T_EVENT ADD COLUMN sourceImagePath TEXT NOT NULL DEFAULT ''")
        }
    }
}

/** 事件表 ↔ [Event] 的映射。 */
object EventMapper {

    fun toValues(event: Event): ContentValues = ContentValues().apply {
        put("id", event.id)
        put("title", event.title)
        put("kind", event.kind.name)
        put("category", event.category)
        put("sourceText", event.sourceText)
        put("sourceImagePath", event.sourceImagePath)
        put("location", event.location)
        put("note", event.note)
        put("teacher", event.teacher)
        put("anchorDate", event.anchorDate.toString())
        val rule = event.recurrence
        if (rule == null) {
            putNull("recDayOfWeek")
            putNull("recStartWeek")
            putNull("recEndWeek")
            putNull("recParity")
            putNull("recWeeks")
        } else {
            put("recDayOfWeek", rule.dayOfWeek.value)
            put("recStartWeek", rule.startWeek)
            put("recEndWeek", rule.endWeek)
            put("recParity", rule.parity.name)
            put("recWeeks", rule.weeks.joinToString(","))
        }
        put("done", if (event.done) 1 else 0)
        put("remindMinutesBefore", event.remindMinutesBefore)
        put("reminderEnabled", if (event.reminderEnabled) 1 else 0)
        put("source", event.source)
        // 时间：按节次还是按时刻。这一段是用户提了两次的那个需求的落点。
        when (val spec = event.time) {
            is TimeSpec.ByPeriod -> {
                put("timeMode", "PERIOD")
                put("firstPeriod", spec.firstPeriod)
                put("periodCount", spec.periodCount)
                putNull("explicitStart")
                putNull("explicitEnd")
            }
            is TimeSpec.ByClock -> {
                put("timeMode", "CLOCK")
                // 仍然写一份节次（最接近的那节），让老的字段不至于为空；
                // 但 timeMode=CLOCK 时所有时间计算都走 explicitStart/End。
                put("firstPeriod", 0)
                put("periodCount", 1)
                put("explicitStart", spec.startMinute)
                put("explicitEnd", spec.endMinute)
            }
            is TimeSpec.ByPoint -> {
                put("timeMode", "POINT")
                put("firstPeriod", 0)
                put("periodCount", 1)
                put("explicitStart", spec.minute)
                // 起止都写同一个值：读回时靠 timeMode=POINT 判断，不靠 end>start。
                put("explicitEnd", spec.minute)
            }
        }
    }

    fun fromCursor(cursor: android.database.Cursor): Event {
        val dayOfWeek = cursor.getIntOrNull("recDayOfWeek")
        val rule = if (dayOfWeek == null) {
            null
        } else {
            Recurrence(
                dayOfWeek = DayOfWeek.of(dayOfWeek),
                startWeek = cursor.getIntOrNull("recStartWeek") ?: 1,
                endWeek = cursor.getIntOrNull("recEndWeek") ?: Semester.totalWeeks,
                parity = cursor.getStringOrNull("recParity")?.let { WeekParity.valueOf(it) } ?: WeekParity.EVERY,
                weeks = cursor.getStringOrNull("recWeeks")
                    ?.split(',')
                    ?.mapNotNull { it.trim().toIntOrNull() }
                    ?: emptyList(),
            )
        }
        // 时间：新列决定是按时刻还是按节次。老库（v1 迁移上来的）没有 timeMode 值时按节次处理。
        val timeMode = cursor.getStringOrNull("timeMode") ?: "PERIOD"
        val explicitStart = cursor.getIntOrNull("explicitStart")
        val explicitEnd = cursor.getIntOrNull("explicitEnd")
        val time: TimeSpec = when {
            timeMode == "POINT" && explicitStart != null -> TimeSpec.ByPoint(explicitStart)
            timeMode == "CLOCK" && explicitStart != null && explicitEnd != null && explicitEnd > explicitStart ->
                TimeSpec.ByClock(explicitStart, explicitEnd)
            else -> TimeSpec.ByPeriod(
                firstPeriod = cursor.getInt(cursor.getColumnIndexOrThrow("firstPeriod")),
                periodCount = cursor.getInt(cursor.getColumnIndexOrThrow("periodCount")),
            )
        }

        return Event(
            id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
            title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
            kind = EventKind.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("kind"))),
            category = cursor.getStringOrNull("category") ?: "",
            sourceText = cursor.getStringOrNull("sourceText") ?: "",
            sourceImagePath = cursor.getStringOrNull("sourceImagePath") ?: "",
            location = cursor.getString(cursor.getColumnIndexOrThrow("location")),
            note = cursor.getString(cursor.getColumnIndexOrThrow("note")),
            teacher = cursor.getString(cursor.getColumnIndexOrThrow("teacher")),
            anchorDate = LocalDate.parse(cursor.getString(cursor.getColumnIndexOrThrow("anchorDate"))),
            time = time,
            recurrence = rule,
            done = cursor.getInt(cursor.getColumnIndexOrThrow("done")) == 1,
            remindMinutesBefore = cursor.getInt(cursor.getColumnIndexOrThrow("remindMinutesBefore")),
            reminderEnabled = cursor.getInt(cursor.getColumnIndexOrThrow("reminderEnabled")) == 1,
            source = cursor.getString(cursor.getColumnIndexOrThrow("source")),
        )
    }
}

/** 例外表 ↔ [EventException] 的映射。 */
object ExceptionMapper {

    fun toValues(exception: EventException): ContentValues = ContentValues().apply {
        put("eventId", exception.eventId)
        put("originalDate", exception.originalDate.toString())
        put("state", exception.state.name)
        put("movedToDate", exception.movedToDate?.toString())
        put("movedLocation", exception.movedLocation)
        put("note", exception.note)
        when (val spec = exception.movedTime) {
            null -> {
                putNull("movedTimeMode")
                putNull("movedStart")
                putNull("movedEnd")
            }
            is TimeSpec.ByPeriod -> {
                put("movedTimeMode", "PERIOD")
                put("movedFirstPeriod", spec.firstPeriod)
                put("movedPeriodCount", spec.periodCount)
                putNull("movedStart")
                putNull("movedEnd")
            }
            is TimeSpec.ByClock -> {
                put("movedTimeMode", "CLOCK")
                putNull("movedFirstPeriod")
                putNull("movedPeriodCount")
                put("movedStart", spec.startMinute)
                put("movedEnd", spec.endMinute)
            }
            is TimeSpec.ByPoint -> {
                put("movedTimeMode", "POINT")
                putNull("movedFirstPeriod")
                putNull("movedPeriodCount")
                put("movedStart", spec.minute)
                put("movedEnd", spec.minute)
            }
        }
    }

    fun fromCursor(cursor: android.database.Cursor): EventException = EventException(
        eventId = cursor.getString(cursor.getColumnIndexOrThrow("eventId")),
        originalDate = LocalDate.parse(cursor.getString(cursor.getColumnIndexOrThrow("originalDate"))),
        state = SessionState.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("state"))),
        movedToDate = cursor.getStringOrNull("movedToDate")?.let { LocalDate.parse(it) },
        movedTime = run {
            val mode = cursor.getStringOrNull("movedTimeMode")
            val start = cursor.getIntOrNull("movedStart")
            val end = cursor.getIntOrNull("movedEnd")
            when {
                mode == "POINT" && start != null -> TimeSpec.ByPoint(start)
                mode == "CLOCK" && start != null && end != null && end > start -> TimeSpec.ByClock(start, end)
                mode == "PERIOD" -> {
                    val first = cursor.getIntOrNull("movedFirstPeriod")
                    val count = cursor.getIntOrNull("movedPeriodCount")
                    if (first != null && count != null) TimeSpec.ByPeriod(first, count) else null
                }
                else -> null
            }
        },
        movedLocation = cursor.getStringOrNull("movedLocation"),
        note = cursor.getStringOrNull("note") ?: "",
    )
}

internal fun android.database.Cursor.getIntOrNull(column: String): Int? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getInt(index)
}

internal fun android.database.Cursor.getStringOrNull(column: String): String? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getString(index)
}
