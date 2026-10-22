package com.ww.kkexi

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.InputStream
import java.time.LocalDate

/**
 * AI 助手：一个聊天页搞定所有事。
 *
 * ## 这一版是照着成熟聊天 App 的做法重做的
 *
 * 用户原话：「你不能把它设计成日常使用的 AI 软件的聊天页面的风格吗」。所以参考了
 * [leavesCZY/compose_chat](https://github.com/leavesCZY/compose_chat)（Apache-2.0，
 * Jetpack Compose 写的 IM App，391 星）的几个关键设计：
 *
 * 1. **消息模型区分类型**：它的 `Message` 是 sealed class，TextMessage 与 ImageMessage
 *    分开，图片消息自带预览地址。我照这个把 [ChatMessage] 加上了 `imagePath`——
 *    这解决了用户说的「图片我就看不见上下文」：现在图片作为消息**常驻在会话里**。
 * 2. **输入栏的构成**：它的 `InputSelectorBar` 是「选择器图标 … 发送按钮」两段式，
 *    附件由独立的 `MediaPickerTable` 选。我照这个把原来的 `＋` 改成图标按钮 +
 *    附件先进入**待发区**（见 [PendingAttachment]），解决「一点图片就自动发送、
 *    没法图文一起发」。
 * 3. **发送按钮做成带底色的圆角块**，而不是我原来那个裸字符 `↑`。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(
    state: UiState,
    onSend: (String) -> Unit,
    onAttachImage: (Uri) -> Unit,
    onAttachFile: (Uri, String) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onSendWithAttachments: (String) -> Unit,
    onAcceptCandidates: (List<LlmClient.Candidate>) -> Unit,
    onDismissCandidates: () -> Unit,
    onUpdateCandidate: (Int, LlmClient.Candidate) -> Unit,
    onRemoveCandidate: (Int) -> Unit,
    onSetEffort: (String) -> Unit,
    onClearChat: () -> Unit,
    onToggleReasoning: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var showEffortSheet by remember { mutableStateOf(false) }
    // 清空对话前的确认（不可撤销，必须挡住误触）
    var showClearConfirm by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // 「回到最新」需要一个作用域来启动滚动动画
    val scope = rememberCoroutineScope()

    /**
     * 键盘（IME）当前高度。
     *
     * 加到自动滚动的触发条件里：键盘弹出时可视区变矮，
     * 如果只按「消息条数」触发滚动，最后一条回复就会被输入区挡住
     * ——用户截图反馈的「键盘挡住了输入界面」实际是这个现象。
     */
    val imeHeight = WindowInsets.ime.getBottom(LocalDensity.current)

    /**
     * 当前是否已经滚到最底部。
     *
     * 用途：滚上去了就浮一个「回到最新」按钮。
     * 长对话里往上翻看历史之后，手指得往上划很多次才能回到最新一条——
     * 聊天类应用的通行做法就是给这么一个入口。
     */
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            // 没有条目，或最后一条的底边已经到达/超过可视区底边（留 12px 容差）
            last == null ||
                (last.index >= info.totalItemsCount - 1 &&
                    last.offset + last.size <= info.viewportEndOffset + 12)
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        // 注意：这里**不再直接发送**，而是放进待发区等用户确认。
        if (uri != null) onAttachImage(uri)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onAttachFile(uri, uri.lastPathSegment.orEmpty())
    }

    LaunchedEffect(
        state.chat.size,
        state.streamingText,
        state.pendingAttachments.size,
        state.aiCandidates.size,
        imeHeight,
    ) {
        // 列表里除了消息，空会话时最前面还有一张问候卡、候选出现时最后面还有一张核对卡，
        // 都要算进索引，否则永远差一条、滚不到底（最后一条被输入区压住）。
        val greetingOffset = if (state.chat.isEmpty() && !state.aiBusy) 1 else 0
        val count = state.chat.size + greetingOffset + if (state.aiBusy) 1 else 0
        if (count > 0) {
            // scrollToItem 而不是 animateScrollToItem：流式输出时正文每几毫秒就变一次，
            // 动画会被不停打断，反而卡在半路。直接定位更稳。
            listState.scrollToItem(count - 1)
        }
    }

    Column(Modifier.fillMaxSize()) {
        // ---------------------------------------------------------- 顶栏
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("AI 助手", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    val subtitle = buildString {
                        append(state.activeProviderName.ifEmpty { "未配置服务商" })
                        if (state.currentEffort.isNotEmpty()) append(" · 思考 ${state.currentEffort}")
                    }
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.chat.isNotEmpty()) {
                    // 「清空」是不可撤销的（对话记录直接从库里删）。加一道确认——
                    // 我自己在调试时就误点过一次，一整个会话没了。
                    TextButton(onClick = { showClearConfirm = true }) { Text("清空") }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))

        if (!state.aiEnabled || state.aiProviders.none { it.configured }) {
            Surface(color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "AI 未启用，或还没配置服务商。日程功能不受影响。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onOpenSettings) { Text("去设置") }
                }
            }
        }

        // ---------------------------------------------------------- 消息列表
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.chat.isEmpty() && !state.aiBusy) {
                item { GreetingPanel(state, onOpenSettings) }
            }

            items(state.chat.size) { index ->
                val message = state.chat[index]
                if (message.fromUser) {
                    UserMessage(message)
                } else {
                    AssistantMessage(message, state.showReasoning, onToggleReasoning)
                }
            }

            if (state.aiBusy) {
                item {
                    Column(Modifier.fillMaxWidth()) {
                        if (state.streamingTool.isNotEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(bottom = 6.dp),
                            ) {
                                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(state.streamingTool, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (state.streamingReasoning.isNotEmpty()) {
                            ReasoningPanel(state.streamingReasoning, state.showReasoning, onToggleReasoning)
                            Spacer(Modifier.height(8.dp))
                        }
                        if (state.streamingText.isNotEmpty()) {
                            Bubble(isUser = false) { MarkdownText(state.streamingText) }
                        } else if (state.streamingReasoning.isEmpty() && state.streamingTool.isEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("正在思考…", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            // 候选核对卡：读图/粘贴解析出来的条目在这里逐条确认。
            //
            // ## 踩过的坑（用户反馈：「发送日程图片没有弹出候选」，而 AI 说读出了 15 条）
            // 我在重做聊天页时**把这块整个删掉了却没补回来**——
            // `UiState.aiCandidates` 一直有值、模型也如实汇报了条数，
            // 但界面上**没有任何地方渲染它**，所以用户什么都看不见。
            // 教训：删界面代码时不能只看"新界面能编译"，要回头核对每一个 UiState 字段
            // 是否仍有人消费。这类"字段没人读"的静默失效编译器不会报。
            if (state.aiCandidates.isNotEmpty()) {
                item {
                    CandidatesReview(
                        state = state,
                        onAccept = onAcceptCandidates,
                        onDismiss = onDismissCandidates,
                        onUpdate = onUpdateCandidate,
                        onRemove = onRemoveCandidate,
                    )
                }
            }
        }

        // ---------------------------------------------------------- 输入区
        // 「回到最新」：只在滚离底部时出现，不占常驻空间
        if (!atBottom && state.chat.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.clickable {
                        scope.launch { listState.animateScrollToItem(maxOf(0, state.chat.size - 1)) }
                    },
                ) {
                    Text(
                        "↓ 回到最新",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }

        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Column(
                // 键盘适配的踩坑记录（三次）：
                //   ① 不加 imePadding → 键盘直接盖住输入框（用户截图反馈「键盘挡住了输入界面」）；
                //   ② 加了 imePadding 之后又出现「回复被压扁、中间空一块」，那次是因为
                //      它和 LazyColumn 的 weight(1f) 抢高度。正确做法是：
                //      LazyColumn 吃 weight(1f)、输入区自己吃 imePadding，两者不冲突；
                //   ③ **imePadding 已经挪到外层 Scaffold 上了**（见 MainActivity），
                //      所以这里不能再加一次——否则键盘弹起时会被垫两次，输入框离键盘老远。
                //      挪出去的原因：只在输入区垫的话，底部导航栏仍然被键盘压住，
                //      用户打字时切不了 Tab。
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                // 待发附件（缩略图 + 可删）——这就是「图片先选、再配文字、一起发」
                if (state.pendingAttachments.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (attachment in state.pendingAttachments) {
                            PendingAttachmentChip(
                                attachment = attachment,
                                onRemove = { onRemoveAttachment(attachment.id) },
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.Bottom) {
                    // 附件选择：图标按钮，不再是裸字符「＋」
                    Box {
                        var showAttachSheet by remember { mutableStateOf(false) }
                        IconButton(
                            onClick = { showAttachSheet = true },
                            modifier = Modifier.size(44.dp),
                        ) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = "添加图片或文件",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (showAttachSheet) {
                            AttachSheet(
                                visionReady = state.visionReady,
                                onDismiss = { showAttachSheet = false },
                                onPickImage = {
                                    showAttachSheet = false
                                    imagePicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                onPickFile = {
                                    showAttachSheet = false
                                    filePicker.launch(
                                        arrayOf(
                                            "image/*", "text/*", "application/json",
                                            "application/pdf",
                                            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                        )
                                    )
                                },
                            )
                        }
                    }

                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("发消息，或粘贴一段课表") },
                        modifier = Modifier.weight(1f).heightIn(min = 46.dp, max = 130.dp),
                        shape = RoundedCornerShape(20.dp),
                        maxLines = 5,
                    )

                    Spacer(Modifier.width(8.dp))

                    // 发送按钮：带底色的圆角块（学 compose_chat 的 SendButton 做法）
                    val canSend = (input.isNotBlank() || state.pendingAttachments.isNotEmpty()) && !state.aiBusy
                    val bg = if (canSend) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                    }
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(bg)
                            .clickable(enabled = canSend) {
                                if (state.pendingAttachments.isEmpty()) {
                                    onSend(input.trim())
                                } else {
                                    onSendWithAttachments(input.trim())
                                }
                                input = ""
                            }
                            .padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "发送",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            "发送",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }

                // 底部：只留思考等级 + 一行说明，不再堆功能按钮
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { showEffortSheet = true }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Psychology,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            "思考等级 ${effortShort(state.currentEffort)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (state.visionReady) "可读图" else "读图未启用",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清空对话？") },
            text = {
                Text(
                    "这条会话的全部消息会从本机删除，无法撤销。\n" +
                        "日程数据不受影响——清掉的只是聊天记录。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(onClick = {
                    onClearChat()
                    showClearConfirm = false
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            },
        )
    }

    if (showEffortSheet) {
        ModalBottomSheet(
            onDismissRequest = { showEffortSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                Text("思考等级", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    // 这里不能写 Markdown 星号：Text 不解析，会把 * 原样显示出来。
                    "实测结论：这个参数真的生效——同一道日期推理题，none 档思考 0 tokens，" +
                        "low 档 385，medium 档 732。值域来自服务端返回的合法值列表。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                for (effort in state.availableEfforts) {
                    FilterChip(
                        selected = effort == state.currentEffort,
                        onClick = {
                            onSetEffort(effort)
                            showEffortSheet = false
                        },
                        label = { Text(AiProvider.effortLabel(effort)) },
                        modifier = Modifier.padding(end = 6.dp, bottom = 6.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "注意：`off` 不是合法值（服务端会 422），要关掉思考请用 `none`。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun effortShort(effort: String): String = if (effort.isEmpty()) "不指定" else effort

/** 附件来源选择：图片 / 文件。学 compose_chat 把选择器单独做成一层。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachSheet(
    visionReady: Boolean,
    onDismiss: () -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("添加内容", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                if (visionReady) "图片会用视觉模型读图；文本文件会当文字解析。选完可以先配一句话再发。"
                else "还没有勾选「支持图片」的服务商，选图片会提示你去设置。文本文件不受影响。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            AttachOption(
                icon = { Icon(Icons.Filled.Image, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                title = "图片",
                detail = "课表截图、作息表截图、通知截图",
                onClick = onPickImage,
            )
            Spacer(Modifier.height(10.dp))
            AttachOption(
                icon = { Icon(Icons.Filled.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                title = "文本文件",
                // 这里是普通 Text，不渲染 Markdown，所以不能写 ** 号（会原样显示）。
                // 之前写成「PDF、PPT、Word 目前**读不了**」，界面上真的把星号显示出来了。
                detail = "txt / md / csv / json 直接读；PDF、PPT、Word 目前读不了，会明确告诉你",
                onClick = onPickFile,
            )
        }
    }
}

@Composable
private fun AttachOption(
    icon: @Composable () -> Unit,
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 待发附件的缩略图（图片显示真图，其他显示文件图标），右上角可删。 */
@Composable
private fun PendingAttachmentChip(attachment: PendingAttachment, onRemove: () -> Unit) {
    Box(Modifier.size(64.dp)) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (attachment.isImage) {
                LocalImage(attachment.uri, contentDescription = attachment.displayName)
            } else {
                Column(
                    Modifier.fillMaxSize().padding(4.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Filled.Description,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        attachment.displayName.takeLast(10),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
        // 删除角标
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .size(20.dp)
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f), CircleShape)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "移除附件",
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

/** 从 Uri 显示本地图片，不引第三方图片库（AGENTS.md 硬约束 7「依赖克制」）。 */
@Composable
private fun LocalImage(uri: Uri, contentDescription: String?) {
    val painter = rememberLocalImagePainter(uri)
    if (painter == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp)
        }
    } else {
        Image(
            painter = painter,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * 把 Uri 解成 [BitmapPainter]。
 *
 * 为什么不用 Coil/Glide：只为显示一张缩略图引一个图片库不划算，而且这些库默认还带
 * 网络与磁盘缓存（这个 App 不需要）。这里手写「后台解码 + 采样到小尺寸」，
 * 代码量可控，也不会有额外依赖。
 */
@Composable
private fun rememberLocalImagePainter(uri: Uri): BitmapPainter? {
    val context = androidx.compose.ui.platform.LocalContext.current
    var painter by remember(uri) { mutableStateOf<BitmapPainter?>(null) }

    LaunchedEffect(uri) {
        val bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                // 第一遍只读尺寸
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                // 采样到最长边 ≤ 640，避免把整张原图读进内存
                val options = BitmapFactory.Options().apply {
                    inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, 640)
                }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }
            }.getOrNull()
        }
        painter = bitmap?.let { BitmapPainter(it.asImageBitmap()) }
    }
    return painter
}

private fun calculateSampleSize(width: Int, height: Int, target: Int): Int {
    if (width <= 0 || height <= 0) return 1
    var sample = 1
    var longest = maxOf(width, height)
    while (longest / 2 >= target) {
        sample *= 2
        longest /= 2
    }
    return sample
}

/** 从文件路径显示图片（历史消息里的图片）。 */
@Composable
private fun LocalFileImage(path: String, contentDescription: String?) {
    val painter = rememberFileImagePainter(path)
    if (painter == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp)
        }
    } else {
        Image(
            painter = painter,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun rememberFileImagePainter(path: String): BitmapPainter? {
    var painter by remember(path) { mutableStateOf<BitmapPainter?>(null) }
    LaunchedEffect(path) {
        val bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val file = File(path)
                if (!file.exists()) return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                val options = BitmapFactory.Options().apply {
                    inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, 720)
                }
                BitmapFactory.decodeFile(path, options)
            }.getOrNull()
        }
        painter = bitmap?.let { BitmapPainter(it.asImageBitmap()) }
    }
    return painter
}

// ==================================================================== 消息气泡

@Composable
private fun UserMessage(message: ChatMessage) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(
            Modifier.fillMaxWidth(0.86f),
            horizontalAlignment = Alignment.End,
        ) {
            // 图片消息常驻显示——这是用户说的「图片看不见上下文」的解法
            if (message.imagePath.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.width(180.dp).height(180.dp).padding(bottom = 6.dp),
                ) {
                    LocalFileImage(message.imagePath, message.attachmentLabel)
                }
            }
            if (!message.text.isEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                ) {
                    SelectionContainer {
                        Text(
                            message.text,
                            Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantMessage(message: ChatMessage, showReasoning: Boolean, onToggleReasoning: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        if (message.reasoning.isNotEmpty()) {
            ReasoningPanel(message.reasoning, showReasoning, onToggleReasoning)
            Spacer(Modifier.height(8.dp))
        }
        Bubble(isUser = false) { MarkdownText(message.text) }
        // token 用量：贴在气泡右下角，小字，不抢视线（用户反馈要能看到消耗）。
        if (message.hasUsage) {
            Row(
                Modifier.fillMaxWidth().padding(top = 3.dp, end = 4.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    buildString {
                        append("↑${compactTokens(message.promptTokens)}")
                        append(" ↓${compactTokens(message.completionTokens)}")
                        if (message.reasoningTokens > 0) append(" （思考 ${compactTokens(message.reasoningTokens)}）")
                        append(" · 共 ${compactTokens(message.totalTokens)}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 1234 → "1.2k"，避免长数字把状态栏挤乱。 */
private fun compactTokens(value: Int): String =
    if (value < 1000) "$value" else String.format(java.util.Locale.ROOT, "%.1fk", value / 1000.0)

@Composable
private fun Bubble(isUser: Boolean, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (isUser) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            },
            shape = if (isUser) {
                RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
            } else {
                RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
            },
            modifier = Modifier.fillMaxWidth(if (isUser) 0.86f else 0.95f),
        ) {
            SelectionContainer {
                Box(Modifier.padding(horizontal = 13.dp, vertical = 10.dp)) { content() }
            }
        }
    }
}

@Composable
private fun ReasoningPanel(text: String, expanded: Boolean, onToggle: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { onToggle() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (expanded) "▾ 思考过程" else "▸ 思考过程（${text.length} 字）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 候选核对卡：解析出来的条目逐条勾选、确认后才写库。
 *
 * 这是「绝不自动入库」这条硬约束的界面落点——读图实测会串列（把某一列的课
 * 归到相邻的星期），所以必须由人过一遍。
 */
@Composable
private fun CandidatesReview(
    state: UiState,
    onAccept: (List<LlmClient.Candidate>) -> Unit,
    onDismiss: () -> Unit,
    onUpdate: (Int, LlmClient.Candidate) -> Unit,
    onRemove: (Int) -> Unit,
) {
    val selected = remember(state.aiCandidates) {
        mutableStateOf(state.aiCandidates.indices.toSet())
    }
    val warned = state.aiCandidates.count { it.warnings.isNotEmpty() }
    var editingIndex by remember { mutableStateOf<Int?>(null) }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "待确认的 ${state.aiCandidates.size} 条",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (warned > 0) {
                    Text(
                        "$warned 条有疑问",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "逐条核对后才会写进日程库。识别错了直接点这一条改，不用重发。" +
                    "读图会串列（把某一列的课归到相邻的星期），请对着原图核一遍。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            for ((index, candidate) in state.aiCandidates.withIndex()) {
                CandidateRow(
                    candidate = candidate,
                    checked = index in selected.value,
                    onCheckedChange = { isOn ->
                        selected.value = if (isOn) selected.value + index else selected.value - index
                    },
                    onEdit = { editingIndex = index },
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = selected.value.isNotEmpty(),
                    onClick = {
                        onAccept(state.aiCandidates.filterIndexed { index, _ -> index in selected.value })
                    },
                ) { Text("写入选中的 ${selected.value.size} 条") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDismiss) { Text("全部丢弃") }
            }
        }
    }

    val editing = editingIndex
    if (editing != null && editing in state.aiCandidates.indices) {
        EditCandidateDialog(
            initial = state.aiCandidates[editing],
            categories = state.categories,
            onDismiss = { editingIndex = null },
            onSave = {
                onUpdate(editing, it)
                editingIndex = null
            },
            onDelete = {
                onRemove(editing)
                editingIndex = null
            },
        )
    }
}

/**
 * 改一条候选。
 *
 * 这是「图片识别错误时，无法先修改再导入」的解法：**在写库之前**就能改
 * 标题、类别、日期、起止时刻、地点、描述。
 * 时间用两个 `HH:mm` 输入框——与新建行程一致，不出现「第几节」。
 */
@Composable
private fun EditCandidateDialog(
    initial: LlmClient.Candidate,
    categories: List<String>,
    onDismiss: () -> Unit,
    onSave: (LlmClient.Candidate) -> Unit,
    onDelete: () -> Unit,
) {
    var title by remember { mutableStateOf(initial.title) }
    var dateText by remember { mutableStateOf(initial.date.toString()) }
    var startText by remember { mutableStateOf(initial.startLabel) }
    var endText by remember { mutableStateOf(initial.endLabel) }
    var location by remember { mutableStateOf(initial.location) }
    var note by remember { mutableStateOf(initial.note) }
    var categoryLabel by remember { mutableStateOf(initial.kind.defaultCategory) }
    var isPointMode by remember { mutableStateOf(initial.isPoint) }

    val startMinute = Timetable.parseMinute(startText)
    val endMinute = Timetable.parseMinute(endText)
    val date = runCatching { LocalDate.parse(dateText.trim()) }.getOrNull()
    val timeValid = if (isPointMode) {
        startMinute != null
    } else {
        startMinute != null && endMinute != null && endMinute > startMinute
    }
    val valid = title.isNotBlank() && date != null && timeValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("改这一条") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))

                Text("类别", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    for (option in categories) {
                        FilterChip(
                            selected = categoryLabel == option,
                            onClick = { categoryLabel = option },
                            label = { Text(option) },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = dateText,
                    onValueChange = { dateText = it },
                    label = { Text("日期（YYYY-MM-DD）") },
                    singleLine = true,
                    isError = date == null,
                    supportingText = if (date == null) {
                        { Text("要写成 2026-10-15 这种格式") }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = !isPointMode,
                        onClick = { isPointMode = false },
                        label = { Text("一段时间") },
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    FilterChip(
                        selected = isPointMode,
                        onClick = { isPointMode = true },
                        label = { Text("具体时刻") },
                    )
                }
                if (isPointMode) {
                    OutlinedTextField(
                        value = startText,
                        onValueChange = { startText = it },
                        label = { Text("时刻") },
                        placeholder = { Text("23:59") },
                        singleLine = true,
                        isError = startMinute == null,
                        modifier = Modifier.width(140.dp),
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = startText,
                            onValueChange = { startText = it },
                            label = { Text("开始") },
                            singleLine = true,
                            isError = startMinute == null,
                            modifier = Modifier.width(118.dp),
                        )
                        Text("—", Modifier.padding(horizontal = 6.dp))
                        OutlinedTextField(
                            value = endText,
                            onValueChange = { endText = it },
                            label = { Text("结束") },
                            singleLine = true,
                            isError = endMinute == null || (startMinute != null && endMinute <= startMinute),
                            modifier = Modifier.width(118.dp),
                        )
                    }
                }
                if (!timeValid) {
                    Text(
                        if (isPointMode) {
                            "时间要写成 HH:mm（例如 23:59）。"
                        } else {
                            "时间要写成 HH:mm（例如 14:30），且结束晚于开始。"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("地点") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("描述") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = valid,
                onClick = {
                    onSave(
                        initial.copy(
                            title = title.trim(),
                            kind = categoryToKind(categoryLabel),
                            date = date!!,
                            startMinute = startMinute!!,
                            endMinute = if (isPointMode) startMinute!! else endMinute!!,
                            isPoint = isPointMode,
                            location = location.trim(),
                            note = note.trim(),
                            // 手动改过之后，原来的「折算/看不清」警告已经不适用了。
                            warnings = initial.warnings.filterNot {
                                it.contains("折算") || it.contains("占位") || it.contains("1 小时")
                            },
                        )
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) {
                    Text("不要这条", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

/** 类别标签 → 行为类型。与新建对话框同一套映射。 */
private fun categoryToKind(label: String): EventKind = when (label) {
    "课程" -> EventKind.COURSE
    "截止" -> EventKind.DEADLINE
    "答疑" -> EventKind.OFFICE_HOUR
    else -> EventKind.ACTIVITY
}

@Composable
private fun CandidateRow(
    candidate: LlmClient.Candidate,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
) {
    val suspicious = candidate.warnings.isNotEmpty()
    Surface(
        color = if (suspicious) {
            MaterialTheme.colorScheme.error.copy(alpha = 0.10f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        },
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            // 整张卡可点 = 进去改。用户要求「识别错了要先能改再导入」。
            .clickable(onClick = onEdit),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
            Column(Modifier.padding(top = 8.dp, bottom = 8.dp, end = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        candidate.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "改",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                // 只显示日期与起止时刻，**不显示「第几节」**。
                // 用户明确要求：「删除时间轴中和 AI 助手中所有第几节课的概念，用这个处处是 bug」。
                val rule = candidate.recurrence
                // 有周次规则时，卡片上的日期应该是「规则的第一周那一天」，
                // 而不是模型随手给的某一周日期——否则「第 5-16 周」下面写着 10/19（第 7 周）会自相矛盾。
                val shownDate = if (rule == null) {
                    candidate.date
                } else {
                    val firstWeek = rule.weeks.minOrNull() ?: rule.startWeek
                    Semester.mondayOf(firstWeek)
                        .plusDays((rule.dayOfWeek.value - 1).toLong())
                }
                Text(
                    "${DateTimeExt.formatDateWithWeek(shownDate)} · ${candidate.timeText}" +
                        if (rule != null) " 起" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
                rule?.let {
                    Text(
                        "整学期 · ${it.parity.descZh} · 第 ${it.startWeek}-${it.endWeek} 周",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (candidate.location.isNotEmpty()) {
                    Text("地点：${candidate.location}", style = MaterialTheme.typography.labelSmall)
                }
                // 用户要求「AI 通过我发的图片同时写入描述的内容」——描述要显示出来给他核。
                if (candidate.note.isNotEmpty()) {
                    Text(
                        "描述：${candidate.note}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (candidate.evidence.isNotEmpty()) {
                    Text(
                        "依据：「${candidate.evidence}」",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                for (warning in candidate.warnings) {
                    Text(
                        "⚠ $warning",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** 首屏问候 + 今天安排 + 能怎么问。 */
@Composable
private fun GreetingPanel(state: UiState, onOpenSettings: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "嗨！今天想聊些什么？",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 14.dp),
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(14.dp),
        ) {
            Column(Modifier.padding(14.dp)) { MarkdownText(state.greeting) }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "可以这样问：",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        for (hint in listOf(
            "我这周三下午有课吗？",
            "把今天的线性代数课取消",
            "下周还有哪些作业要交？",
            "把周四的高数调到周五 14:30-16:00",
        )) {
            Text("· $hint", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 3.dp))
        }
        if (!state.visionReady) {
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onOpenSettings) { Text("想让 AI 读课表截图？先去设置里加一个支持图片的服务商") }
        }
    }
}
