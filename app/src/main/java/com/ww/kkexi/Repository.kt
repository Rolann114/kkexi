package com.ww.kkexi

import android.content.Context
import java.time.LocalDate

/**
 * 全部数据访问的唯一入口。
 *
 * 设计要点：界面只跟 [Session] 打交道（展开后的具体发生），
 * 主规则与例外的合并逻辑全部收敛在 [Expander]（纯函数，可被单测覆盖）。
 */
class Repository(context: Context) {

    private val helper = Db(context.applicationContext)

    /**
     * 当前生效的作息表。
     *
     * 每次展开都重新读一遍设置，所以用户在设置页改完作息表、界面一刷新，全库时间就跟着变——
     * 不需要把已存的事件逐条改时间（事件里存的是节次，时刻是展开时算出来的）。
     */
    private val settings = Settings(context)

    // ---------------------------------------------------------------- 初始化

    /** 首次启动时灌入初始数据；已有数据则原样保留。 */
    fun seedIfEmpty(): Boolean {
        val db = helper.writableDatabase
        val count = db.rawQuery("SELECT COUNT(*) FROM ${Db.T_EVENT}", null).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
        if (count > 0) return false
        db.beginTransaction()
        try {
            for (event in SeedData.events) {
                db.insertWithOnConflict(Db.T_EVENT, null, EventMapper.toValues(event), android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return true
    }

    // ---------------------------------------------------------------- 主规则

    fun allEvents(): List<Event> {
        val db = helper.readableDatabase
        return db.rawQuery("SELECT * FROM ${Db.T_EVENT} ORDER BY anchorDate, firstPeriod", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(EventMapper.fromCursor(cursor)) }
        }
    }

    fun event(id: String): Event? {
        val db = helper.readableDatabase
        return db.rawQuery("SELECT * FROM ${Db.T_EVENT} WHERE id = ?", arrayOf(id)).use { cursor ->
            if (cursor.moveToFirst()) EventMapper.fromCursor(cursor) else null
        }
    }

    fun upsert(event: Event) {
        helper.writableDatabase.insertWithOnConflict(
            Db.T_EVENT, null, EventMapper.toValues(event), android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun deleteEvent(id: String) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete(Db.T_EXCEPTION, "eventId = ?", arrayOf(id))
            db.delete(Db.T_EVENT, "id = ?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---------------------------------------------------------------- 例外

    fun exceptionsFor(eventId: String): List<EventException> {
        val db = helper.readableDatabase
        return db.rawQuery("SELECT * FROM ${Db.T_EXCEPTION} WHERE eventId = ?", arrayOf(eventId)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(ExceptionMapper.fromCursor(cursor)) }
        }
    }

    fun allExceptions(): List<EventException> {
        val db = helper.readableDatabase
        return db.rawQuery("SELECT * FROM ${Db.T_EXCEPTION}", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(ExceptionMapper.fromCursor(cursor)) }
        }
    }

    fun upsertException(exception: EventException) {
        helper.writableDatabase.insertWithOnConflict(
            Db.T_EXCEPTION, null, ExceptionMapper.toValues(exception),
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun deleteException(eventId: String, originalDate: LocalDate) {
        helper.writableDatabase.delete(
            Db.T_EXCEPTION, "eventId = ? AND originalDate = ?", arrayOf(eventId, originalDate.toString()),
        )
    }

    // ---------------------------------------------------------------- 展开

    /** 把 [from]..[to] 之间的一切展开成按时间排序的具体发生。 */
    fun sessions(from: LocalDate, to: LocalDate): List<Session> {
        val events = allEvents()
        val exceptions = allExceptions()
        val timetable = settings.timetable
        val result = ArrayList<Session>()
        for (event in events) {
            result += Expander.expand(event, from, to, exceptions, timetable)
        }
        // 「已取消」排在最后；同一天内按分钟排序。
        return result.sortedWith(
            compareBy({ it.date }, { if (it.state == SessionState.CANCELLED) 1 else 0 }, { it.startMinute }),
        )
    }

    fun sessionsOn(date: LocalDate): List<Session> = sessions(date, date)

    /** 未完成的截止型事项，按日期升序——时间轴顶部常驻。 */
    fun pendingDeadlines(today: LocalDate): List<Session> =
        sessions(today.minusDays(60), today.plusDays(180))
            .filter { it.kind == EventKind.DEADLINE && !it.done && it.state != SessionState.CANCELLED }

    /**
     * 同一天内与 [session] 时间重叠的其他发生。
     *
     * Q26 =「不拦，只标」：调用方只用来画冲突角标，不阻止保存。
     */
    fun conflictsOf(session: Session): List<Session> = sessionsOn(session.date).filter { other ->
        other.id != session.id &&
            other.state != SessionState.CANCELLED &&
            other.startMinute < session.endMinute &&
            session.startMinute < other.endMinute
    }

    /** 某个教学周内每一天的所有发生，供课表周视图使用。 */
    fun weekSessions(week: Int): Map<LocalDate, List<Session>> {
        val monday = Semester.mondayOf(week)
        return sessions(monday, monday.plusDays(6)).groupBy { it.date }
    }

    // ---------------------------------------------------------------- 便捷操作

    /** 勾选/取消勾选一条截止型事项。 */
    fun setDone(session: Session, done: Boolean) {
        val event = event(session.eventId) ?: return
        upsert(event.copy(done = done))
    }

    /** 取消某一次发生（Q24 动作之一）。 */
    fun cancelOccurrence(session: Session, note: String = "已取消") {
        upsertException(
            EventException(
                eventId = session.eventId,
                originalDate = session.date,
                state = SessionState.CANCELLED,
                note = note,
            )
        )
    }

    /** 把某一次发生改到别的时间地点（Q24 动作之二）。 */
    fun moveOccurrence(
        session: Session,
        toDate: LocalDate,
        time: TimeSpec,
        location: String?,
    ) {
        upsertException(
            EventException(
                eventId = session.eventId,
                originalDate = session.date,
                state = SessionState.MOVED,
                movedToDate = toDate,
                movedTime = time,
                movedLocation = location,
                note = "由 ${session.date.monthValue}月${session.date.dayOfMonth}日 调整",
            )
        )
    }

    /** 撤销某一次的覆盖，回到主规则。 */
    fun revertOccurrence(session: Session) {
        deleteException(session.eventId, session.date)
    }

    /**
     * 改整条规则（Q23 =「从本周起生效」默认）。
     *
     * 实现方式：把主规则的 anchorDate 推到本周一，并保留一个「历史段」的例外语义——
     * 简化起见，这里只改 anchorDate/时间/地点，**不改已过去周次的显示**由 anchorDate 截断保证。
     */
    fun updateSeries(event: Event, fromWeek: Int, newTime: TimeSpec, newLocation: String) {
        val rule = event.recurrence ?: run {
            upsert(event.copy(time = newTime, location = newLocation))
            return
        }
        val newAnchor = maxOf(event.anchorDate, Semester.mondayOf(fromWeek))
        upsert(
            event.copy(
                anchorDate = newAnchor,
                time = newTime,
                location = newLocation,
                recurrence = rule.copy(startWeek = maxOf(rule.startWeek, fromWeek)),
            )
        )
    }
}
