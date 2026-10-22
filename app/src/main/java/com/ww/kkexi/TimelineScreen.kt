package com.ww.kkexi

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate

/**
 * 时间轴：按天分组的流水。
 *
 * 版式取舍：一天一个分组标题，组内每一条占一行「节次时间 | 内容」。
 * 高亮当前正在进行的行程——你打开 App 最想知道的就是「现在该去哪」。
 */
@Composable
fun TimelineScreen(
    state: UiState,
    onToggleHistory: () -> Unit,
    onToggleDone: (Session, Boolean) -> Unit,
    onLongPress: (Session) -> Unit,
    onImportSample: () -> Unit,
    onOpenAssistant: () -> Unit,
    onSearch: (String) -> Unit = {},
    onClearSearch: () -> Unit = {},
) {
    // 用户反馈第 2 条：分享给同学的应该是**纯净版**。
    // 库里一条都没有时，不要甩一个空白列表，给一个能立刻上手的引导页。
    if (state.libraryEmpty) {
        EmptyWelcome(onImportSample = onImportSample, onOpenAssistant = onOpenAssistant)
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp),
    ) {
        item { TodayStrip(state) }
        item { SearchBar(state, onSearch, onClearSearch) }

        // 有查询词时，列表换成搜索结果（覆盖整个学期），不再显示时间轴。
        if (state.searchQuery.isNotEmpty()) {
            if (state.searchResults.isEmpty()) {
                item {
                    Text(
                        "没找到匹配「${state.searchQuery}」的日程。\n" +
                            "搜索范围是整个学期，不分大小写。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
                    )
                }
            } else {
                item {
                    Text(
                        "找到 ${state.searchHitCount} 条（整个学期）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(state.searchResults, key = { "s-" + it.date.toString() }) { group ->
                    DaySection(group, state, onToggleDone, onLongPress)
                }
            }
            return@LazyColumn
        }

        if (state.skippedDays > 0) {
            item {
                Text(
                    "今天起 ${state.skippedDays} 天没有安排，下面从 ${DateTimeExt.formatDate(state.anchorDate)} 开始显示。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }

        if (state.deadlines.isNotEmpty()) {
            item {
                DeadlinePin(state.deadlines, onToggleDone, onLongPress)
            }
        }

        if (state.timeline.isEmpty()) {
            item {
                Text(
                    "未来 ${state.timeline.size} 天没有安排。\n点右下角 ＋ 添加，或去「AI 助手」粘贴一段文本让我来拆。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        items(state.timeline, key = { it.date.toString() }) { group ->
            // animateItem：勾掉一个截止项时，卡片会平滑地移动/淡出，而不是瞬间跳一下。
            // 列表里所有条目都在这里，所以增删改都有过渡。
            Box(Modifier.animateItem()) {
                DaySection(group, state, onToggleDone, onLongPress)
            }
        }

        if (state.history.isNotEmpty()) {
            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleHistory() }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (state.historyCollapsed) "▸ 历史（过去 14 天）" else "▾ 历史（过去 14 天）",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${state.history.sumOf { it.sessions.size }} 条",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!state.historyCollapsed) {
                items(state.history, key = { "h-" + it.date.toString() }) { group ->
                    Box(Modifier.animateItem()) {
                        DaySection(group, state, onToggleDone, onLongPress, dimmed = true)
                    }
                }
            }
        }
    }
}

/**
 * 搜索栏。
 *
 * 放在时间轴顶部而不是藏进菜单：日程一多，「找那条叫交作业的」是最常见的动作，
 * 而人记得的是名字、不是日期。
 *
 * 输入即搜（不做防抖的额外按钮）：本地库查询，量小，直接搜比按一下按钮顺手。
 */
@Composable
private fun SearchBar(
    state: UiState,
    onSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onSearch,
            placeholder = { Text("搜索日程（标题、地点、描述、教师）", style = MaterialTheme.typography.bodySmall) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium,
            leadingIcon = { Text("⌕", style = MaterialTheme.typography.titleMedium) },
            trailingIcon = {
                if (state.searchQuery.isNotEmpty()) {
                    Text(
                        "✕",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .clickable(onClick = onClearSearch)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        )
    }
}

/**
 * 纯净版引导页（用户反馈第 2 条）。
 *
 * 场景：把 APK 发给同学，对方装上是空的。不要只丢一个空白列表，
 * 而要明确告诉他「这里现在什么都没有，你可以这样开始」。
 */
@Composable
private fun EmptyWelcome(onImportSample: () -> Unit, onOpenAssistant: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 24.dp, bottom = 100.dp),
    ) {
        Spacer(Modifier.height(20.dp))

        // 一块简单的品牌区：与桌面图标同一套图形语言（日历 + 中间那道「隙」）。
        // 不加图片资源，用几个 Box 画出来，跟着主题走深浅色。
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(52.dp)
                    .background(
                        MaterialTheme.colorScheme.primary,
                        androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(
                        Modifier
                            .width(26.dp)
                            .height(4.dp)
                            .background(MaterialTheme.colorScheme.onPrimary, androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(
                            Modifier
                                .width(10.dp)
                                .height(4.dp)
                                .background(MaterialTheme.colorScheme.onPrimary, androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                        )
                        Box(
                            Modifier
                                .width(10.dp)
                                .height(4.dp)
                                .background(MaterialTheme.colorScheme.onPrimary, androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                        )
                    }
                    Box(
                        Modifier
                            .width(18.dp)
                            .height(4.dp)
                            .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f), androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text("课隙", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "课与课之间的空隙，也替你记住",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "一个本地日程本：课表、行程、截止日期，按时间顺序排好，到点离线提醒。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(28.dp))
        Text("现在这里还是空的", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "三种开始方式，随便挑一个：",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        StepCard(
            index = "1",
            title = "点右下角 ＋ 手动加一条",
            detail = "填日期与起止时间（也可以选「全天」），再加地点。最快。",
        )
        StepCard(
            index = "2",
            title = "去「AI 助手」粘贴一段文字或发张图",
            detail = "把课表、群公告、便签原文整段贴进去，它拆成一条条行程给你逐条确认。" +
                "需要先在设置里填 API Key 并打开 AI 开关（两件都要做）。",
        )
        StepCard(
            index = "3",
            title = "导入一份示例课表",
            detail = "一份虚构的示例课表，只想先看看效果就点这个。不要了可以在设置里清空。",
        )

        Spacer(Modifier.height(12.dp))
        Button(onClick = onImportSample, modifier = Modifier.fillMaxWidth()) {
            Text("导入示例课表")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenAssistant, modifier = Modifier.fillMaxWidth()) {
            Text("去 AI 助手粘贴")
        }

        Spacer(Modifier.height(28.dp))
        Text(
            "数据只存在这台手机上，不上传、不需要账号。设置页可以导出 JSON 备份。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StepCard(index: String, title: String, detail: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
    ) {
        Row(Modifier.padding(12.dp)) {
            Text(
                index,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(24.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 顶部常驻「今日课表条」——打开 App 第一眼要看到今天有什么课。 */
@Composable
private fun TodayStrip(state: UiState) {
    val allToday = state.timeline.firstOrNull { it.date == state.today }?.sessions.orEmpty()
        .filter { it.state != SessionState.CANCELLED }
    val courses = allToday.filter { it.kind == EventKind.COURSE }
    val others = allToday.filter { it.kind != EventKind.COURSE }

    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                "今天是 ${DateTimeExt.formatDate(state.today)} ${DateTimeExt.weekName(state.today)} · " +
                    "${DateTimeExt.weekLabel(state.today)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(4.dp))

            when {
                // 一件都没有：直接说清楚，别让人以为坏了
                allToday.isEmpty() -> Text(
                    "今天没有安排",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )

                // 有课：把课与其它事分开说。
                // 原先这里只统计 COURSE，于是「今天有会议但没课」时它会说「今天没有课」——
                // 字面没错，但会让人以为今天很闲，漏掉那场会。
                else -> {
                    if (courses.isNotEmpty()) {
                        Text(
                            courses.joinToString("　") { session ->
                                "${session.startLabel} ${session.title}" +
                                    if (session.location.isEmpty()) "" else "@${session.location}"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    if (others.isNotEmpty()) {
                        if (courses.isNotEmpty()) Spacer(Modifier.height(2.dp))
                        Text(
                            (if (courses.isEmpty()) "今天没课，但有 " else "另有 ") +
                                "${others.size} 件事：" +
                                others.joinToString("　") { session ->
                                    "${session.startLabel} ${session.title}"
                                },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                        )
                    }
                }
            }
        }
    }
}

/** 截止型事项置顶（Q25 的 B 方案）——便签做不到的那件事。 */
@Composable
private fun DeadlinePin(
    deadlines: List<Session>,
    onToggleDone: (Session, Boolean) -> Unit,
    onLongPress: (Session) -> Unit,
) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(
            "未完成的截止（${deadlines.size}）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
        for (session in deadlines) {
            Card(
                colors = CardDefaults.cardColors(containerColor = KindColors.deadline.copy(alpha = 0.14f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = session.done, onCheckedChange = { onToggleDone(session, it) })
                    Column(
                        Modifier
                            .weight(1f)
                            .clickable { onLongPress(session) }
                            .padding(vertical = 8.dp),
                    ) {
                        Text(session.title, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${DateTimeExt.formatDate(session.date)} ${DateTimeExt.weekName(session.date)} · " +
                                session.timeLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                }
            }
        }
    }
}

@Composable
private fun DaySection(
    group: DayGroup,
    state: UiState,
    onToggleDone: (Session, Boolean) -> Unit,
    onLongPress: (Session) -> Unit,
    dimmed: Boolean = false,
) {
    Column(Modifier.padding(horizontal = 12.dp)) {
        Text(
            group.header,
            style = MaterialTheme.typography.titleMedium,
            color = if (group.date == state.today) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp),
        )
        for (session in group.sessions) {
            SessionRow(
                session = session,
                hasConflict = session.id in group.conflicts,
                onToggleDone = onToggleDone,
                onLongPress = onLongPress,
                dimmed = dimmed,
            )
        }
    }
}

/** 一条行程。长按进入「取消这一次 / 调课 / 补课 / 改整条规则」。 */
@Composable
fun SessionRow(
    session: Session,
    hasConflict: Boolean,
    onToggleDone: (Session, Boolean) -> Unit,
    onLongPress: (Session) -> Unit,
    dimmed: Boolean = false,
) {
    val accent = if (session.state == SessionState.CANCELLED) KindColors.cancelled else KindColors.of(session.kind)
    val alpha = if (dimmed) 0.6f else 1f

    /*
     * 「正在进行」标记。
     *
     * 打开 App 最想知道的往往是「现在我该在哪」——而时间轴上今天那一段里，
     * 正在进行的这一条原本和其他条目长得一样，得自己比对时刻才知道。
     *
     * 只在今天、且状态正常、且当前时刻落在这条的区间内时才标。
     * （它不是每秒刷新的：状态变化/切换页面时会重算，够用。真做每秒 ticking
     * 会带来一次不必要的常驻重组，不值得。）
     */
    val nowMinute = remember { Timetable.nowMinute() }
    val running = session.date == LocalDate.now() &&
        session.state == SessionState.NORMAL &&
        !session.isAllDay &&
        nowMinute >= session.startMinute && nowMinute < session.endMinute

    Card(
        colors = CardDefaults.cardColors(
            // 进行中的卡片底色更亮一档，配合左边的色条，扫一眼就能定位
            containerColor = if (running) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f * alpha)
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clickable { onLongPress(session) },
    ) {
        Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
            // 左侧色条：一眼分清课程/行程/截止。Row 用 IntrinsicSize.Min 后 fillMaxHeight 才有效。
            Box(
                Modifier
                    .width(5.dp)
                    .fillMaxHeight()
                    .background(accent)
            )
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 用户反馈（第三次强调）：**不要「第几节」**。
                    // 「删除时间轴中和 AI 助手中所有第几节课的概念，用这个处处是 bug」。
                    // 所以这里只显示起止时刻——那是真实信息，不需要经过任何换算，
                    // 也就不会有换算错误。
                    //
                    // 用 session.timeLabel 而不是自己拼：全天型日程（作业死线那种）
                    // 会显示成「全天」，而不是难看的「00:00-23:59」。
                    Text(
                        session.timeLabel,
                        style = MaterialTheme.typography.titleMedium,
                        color = accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (running) {
                        Spacer(Modifier.width(8.dp))
                        Badge("进行中", MaterialTheme.colorScheme.primary)
                    }
                    if (hasConflict) {
                        Spacer(Modifier.width(8.dp))
                        Badge("时间冲突", KindColors.conflict)
                    }
                    if (session.state == SessionState.CANCELLED) {
                        Spacer(Modifier.width(8.dp))
                        Badge("已取消", KindColors.cancelled)
                    }
                    if (session.state == SessionState.MOVED) {
                        Spacer(Modifier.width(8.dp))
                        Badge("已调整", KindColors.conflict)
                    }
                    if (session.state == SessionState.EXTRA) {
                        Spacer(Modifier.width(8.dp))
                        Badge("补课", KindColors.activity)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (session.kind == EventKind.DEADLINE) {
                        Checkbox(
                            checked = session.done,
                            onCheckedChange = { onToggleDone(session, it) },
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        session.title,
                        style = MaterialTheme.typography.bodyLarge,
                        textDecoration = if (session.state == SessionState.CANCELLED) TextDecoration.LineThrough else null,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val detail = buildList {
                    if (session.location.isNotEmpty()) add(session.location)
                    if (session.teacher.isNotEmpty()) add(session.teacher)
                }.joinToString(" · ")
                if (detail.isNotEmpty()) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (session.note.isNotEmpty() && session.state != SessionState.NORMAL) {
                    Text(
                        session.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun Badge(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.18f),
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}
