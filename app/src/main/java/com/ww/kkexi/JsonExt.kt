package com.ww.kkexi

import org.json.JSONObject

/**
 * 从 [JSONObject] 里安全地取字符串。
 *
 * ## 为什么必须封这一层（真机上抓到的 bug）
 * `JSONObject.optString(key)` 在值是 JSON `null` 时**返回字符串 `"null"`**，
 * 不是空串。而且 `optString(key, fallback)` 的 fallback **只在键不存在时**生效——
 * 键存在且为 `null` 时，它照样返回 `"null"`。
 *
 * 实测后果：DeepSeek 的流式响应**每个 chunk 都带** `"reasoning_content": null`，
 * 而代码是 `delta.optString("reasoning_content").takeIf { it.isNotEmpty() }`，
 * 于是每个空 chunk 都往「思考过程」末尾追加一次 `"null"`，
 * 一整轮下来变成几百个 null 连在一起（真机截图里能看到一整屏）。
 *
 * 同一个坑还会让日程的**标题/地点/描述**变成字面的 `"null"`——
 * 模型返回 `"location": null` 时，用户会在界面上看到「地点：null」。
 *
 * 所以凡是「从 JSON 读一个可能为 null 的文本字段」，都应走这里。
 */
internal fun JSONObject.text(key: String): String {
    val value = opt(key) ?: return ""
    if (value === JSONObject.NULL) return ""
    return value.toString()
}

/** 同 [text]，但空串按「没有」处理，便于和 `?.let` 配合。 */
internal fun JSONObject.textOrNull(key: String): String? = text(key).takeIf { it.isNotEmpty() }

/** 读一个可能为 null 的整数；缺失或为 `null` 时返回 null（而不是 0）。 */
internal fun JSONObject.intOrNull(key: String): Int? {
    val value = opt(key) ?: return null
    if (value === JSONObject.NULL) return null
    return when (value) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }
}
