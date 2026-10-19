package com.ww.kkexi

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * 极简 Markdown 渲染器。
 *
 * 为什么自己写而不引库（AGENTS.md 硬约束 7「依赖克制」）：
 * 模型实际输出的 Markdown 只用到很少几种语法，而一个完整的 Markdown 库
 * 会带进大量本项目用不到的解析规则与资源。这里只支持真正会出现的：
 *
 *   - `#` / `##` / `###` 标题      → 按级别放大加粗
 *   - `-` / `*` / `1.` 列表        → 前面加「•」并缩进
 *   - `**粗体**`、`*斜体*`、`` `行内代码` ``
 *   - ``` 代码块 ```               → 等宽字体 + 底色
 *   - `| a | b |` 表格             → 对齐成纯文本表格（等宽）
 *   - `>` 引用                     → 左侧竖线 + 次要色
 *
 * **不支持**嵌套列表、链接跳转、图片、HTML——真需要时再加，不要提前做。
 */
@Composable
fun MarkdownText(text: String) {
    val blocks = remember(text) { parseBlocks(text) }
    Column(Modifier.fillMaxWidth()) {
        for (block in blocks) {
            when (block) {
                is Block.Heading -> Text(
                    inline(block.text),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.bodyLarge
                    },
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                )

                is Block.Bullet -> Row(Modifier.padding(vertical = 1.dp)) {
                    Text("• ", style = MaterialTheme.typography.bodyMedium)
                    Text(inline(block.text), style = MaterialTheme.typography.bodyMedium)
                }

                is Block.Numbered -> Row(Modifier.padding(vertical = 1.dp)) {
                    Text("${block.number}. ", style = MaterialTheme.typography.bodyMedium)
                    Text(inline(block.text), style = MaterialTheme.typography.bodyMedium)
                }

                is Block.Quote -> Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Spacer(
                        Modifier
                            .width(3.dp)
                            .height(IntrinsicHeightOfQuote)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        inline(block.text),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is Block.Code -> Text(
                    block.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                            RoundedCornerShape(4.dp),
                        )
                        .padding(8.dp),
                )

                is Block.Table -> ComposeTable(block.header, block.rows)

                is Block.Paragraph -> Text(
                    inline(block.text),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

/** 引用块的竖线高度：给一个够用的定值，避免为一行文字做 intrinsic 测量。 */
private val IntrinsicHeightOfQuote = 20.dp

/** 解析后的块类型。 */
private sealed interface Block {
    data class Heading(val level: Int, val text: String) : Block
    data class Bullet(val text: String) : Block
    data class Numbered(val number: Int, val text: String) : Block
    data class Quote(val text: String) : Block
    data class Code(val text: String) : Block
    data class Table(val header: List<String>, val rows: List<List<String>>) : Block
    data class Paragraph(val text: String) : Block
}

private val bulletRegex = Regex("""^\s*[-*+]\s+(.*)$""")
private val numberedRegex = Regex("""^\s*(\d+)[.、)]\s+(.*)$""")

/**
 * 把 Markdown 切成块。
 *
 * 表格识别：连续若干行都以 `|` 开头开头与结尾，且第二行是分隔行（`---|---`）。
 * 渲染方式是把每列按显示宽度补空格对齐——手机屏幕窄，等宽表格比花哨的边框更实用。
 */
private fun parseBlocks(text: String): List<Block> {
    val lines = text.replace("\r\n", "\n").split("\n")
    val blocks = ArrayList<Block>()
    var index = 0

    while (index < lines.size) {
        val line = lines[index]

        // 代码块
        if (line.trimStart().startsWith("```")) {
            index++
            val buffer = StringBuilder()
            while (index < lines.size && !lines[index].trimStart().startsWith("```")) {
                buffer.appendLine(lines[index])
                index++
            }
            index++ // 跳过收尾的 ```
            blocks += Block.Code(buffer.toString().trimEnd())
            continue
        }

        // 表格：当前行是表头且下一行是分隔行
        if (line.trim().startsWith("|") && index + 1 < lines.size && isSeparatorRow(lines[index + 1])) {
            val header = splitRow(line)
            val rows = ArrayList<List<String>>()
            index += 2
            while (index < lines.size && lines[index].trim().startsWith("|")) {
                rows += splitRow(lines[index])
                index++
            }
            blocks += Block.Table(header, rows)
            continue
        }

        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> index++

            trimmed.startsWith("#") -> {
                val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 3)
                blocks += Block.Heading(level, trimmed.drop(level).trim())
                index++
            }

            trimmed.startsWith(">") -> {
                blocks += Block.Quote(trimmed.drop(1).trim())
                index++
            }

            bulletRegex.matches(line) -> {
                blocks += Block.Bullet(bulletRegex.find(line)!!.groupValues[1])
                index++
            }

            numberedRegex.matches(line) -> {
                val match = numberedRegex.find(line)!!
                blocks += Block.Numbered(match.groupValues[1].toIntOrNull() ?: 1, match.groupValues[2])
                index++
            }

            else -> {
                blocks += Block.Paragraph(trimmed)
                index++
            }
        }
    }
    return blocks
}

private fun isSeparatorRow(line: String): Boolean {
    val trimmed = line.trim()
    if (!trimmed.startsWith("|")) return false
    return trimmed.all { it == '|' || it == '-' || it == ':' || it == ' ' }
}

private fun splitRow(line: String): List<String> =
    line.trim().trim('|').split('|').map { it.trim() }

/**
 * 单元格内容按「显示宽度」对齐时中日韩字符算两个宽度，其余算一个。
 *
 * 注意：这个函数现在只被测试用到——表格改成真正的 Compose 网格之后不再需要手动补空格。
 * 保留它是因为 [MarkdownTest] 有断言在验证这个宽度规则，而规则本身仍然是对的。
 */
internal fun displayWidth(text: String): Int = text.sumOf { if (it.code > 0x2E80) 2 else 1 }

private fun pad(text: String, width: Int): String =
    text + " ".repeat((width - displayWidth(text)).coerceAtLeast(0))

/**
 * 把表格渲染成**真正的组件网格**。
 *
 * ## 踩过的坑（用户截图反馈：表格是坏的）
 * 第一版是把表格对齐成一个等宽字符串，然后整个塞进一个 `Text`。
 * 这在手机上必坏：屏幕宽度不够，`Text` 自动换行，而换行是按**字符**断的，
 * 不认「列」——结果第二列的内容被折到下一行、右边只剩竖线残渣，
 * 用户看到的正是那个样子。
 *
 * 正确做法是每个单元格一个 `Text`，用 `weight` 分配列宽，换行发生在单元格内部，
 * 列与列之间的边界由布局保证，永远不会错位。
 */
@Composable
private fun ComposeTable(header: List<String>, rows: List<List<String>>) {
    val columnCount = maxOf(header.size, rows.maxOfOrNull { it.size } ?: 0)
    if (columnCount == 0) return

    // 列宽按内容长度分配。上下限都收窄了些：
    // 下限原来给 4，会让「地点」这种短列也占掉一大块，把「事项」挤成两行一个词。
    val weights = (0 until columnCount).map { column ->
        val longest = buildList {
            add(header.getOrElse(column) { "" })
            rows.forEach { add(it.getOrElse(column) { "" }) }
        }.maxOfOrNull { it.length } ?: 1
        longest.coerceIn(3, 16).toFloat()
    }
    val totalWeight = weights.sum()

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                RoundedCornerShape(6.dp),
            )
            .padding(1.dp)
    ) {
        TableRow(
            cells = (0 until columnCount).map { header.getOrElse(it) { "" } },
            weights = weights.map { it / totalWeight },
            isHeader = true,
        )
        for (row in rows) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f),
                thickness = 1.dp,
            )
            TableRow(
                cells = (0 until columnCount).map { row.getOrElse(it) { "" } },
                weights = weights.map { it / totalWeight },
                isHeader = false,
            )
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, weights: List<Float>, isHeader: Boolean) {
    Row(Modifier.fillMaxWidth()) {
        cells.forEachIndexed { index, cell ->
            Text(
                inline(cell),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (isHeader) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isHeader) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f)
                },
                modifier = Modifier
                    .weight(weights.getOrElse(index) { 1f })
                    .padding(horizontal = 6.dp, vertical = 5.dp),
            )
            if (index < cells.size - 1) {
                Spacer(
                    Modifier
                        .width(1.dp)
                        .height(IntrinsicHeightOfTableCell)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f))
                )
            }
        }
    }
}

/** 表格竖线高度：给一个够用的定值，避免为一行文字做 intrinsic 测量。 */
private val IntrinsicHeightOfTableCell = 26.dp

/**
 * 行内的 `**粗体**`、`*斜体*`、`` `代码` ``。
 *
 * 用最朴素的逐个标记扫描，不用正则——因为要处理"标记不配对时原样保留"这种情况，
 * 扫描比正则更不容易出错，也更好读。
 */
private fun inline(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end < 0) {
                    append(text.substring(i))
                    i = text.length
                } else {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                }
            }

            text.startsWith("`", i) -> {
                val end = text.indexOf('`', i + 1)
                if (end < 0) {
                    append(text.substring(i))
                    i = text.length
                } else {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                }
            }

            text.startsWith("*", i) -> {
                val end = text.indexOf('*', i + 1)
                if (end < 0) {
                    append(text.substring(i))
                    i = text.length
                } else {
                    withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                }
            }

            else -> {
                append(text[i])
                i++
            }
        }
    }
}
