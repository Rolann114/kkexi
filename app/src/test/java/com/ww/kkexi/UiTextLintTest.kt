package com.ww.kkexi

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 界面文案的静态检查。
 *
 * ## 为什么要这条测试
 * Compose 的 `Text("...")` **不解析 Markdown**。
 * 我在给界面写说明文字时，三次顺手用了 `**加粗**` 的写法，
 * 结果屏幕上原样显示出两个星号（真机上看到过：「是**一份空壳课表**」）。
 *
 * 三次都是人工回看截图才发现的——这种错误用户不会报，只会觉得「这 App 有点糙」。
 * 与其每次都靠眼睛，不如让测试盯住。
 *
 * ## 判定规则
 * 扫所有 .kt 源码，找**字符串字面量里**出现 `**` 的行。
 * 注释里的不算（`//` 与 ` * ` 开头的行跳过）——文档注释里用 `**强调**` 是完全正常的。
 *
 * ## 白名单
 * [ALLOWED_FILES] 里的文件是**故意**在字符串里用 Markdown 的：
 * `Llm.kt` 里的那些字符串是发给模型的提示词与工具说明，模型读得懂、也不显示给用户。
 */
class UiTextLintTest {

    private val allowedFiles = setOf(
        // 提示词 / 工具说明，发给模型看的，不显示在界面上
        "Llm.kt",
        // 这个文件本身就是 Markdown 解析器，测试数据里当然有 **
        "Markdown.kt",
        // 里面有拼接给模型的「交接口径」提示词（AI 读完图后回灌给对话的那段说明），
        // 那段是发给模型看的，不是界面文字
        "AppViewModel.kt",
    )

    private fun sourceDir(): File {
        // 两种布局都兼容：Gradle 默认把工作目录设在 app/，也可能是仓库根。
        val candidates = listOf(
            File("src/main/java/com/ww/kkexi"),
            File("app/src/main/java/com/ww/kkexi"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到源码目录，试过：${candidates.map { it.absolutePath }}")
    }

    /**
     * 真正要抓的形状：`**被强调的文字**`——两个星号**夹着**非星号内容。
     *
     * 刻意不抓这几种：
     * - `"****"`：API Key 打码用的，全是星号（`[^*]+` 匹配不到东西，自然放过）；
     * - 注释里的 `**强调**`：文档注释里这么写完全正常。
     */
    private val markdownBold = Regex("""\*\*[^*\s][^*]*\*\*""")

    /** 判断一行是不是「字符串字面量里含 Markdown 加粗」。 */
    private fun isOffending(line: String): Boolean {
        val trimmed = line.trimStart()
        // 注释行跳过
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return false
        // 只看引号之后的部分（避免把行尾注释里的星号算进来）
        val firstQuote = line.indexOf('"')
        if (firstQuote < 0) return false
        return markdownBold.containsMatchIn(line.substring(firstQuote))
    }

    @Test
    fun no_markdown_asterisks_in_user_facing_strings() {
        val offenders = ArrayList<String>()

        for (file in sourceDir().listFiles { f -> f.extension == "kt" }.orEmpty()) {
            if (file.name in allowedFiles) continue
            file.readLines().forEachIndexed { index, line ->
                if (isOffending(line)) {
                    offenders += "${file.name}:${index + 1}  ${line.trim()}"
                }
            }
        }

        assertTrue(
            "Text() 不解析 Markdown，字符串里的 ** 会原样显示在界面上。\n" +
                "改成纯文本，或者把内容交给 Markdown 渲染组件。\n" +
                "命中的行：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun the_lint_itself_can_detect_an_offending_line() {
        // 自检：确保上面那条测试不是「永远通过」的空壳
        assertTrue(isOffending("""            Text("这是**加粗**的写法")"""))
        assertTrue(isOffending("""                "跨行拼接的**加粗**" +"""))
        // 注释不算
        assertTrue(!isOffending("""    // 注释里写 **强调** 没问题"""))
        assertTrue(!isOffending("""     * 文档注释里写 **强调** 也没问题"""))
        // 没有星号的自然不算
        assertTrue(!isOffending("""            Text("普通文字")"""))
        // API Key 打码用的纯星号不算 Markdown（这是真踩过的误报）
        assertTrue(!isOffending("""            else -> key.take(3) + "****" + key.takeLast(4)"""))
        assertTrue(!isOffending("""            key.length <= 8 -> "****\""""))
    }
}
