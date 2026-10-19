package com.ww.kkexi

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UI 接线检查：`UiState` 里的字段必须**真的有人在界面上用**。
 *
 * ## 为什么需要这种测试（血的教训）
 * 我重做 AI 助手聊天页时，把「候选核对面板」整块删掉却没补回来。
 * 结果是：`UiState.aiCandidates` 一直有值、模型也如实汇报「读出 15 条候选」，
 * 但**界面上没有任何地方渲染它**——用户点了图片什么都看不见，而且**没有任何报错**。
 *
 * 这类「字段没人读」的静默失效**编译器不会报**（Kotlin 不会因为 data class 的某个字段
 * 没被读而警告），单测也测不到（逻辑层全是对的）。只能靠一条机械的接线检查兜住。
 *
 * 做法很朴素：在界面源码里搜字段名。搜不到就说明这个字段是"写进去没人看"。
 * 它不是万能的（字段被读了但用错了它抓不到），但能挡住"整块 UI 被删掉"这种最严重的退化。
 */
class UiStateWiringTest {

    /**
     * 定位界面源码目录。
     *
     * 注意工作目录有两种可能：`run-tests.ps1` 把 cwd 设成**仓库根**
     * （源码在 `app/src/main/java/...`），而 Gradle 跑测试时 cwd 是**模块根**
     * （源码在 `src/main/java/...`）。两种都试，避免测试因为路径猜错而假失败。
     */
    private val uiSourceDir: File = listOf(
        File("app/src/main/java/com/ww/kkexi"),
        File("src/main/java/com/ww/kkexi"),
    ).firstOrNull { it.isDirectory }
        ?: File("app/src/main/java/com/ww/kkexi")

    private fun allUiSource(): String {
        val files = listOf(
            "AssistantScreen.kt",
            "TimelineScreen.kt",
            "WeekScreen.kt",
            "SettingsScreen.kt",
            "SessionSheet.kt",
            "MainActivity.kt",
        )
        val missing = files.filterNot { File(uiSourceDir, it).exists() }
        assertTrue(
            "找不到界面源文件：$missing\n" +
                "  已尝试的目录 = ${uiSourceDir.absolutePath}（存在=${uiSourceDir.exists()}）\n" +
                "  cwd          = ${File(".").absolutePath}",
            missing.isEmpty(),
        )
        return files.joinToString("\n") { File(uiSourceDir, it).readText() }
    }

    @Test
    fun candidate_panel_is_actually_rendered() {
        val source = allUiSource()
        assertTrue(
            "UiState.aiCandidates 没有被任何界面代码读取——" +
                "这正是「点了图片看不见候选」那个 bug 的形态。请确认 AssistantScreen 里有核对面板。",
            source.contains("aiCandidates"),
        )
        assertTrue(
            "候选的写入动作没有接线（acceptCandidates 没被界面调用）",
            source.contains("acceptCandidates"),
        )
    }

    @Test
    fun attachments_are_actually_rendered() {
        val source = allUiSource()
        assertTrue("待发附件没有在界面上渲染", source.contains("pendingAttachments"))
        assertTrue("图片消息没有在界面上渲染（用户反馈过「图片看不见上下文」）", source.contains("imagePath"))
    }

    @Test
    fun reasoning_and_effort_are_actually_rendered() {
        val source = allUiSource()
        assertTrue("思考过程没有渲染", source.contains("streamingReasoning"))
        assertTrue("思考等级选择没有渲染", source.contains("availableEfforts"))
    }

    @Test
    fun token_usage_is_actually_rendered() {
        val source = allUiSource()
        assertTrue(
            "token 用量没有渲染（用户要求「能看见每次输入输出所消耗的 token」）",
            source.contains("promptTokens") || source.contains("totalTokens"),
        )
    }

    @Test
    fun categories_are_actually_rendered_and_editable() {
        val source = allUiSource()
        assertTrue("自定义类别没有在新建对话框里渲染", source.contains("categories"))
        assertTrue("类别的增删入口没有接线", source.contains("addCategory") && source.contains("removeCategory"))
    }

    @Test
    fun timetable_is_rendered() {
        val source = allUiSource()
        assertTrue("作息表没有被任何界面读取", source.contains("timetable"))
    }
}
