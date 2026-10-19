package com.ww.kkexi

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * AI 会话历史（存本地 SQLite）。
 *
 * 用户反馈：「会话历史可保存/多会话」——之前对话只在内存里，退出就没了。
 *
 * 设计取舍：
 * - **一个会话**（不做多会话切换）。用户实际要的是"别一关就没"，不是项目管理；
 *   等真的需要分主题再引入会话表，现在多一层只会让界面更复杂。
 * - 保留最近 [MAX_MESSAGES] 条，超出从最旧的删起，避免库无限长。
 * - 只存文本；图片不入库（体积大、且没必要）。
 */
class ChatStore(context: Context) {

    private val helper = object : SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE message (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    fromUser INTEGER NOT NULL,
                    text TEXT NOT NULL,
                    reasoning TEXT NOT NULL DEFAULT '',
                    promptTokens INTEGER NOT NULL DEFAULT 0,
                    completionTokens INTEGER NOT NULL DEFAULT 0,
                    reasoningTokens INTEGER NOT NULL DEFAULT 0,
                    createdAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }

        /**
         * v1 → v2：加三个 token 列。
         *
         * 用户要求「能看见每次输入输出所消耗的 token」。第一版只存在内存里，
         * 重启 App 后旧消息就不显示用量了；而「花了多少」恰恰是回头才想看的。
         * 增量迁移，`ALTER TABLE ADD COLUMN`，不 drop 数据。
         */
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE message ADD COLUMN promptTokens INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE message ADD COLUMN completionTokens INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE message ADD COLUMN reasoningTokens INTEGER NOT NULL DEFAULT 0")
            }
        }
    }

    data class Stored(
        val fromUser: Boolean,
        val text: String,
        val reasoning: String,
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,
        val reasoningTokens: Int = 0,
    )

    fun all(): List<Stored> {
        val db = helper.readableDatabase
        return db.rawQuery(
            "SELECT fromUser, text, reasoning, promptTokens, completionTokens, reasoningTokens " +
                "FROM message ORDER BY id ASC",
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        Stored(
                            fromUser = cursor.getInt(0) == 1,
                            text = cursor.getString(1),
                            reasoning = cursor.getString(2),
                            promptTokens = cursor.getInt(3),
                            completionTokens = cursor.getInt(4),
                            reasoningTokens = cursor.getInt(5),
                        )
                    )
                }
            }
        }
    }

    fun append(
        fromUser: Boolean,
        text: String,
        reasoning: String = "",
        promptTokens: Int = 0,
        completionTokens: Int = 0,
        reasoningTokens: Int = 0,
    ) {
        val db = helper.writableDatabase
        db.insert(
            "message",
            null,
            ContentValues().apply {
                put("fromUser", if (fromUser) 1 else 0)
                put("text", text)
                put("reasoning", reasoning)
                put("promptTokens", promptTokens)
                put("completionTokens", completionTokens)
                put("reasoningTokens", reasoningTokens)
                put("createdAt", System.currentTimeMillis())
            },
        )
        trim()
    }

    fun clear() {
        helper.writableDatabase.delete("message", null, null)
    }

    /**
     * 改写**最后一条用户消息**的正文。
     *
     * ## 为什么需要它（踩过的坑）
     * 用户发图片时，那条消息是先以占位符 `"[图片] "` 落库的，之后才异步读图。
     * 读出来之后必须**回头把内容补写进去**，否则下一轮回灌历史时模型看到的是一条
     * **空的用户消息**——它就会说「我这边并没有收到任何图片」，甚至否认自己刚说过的话。
     *
     * 实测数据库里的原文：
     *     #39 用户: '[图片] '      ← 空的，模型什么都看不到
     *     #40 助手: '我从这张图里读出 15 条候选…'
     *     #42 助手: '我这边并没有收到任何图片，上一条是我凭空说的'
     */
    fun replaceLastFromUser(text: String) {
        val db = helper.writableDatabase
        val id = db.rawQuery(
            "SELECT id FROM message WHERE fromUser = 1 ORDER BY id DESC LIMIT 1",
            null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else -1L }
        if (id < 0) return
        db.update(
            "message",
            ContentValues().apply { put("text", text) },
            "id = ?",
            arrayOf(id.toString()),
        )
    }

    /** 只保留最近 MAX_MESSAGES 条。 */
    private fun trim() {
        helper.writableDatabase.execSQL(
            """
            DELETE FROM message WHERE id NOT IN (
                SELECT id FROM message ORDER BY id DESC LIMIT $MAX_MESSAGES
            )
            """.trimIndent()
        )
    }

    companion object {
        private const val NAME = "kkexi_chat.db"

        /**
         * 库版本。
         *
         * v1：只有 fromUser/text/reasoning/createdAt。
         * v2：加三个 token 列（用户要求「能看见每次消耗」，且要能回看）。
         */
        private const val VERSION = 2

        const val MAX_MESSAGES = 200
    }
}

/**
 * 把一段文本渲染成"当天简报"，用于 AI 助手首页的问候语。
 *
 * 与 [Alarms] 里的每日汇总措辞保持一致，免得同一个 App 里两种说法。
 */
object DailyGreeting {

    fun build(today: LocalDate, sessions: List<Session>): String {
        val week = DateTimeExt.weekLabel(today)
        if (sessions.isEmpty()) {
            return "今天是 ${DateTimeExt.formatDateWithDay(today)}，$week。\n今天没有安排。"
        }
        val lines = sessions.joinToString("\n") { session ->
            "· ${session.timeLabel} ${session.title}" +
                if (session.location.isEmpty()) "" else " @${session.location}"
        }
        return "今天是 ${DateTimeExt.formatDateWithDay(today)}，$week，共 ${sessions.size} 件事：\n$lines"
    }

    /** "10月14日 周三" */
    fun stamp(today: LocalDate): String = DateTimeExt.formatDateWithDay(today)

    /** 供界面显示"多久之前"这类文字。 */
    fun relative(millis: Long): String {
        val then = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDateTime()
        val now = java.time.LocalDateTime.now()
        val minutes = java.time.Duration.between(then, now).toMinutes()
        return when {
            minutes < 1 -> "刚刚"
            minutes < 60 -> "$minutes 分钟前"
            minutes < 60 * 24 -> "${minutes / 60} 小时前"
            else -> "${minutes / (60 * 24)} 天前"
        }
    }
}
