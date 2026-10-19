package com.ww.kkexi

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JSON 取值的安全性。
 *
 * ## 这个测试锁住一个真机上抓到的 bug
 * `JSONObject.optString(key)` 在值是 JSON `null` 时**返回字符串 `"null"`**：
 * 它不是空串，而且 `optString(key, fallback)` 的 fallback **只在键不存在时**生效。
 *
 * 实测后果：DeepSeek 的流式响应每个 chunk 都带 `"reasoning_content": null`，
 * 代码里写的是 `optString(...).takeIf { it.isNotEmpty() }`——
 * `"null"` 不是空串，于是**每个空 chunk 都往思考过程末尾追加一次 "null"**，
 * 一整轮下来屏幕上出现几百个 null 连成一片（截图里确认过）。
 *
 * 同一个坑还会让日程的标题/地点/描述变成字面的 "null"。
 */
class JsonExtTest {

    @Test
    fun opt_string_behaves_differently_on_jvm_and_android_which_is_why_we_wrap_it() {
        /*
         * 同一个 `optString` 在两个平台上语义不同：
         * - **Android 的 org.json**：值是 JSON null 时返回字符串 `"null"`
         *   （`JSON.toString(JSONObject.NULL)` → `String.valueOf(NULL)` → `"null"`），
         *   它的实现：`String r = JSON.toString(opt(name)); return r != null ? r : fallback;`
         * - **JVM 的 org.json**（单测跑的这个）：值是 null 时返回 fallback。
         *
         * 实测后果只出现在真机上：DeepSeek 流式响应的每个 chunk 都带
         * `"reasoning_content": null`，于是「思考过程」末尾被追加了几百个 "null"。
         * 而单测在这条断言上**看不出问题**——所以这条测试只用来记录差异，
         * 真正要保证的是 [text] 的行为（下一条）。
         *
         * 这也正是「不能直接用 optString」的理由：它在两个平台上不一样，
         * 单测过的代码上真机仍可能出错。
         */
        val json = JSONObject("""{"reasoning_content":null}""")
        val jvmBehaviour = json.optString("reasoning_content")
        assertEquals("JVM 版 org.json 对 JSON null 返回 fallback（空串）", "", jvmBehaviour)
    }

    @Test
    fun text_returns_empty_for_a_json_null() {
        val json = JSONObject("""{"reasoning_content":null}""")
        assertEquals("", json.text("reasoning_content"))
        assertNull(json.textOrNull("reasoning_content"))
    }

    @Test
    fun text_returns_empty_for_a_missing_key() {
        val json = JSONObject("""{}""")
        assertEquals("", json.text("nothing"))
        assertNull(json.textOrNull("nothing"))
    }

    @Test
    fun text_keeps_real_values() {
        val json = JSONObject("""{"title":"高等数学","count":12,"flag":true}""")
        assertEquals("高等数学", json.text("title"))
        assertEquals("12", json.text("count"))
        assertEquals("true", json.text("flag"))
    }

    @Test
    fun a_deadline_with_null_location_does_not_become_the_word_null() {
        // 端到端：模型返回 null 字段时，候选里的地点/描述必须是空串
        val candidates = CandidateParser.parse(
            content = """{"items":[{
                "title":"交作业","kind":"deadline","date":"2026-10-21",
                "location":null,"note":null,"evidence":null,"start":null,"end":null
            }]}""",
            originalText = "10月21日交作业",
            today = java.time.LocalDate.of(2026, 10, 18),
            timetable = Timetable.DEFAULT,
        )
        assertEquals(1, candidates.size)
        val candidate = candidates[0]
        assertEquals("地点不该变成字面量 null", "", candidate.location)
        assertEquals("描述不该变成字面量 null", "", candidate.note)
        assertEquals("依据不该变成字面量 null", "", candidate.evidence)
        assertTrue("标题保持正常", candidate.title == "交作业")
        // 时间没给 → 截止型应落成全天
        assertTrue(candidate.shouldBeAllDay)
    }

    @Test
    fun int_or_null_distinguishes_zero_from_absent() {
        val json = JSONObject("""{"a":0,"b":null,"c":"7"}""")
        assertEquals(0, json.intOrNull("a"))
        assertNull("null 不等于 0", json.intOrNull("b"))
        assertNull(json.intOrNull("missing"))
        assertEquals(7, json.intOrNull("c"))
    }
}
