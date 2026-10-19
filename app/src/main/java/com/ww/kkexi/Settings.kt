package com.ww.kkexi

import android.content.Context
import android.content.SharedPreferences

/**
 * 设置存储。
 *
 * 安全说明（必须原样保留在 README 里）：
 * DeepSeek API Key 只存在本机 SharedPreferences，只发往 https://api.deepseek.com，不经任何中转。
 * 界面用 [maskKey] 打码显示，另提供「清除 Key」按钮。
 */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("kkexi_settings", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString(KEY_API, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API, value.trim()).apply()

    /** AI 总开关。关掉后 App 完全离线，功能不受影响（AGENTS.md 硬约束 1）。 */
    var aiEnabled: Boolean
        get() = prefs.getBoolean(KEY_AI_ON, false)
        set(value) = prefs.edit().putBoolean(KEY_AI_ON, value).apply()

    var model: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString(KEY_MODEL, value.trim().ifEmpty { DEFAULT_MODEL }).apply()

    /**
     * 当前生效的作息表（第几节 = 几点到几点）。
     *
     * 存在 SharedPreferences 里（JSON 文本），所以换设备用备份恢复、或者学校临时调作息，
     * 用户自己就能改，不必等我发新版。
     */
    var timetable: Timetable
        get() {
            val raw = prefs.getString(KEY_TIMETABLE, null) ?: return Timetable.DEFAULT
            return Timetable.fromJson(raw) ?: Timetable.DEFAULT
        }
        set(value) = prefs.edit().putString(KEY_TIMETABLE, value.toJson()).apply()

    /** 已保存的作息表是不是就是内置默认那份（界面据此显示「默认」还是「已自定义」）。 */
    fun isUsingDefaultTimetable(): Boolean = prefs.getString(KEY_TIMETABLE, null).isNullOrEmpty()

    fun resetTimetable() {
        prefs.edit().remove(KEY_TIMETABLE).apply()
    }

    /**
     * 采样温度。解析与改动型任务要**确定性**，所以默认 0；
     * 用户想要更有"人味"的闲聊式回答时可以在设置里往上调。
     */
    var temperature: Double
        get() = prefs.getFloat(KEY_TEMPERATURE, 0f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_TEMPERATURE, value.toFloat().coerceIn(0f, 2f)).apply()

    /**
     * 首次启动要不要灌样例数据（用户本人的课表）。
     *
     * 默认 **false = 留空**：这样把 APK 分享给同学时，对方拿到的是干净的空 App，
     * 不会看到别人的课表。要恢复自己的课表就在设置页打开这一项再重装/清数据。
     */
    var seedSampleData: Boolean
        get() = prefs.getBoolean(KEY_SEED, false)
        set(value) = prefs.edit().putBoolean(KEY_SEED, value).apply()

    /** 提醒总开关（Q18 答复：要能一键关掉，不想被打扰时不用逐条改）。 */
    var remindersEnabled: Boolean
        get() = prefs.getBoolean(KEY_REMIND_ON, true)
        set(value) = prefs.edit().putBoolean(KEY_REMIND_ON, value).apply()

    /** 每日汇总提醒的开钟点（分钟数），默认 07:30。 */
    var dailyBriefingMinute: Int
        get() = prefs.getInt(KEY_DAILY, 7 * 60 + 30)
        set(value) = prefs.edit().putInt(KEY_DAILY, value).apply()

    var dailyBriefingEnabled: Boolean
        get() = prefs.getBoolean(KEY_DAILY_ON, true)
        set(value) = prefs.edit().putBoolean(KEY_DAILY_ON, value).apply()

    /** 课程默认提前量（分钟），默认 15。 */
    var courseLeadMinutes: Int
        get() = prefs.getInt(KEY_COURSE_LEAD, 15)
        set(value) = prefs.edit().putInt(KEY_COURSE_LEAD, value).apply()

    /** 其他事件默认提前量（分钟），默认 30。 */
    var eventLeadMinutes: Int
        get() = prefs.getInt(KEY_EVENT_LEAD, 30)
        set(value) = prefs.edit().putInt(KEY_EVENT_LEAD, value).apply()

    /** 时间轴默认展示多少天的未来（默认 30 天）。 */
    var horizonDays: Int
        get() = prefs.getInt(KEY_HORIZON, 30)
        set(value) = prefs.edit().putInt(KEY_HORIZON, value).apply()

    /**
     * 外观：跟随系统 / 强制浅色 / 强制深色。
     *
     * 为什么给强制选项：有些人的系统是深色，但这个 App 他只在白天用；
     * 也有人正好相反。跟随系统是合理的默认，但硬绑死就少了选择。
     */
    var themeMode: ThemeMode
        get() = prefs.getString(KEY_THEME_MODE, null)
            ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.SYSTEM
        set(value) = prefs.edit().putString(KEY_THEME_MODE, value.name).apply()

    /**
     * 是否用系统的「动态取色」（Material You，Android 12+）。
     *
     * 默认关闭：这个 App 用四种语义色区分事件类型，跟着壁纸变会让
     * 「课程蓝 / 死线橙」这个约定不稳。想更贴合系统观感的人可以打开。
     */
    var dynamicColor: Boolean
        get() = prefs.getBoolean(KEY_DYNAMIC_COLOR, false)
        set(value) = prefs.edit().putBoolean(KEY_DYNAMIC_COLOR, value).apply()

    /**
     * 用户自定义的「类别」标签列表。
     *
     * 用户反馈：「新建行程这里自定义程度太小了，比如类别无法自定义」。
     * 原先类别是写死的四个枚举（行程/截止/课程/答疑），这里改成可编辑列表，
     * 用户可以加「实验」「社团」「考试」这类自己需要的。
     *
     * 注意它和 [EventKind] 的分工：
     * - `category` 只是**给人看的标签**，随便改；
     * - `EventKind` 决定**行为**（课程会自动按周重复、截止会进「未完成的截止」），
     *   所以不跟着标签变。界面上类别选 COURSE 那几个时同时更新 kind。
     */
    var categories: List<String>
        get() {
            val raw = prefs.getString(KEY_CATEGORIES, null) ?: return DEFAULT_CATEGORIES
            val parsed = raw.split('\u001F').map { it.trim() }.filter { it.isNotEmpty() }
            return parsed.ifEmpty { DEFAULT_CATEGORIES }
        }
        set(value) {
            val cleaned = value.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(12)
            prefs.edit().putString(KEY_CATEGORIES, cleaned.joinToString("\u001F")).apply()
        }

    /** 已保存的类别是不是就是内置那四个（界面据此显示「默认」还是「已自定义」）。 */
    fun isUsingDefaultCategories(): Boolean = prefs.getString(KEY_CATEGORIES, null).isNullOrEmpty()

    fun resetCategories() {
        prefs.edit().remove(KEY_CATEGORIES).apply()
    }

    companion object {
        private const val KEY_API = "api_key"
        private const val KEY_AI_ON = "ai_enabled"
        private const val KEY_MODEL = "model"
        private const val KEY_TEMPERATURE = "temperature"
        private const val KEY_SEED = "seed_sample_data"
        private const val KEY_TIMETABLE = "timetable_json"
        private const val KEY_DAILY = "daily_briefing_minute"
        private const val KEY_DAILY_ON = "daily_briefing_enabled"
        private const val KEY_REMIND_ON = "reminders_enabled"
        private const val KEY_COURSE_LEAD = "course_lead_minutes"
        private const val KEY_EVENT_LEAD = "event_lead_minutes"
        private const val KEY_HORIZON = "horizon_days"
        private const val KEY_CATEGORIES = "categories"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"

        /** 默认类别，就是原来写死的那四个，保证老用户升级后界面不变。 */
        val DEFAULT_CATEGORIES: List<String> = listOf("行程", "截止", "课程", "答疑")

        /**
         * 默认模型。
         *
         * `deepseek-flash` 是实测查过 `/models` 接口确认可用的名字（2026-10-14 用本机 key 查得），
         * **不是凭记忆写的**——第一版我写的 `deepseek-chat` 在这个账号上根本不存在。
         */
        const val DEFAULT_MODEL = "deepseek-flash"
        const val BASE_URL = "https://api.deepseek.com"

        /**
         * 模型预设，按**任务强度**分档。
         *
         * 用户反馈第 7 条：「配了 V4 就用来抽个日程太浪费」——所以日常任务用便宜的 flash，
         * 需要深度推理时才切 pro。
         *
         * id 全部来自 `GET /models` 的实测返回（2026-10-14，本机 key）：
         *   - deepseek-flash
         *   - deepseek-v4-pro
         * 该接口返回的清单随账号而异，所以界面上保留了「自定义模型名」兜底；
         * 换模型只需在设置页填名字，不必改代码。
         */
        val MODEL_PRESETS: List<ModelPreset> = listOf(
            ModelPreset(
                id = "deepseek-flash",
                label = "日常（推荐）",
                detail = "抽取行程、改日程、回答问题。快、省额度，实测支持工具调用。",
            ),
            ModelPreset(
                id = "deepseek-v4-pro",
                label = "深度思考",
                detail = "复杂排期、多步推理、长文本理解。更贵更慢，但更准。",
            ),
        )

        /** 界面展示用：只露前 3 位与后 4 位。 */
        fun maskKey(key: String): String = when {
            key.isEmpty() -> "（未填写）"
            key.length <= 8 -> "****"
            else -> key.take(3) + "****" + key.takeLast(4)
        }
    }
}

/** 一个模型预设。[id] 直接作为 API 的 model 字段。 */
data class ModelPreset(val id: String, val label: String, val detail: String)

/** 界面外观模式。 */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
}
