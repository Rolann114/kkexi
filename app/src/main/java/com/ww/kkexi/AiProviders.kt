package com.ww.kkexi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个 AI 服务商的配置。
 *
 * 为什么要多份（用户反馈第 6、7 条 + 参考 cc switch）：
 *   - 日常任务用便宜快的模型，复杂排期才切强模型，不该一种模型干所有事；
 *   - 读图这类能力，不同服务商差别很大，得能按能力挑；
 *   - 用户以后想换供应商（智谱 / 通义 / OpenRouter…）不该改代码。
 *
 * 所有字段都存在本机 SharedPreferences，随备份一起导出。
 */
data class AiProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val temperature: Double,
    /** 思考强度。合法值见 [AiProvider.EFFORT_VALUES]；空串表示不发这个参数。 */
    val reasoningEffort: String = "",
    /** 该模型是否支持图片输入。**必须实测确定**，不许猜——所以由用户勾选，界面会提示怎么自测。 */
    val supportsVision: Boolean = false,
) {
    val configured: Boolean get() = apiKey.isNotBlank() && model.isNotBlank()

    /** 界面上显示的打码 key。 */
    val maskedKey: String get() = Settings.maskKey(apiKey)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("baseUrl", baseUrl)
        put("apiKey", apiKey)
        put("model", model)
        put("temperature", temperature)
        put("reasoningEffort", reasoningEffort)
        put("supportsVision", supportsVision)
    }

    companion object {
        /**
         * 思考强度的合法值。
         *
         * **实测来源**（2026-10-14，用本机 key 打 DeepSeek）：
         * 传 `off` 时服务端 422，报错原文列出了完整值域：
         *   `expected one of \`none\`, \`minimal\`, \`low\`, \`medium\`, \`high\`, \`xhigh\`, \`ultra\`, \`max\``
         * 且实测 `none` → reasoning_tokens=0，`low` → 385，`medium` → 732，
         * 证明这个参数**真的生效**，不是被接受后忽略。
         *
         * 注意没有 `off`：关掉思考要用 `none`。
         */
        val EFFORT_VALUES = listOf("", "none", "minimal", "low", "medium", "high", "xhigh", "ultra", "max")

        fun effortLabel(value: String): String = when (value) {
            "" -> "不指定"
            "none" -> "none（不想，最快最省）"
            "minimal" -> "minimal"
            "low" -> "low"
            "medium" -> "medium"
            "high" -> "high"
            "xhigh" -> "xhigh"
            "ultra" -> "ultra"
            "max" -> "max（想最多）"
            else -> value
        }

        fun fromJson(json: JSONObject): AiProvider = AiProvider(
            id = json.text("id").ifEmpty { "p-" + System.currentTimeMillis().toString(36) },
            name = json.text("name").ifEmpty { "未命名" },
            baseUrl = json.text("baseUrl").ifEmpty { DEFAULT_BASE_URL },
            apiKey = json.text("apiKey"),
            model = json.text("model"),
            temperature = json.optDouble("temperature", 0.0),
            reasoningEffort = json.text("reasoningEffort"),
            supportsVision = json.optBoolean("supportsVision", false),
        )

        const val DEFAULT_BASE_URL = "https://api.deepseek.com"

        /**
         * 内置预设：用户点一下就能填好大部分字段，只需要补 key。
         *
         * 地址与模型名是**公开文档里的事实**；具体账号能用哪些模型仍以 `/models` 为准，
         * 所以界面上保留了「测试连接」让用户当场验证，而不是替他假设。
         */
        fun presets(): List<AiProvider> = listOf(
            AiProvider(
                id = "preset-deepseek-flash",
                name = "DeepSeek · 日常",
                baseUrl = DEFAULT_BASE_URL,
                apiKey = "",
                model = "deepseek-flash",
                temperature = 0.0,
                reasoningEffort = "none",
                supportsVision = true, // 实测：能读图（但会串列，见 README）
            ),
            AiProvider(
                id = "preset-deepseek-pro",
                name = "DeepSeek · 深度思考",
                baseUrl = DEFAULT_BASE_URL,
                apiKey = "",
                model = "deepseek-v4-pro",
                temperature = 0.0,
                reasoningEffort = "medium",
                supportsVision = true,
            ),
            AiProvider(
                id = "preset-zhipu-vision",
                name = "智谱 GLM（备用视觉）",
                baseUrl = "https://open.bigmodel.cn/api/paas/v4",
                apiKey = "",
                model = "glm-4v",
                temperature = 0.0,
                reasoningEffort = "",
                supportsVision = true,
            ),
            AiProvider(
                id = "preset-custom",
                name = "自定义（OpenAI 兼容）",
                baseUrl = DEFAULT_BASE_URL,
                apiKey = "",
                model = "",
                temperature = 0.0,
                reasoningEffort = "",
                supportsVision = false,
            ),
        )
    }
}

/**
 * 多服务商配置的读写。
 *
 * 存在 SharedPreferences 的一个 JSON 数组里；
 * 另外单独记一个 [activeId]，表示当前用哪一份。
 */
class AiConfigStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("kkexi_ai_providers", Context.MODE_PRIVATE)

    /** 全部服务商。首次访问时用内置预设初始化（但不含任何 key）。 */
    fun all(): List<AiProvider> {
        val raw = prefs.getString(KEY_LIST, null)
        if (raw.isNullOrEmpty()) return AiProvider.presets()
        return try {
            val array = JSONArray(raw)
            val list = ArrayList<AiProvider>()
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { list += AiProvider.fromJson(it) }
            }
            list.ifEmpty { AiProvider.presets() }
        } catch (_: Exception) {
            AiProvider.presets()
        }
    }

    fun save(providers: List<AiProvider>) {
        val array = JSONArray()
        for (provider in providers) array.put(provider.toJson())
        prefs.edit().putString(KEY_LIST, array.toString()).apply()
    }

    fun add(provider: AiProvider): List<AiProvider> {
        val current = all()
        val index = current.indexOfFirst { it.id == provider.id }
        val updated = if (index >= 0) {
            /*
             * 原地替换，**保持列表顺序不变**。
             *
             * ## 踩过的坑（用户会直接看到的 bug）
             * 原先是 `all().filterNot { it.id == provider.id } + provider`——先删再追加，
             * 于是这份配置每次被更新都会跑到列表**最后一位**。
             * 而 `AppViewModel.setEffort()` 每次改「思考等级」都会走 add()，
             * 结果就是：在 AI 助手页调一次思考强度，回设置页一看，
             * 自己那份配好的服务商从第一位跑到了「未填写 Key」的三项之后。
             * 用户当时装的就是 4 份预设，配上 Key 的那份排在末尾，看起来像"丢了"。
             */
            current.toMutableList().also { it[index] = provider }
        } else {
            current + provider
        }
        save(updated)
        return updated
    }

    fun remove(id: String): List<AiProvider> {
        val updated = all().filterNot { it.id == id }
        save(updated)
        if (activeId() == id) {
            prefs.edit().putString(KEY_ACTIVE, updated.firstOrNull()?.id.orEmpty()).apply()
        }
        return updated
    }

    fun activeId(): String = prefs.getString(KEY_ACTIVE, "").orEmpty()

    fun setActive(id: String) {
        prefs.edit().putString(KEY_ACTIVE, id).apply()
    }

    /** 当前生效的服务商；没有任何配置好时返回 null。 */
    fun active(): AiProvider? {
        val list = all()
        val id = activeId()
        return list.firstOrNull { it.id == id && it.configured }
            ?: list.firstOrNull { it.configured }
    }

    /** 第一个勾了「支持图片」且配置好的服务商——读图功能用它。 */
    fun visionCapable(): AiProvider? =
        all().firstOrNull { it.configured && it.supportsVision }

    private companion object {
        const val KEY_LIST = "providers_json"
        const val KEY_ACTIVE = "active_id"
    }
}
