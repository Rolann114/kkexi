package com.ww.kkexi

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDate

/**
 * 长按一条行程后弹出的动作面板。
 *
 * 四个动作对应 Q24 的答复（用户确认「可以」）：
 * 1. 取消这一次 —— 只在时间轴留一条灰字痕迹，不删规则；
 * 2. 调课到… —— 改这一次的日期/节次/地点；
 * 3. 这周不上但补课 —— 取消 + 另加一条补课；
 * 4. 改整条规则… —— 走 Q23 的流程（默认「从本周起生效」，可切「整学期生效」）。
 */
private enum class SheetMode { ACTIONS, MOVE, MAKEUP, SERIES, PROVENANCE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionActionSheet(
    session: Session,
    seriesEvent: Event?,
    // 用当前生效的作息表算「几点到几点」。用户改了作息表，这里自动跟着变。
    timetable: Timetable = Timetable.DEFAULT,
    onDismiss: () -> Unit,
    onCancelOccurrence: (Session) -> Unit,
    onMove: (Session, LocalDate, TimeSpec, String?) -> Unit,
    onMakeup: (Session, LocalDate, TimeSpec) -> Unit,
    onUpdateSeries: (Event, Int, TimeSpec, String) -> Unit,
    onRevert: (Session) -> Unit,
    onDeleteEvent: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var mode by remember { mutableStateOf(SheetMode.ACTIONS) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // 必须自己滚：ModalBottomSheet 的内容槽**不会**自动滚动，
        // 内容比屏幕高时下半截会被裁掉且够不着。
        // 重复课程的面板有 4 个动作（取消这一次 / 调课 / 补课 / 改整条规则）再加
        // 「看一手资料」，配上较长的描述就会超出；一次性行程只有 3 个动作时不明显。
        // 注意：ModalBottomSheet 本身不是滚动容器，所以这里不会出现
        // 「竖向滚动套竖向滚动」那个崩溃（对比 SettingsScreen 里踩过的坑）。
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(session.title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                buildList {
                    // 不显示「第几节」（用户要求去掉），只显示日期与起止时刻。
                    add(DateTimeExt.formatDateWithWeek(session.date))
                    add(session.timeLabel)
                    if (session.location.isNotEmpty()) add(session.location)
                    if (session.teacher.isNotEmpty()) add(session.teacher)
                }.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (session.note.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(session.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (session.source.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("来源：${session.source}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(16.dp))

            when (mode) {
                SheetMode.ACTIONS -> Column {
                    // 面板按「这条是不是重复规则」分化，只给用得上的动作。
                    // 用户反馈：一次性行程上还摆着「取消这一次」「改整条规则」这些，
                    // 「有一些我不需要的内容占了大部分的空间」。
                    val isRecurring = seriesEvent?.recurrence != null

                    if (isRecurring) {
                        ActionButton("取消这一次", "今天不上，其他周照常（会留一条灰字痕迹）") {
                            onCancelOccurrence(session)
                            onDismiss()
                        }
                        ActionButton("调到别的时间…", "改这一次的日期、时间或地点") { mode = SheetMode.MOVE }
                        ActionButton("这周不上但补课", "取消这一次，另加一节补课") { mode = SheetMode.MAKEUP }
                        ActionButton("改整条规则…", "这门课以后都改成别的时间或地点") { mode = SheetMode.SERIES }
                    } else {
                        // 一次性行程：只有「改时间」和「删掉」两件事可做，直接给这两个。
                        ActionButton("改时间或地点…", "改这一条的日期、时间、地点") { mode = SheetMode.MOVE }
                        ActionButton(
                            "删除这条",
                            "删掉之后就没了（这一类没有重复周次）",
                            danger = true,
                        ) {
                            onDeleteEvent(session.eventId)
                            onDismiss()
                        }
                    }

                    if (session.state != SessionState.NORMAL) {
                        ActionButton("撤销这次调整", "回到主规则") {
                            onRevert(session)
                            onDismiss()
                        }
                    }

                    // 「看一手资料」——用户要求：点开一条日程能看到当初喂给 AI 的原文/原图。
                    // 只有从 AI 来的日程才有，手动创建的不显示这一项。
                    if (session.hasProvenance) {
                        ActionButton("看一手资料", "这条日程是从哪段文字 / 哪张图来的") {
                            mode = SheetMode.PROVENANCE
                        }
                    }

                    if (isRecurring) {
                        Spacer(Modifier.height(6.dp))
                        TextButton(
                            onClick = {
                                onDeleteEvent(session.eventId)
                                onDismiss()
                            }
                        ) {
                            Text("删除整条（含所有周次）", color = MaterialTheme.colorScheme.error)
                        }
                        Text(
                            "「取消这一次」只影响 ${DateTimeExt.formatDate(session.date)}；" +
                                "「删除整条」会把整学期的这门课一起删掉。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                SheetMode.MOVE -> OccurrenceEditor(
                    session = session,
                    timetable = timetable,
                    title = "调到",
                    confirmLabel = "保存调整",
                    onCancel = { mode = SheetMode.ACTIONS },
                    onConfirm = { date, spec, location ->
                        onMove(session, date, spec, location)
                        onDismiss()
                    },
                )

                SheetMode.MAKEUP -> OccurrenceEditor(
                    session = session,
                    timetable = timetable,
                    title = "补课安排",
                    confirmLabel = "取消原课并加补课",
                    onCancel = { mode = SheetMode.ACTIONS },
                    onConfirm = { date, spec, _ ->
                        onMakeup(session, date, spec)
                        onDismiss()
                    },
                )

                SheetMode.SERIES -> {
                    val event = seriesEvent
                    if (event == null) {
                        Text("这条不是重复规则，无法整条修改。")
                    } else {
                        SeriesEditor(
                            event = event,
                            session = session,
                            timetable = timetable,
                            onCancel = { mode = SheetMode.ACTIONS },
                            onConfirm = { fromWeek, spec, location ->
                                onUpdateSeries(event, fromWeek, spec, location)
                                onDismiss()
                            },
                        )
                    }
                }

                SheetMode.PROVENANCE -> ProvenanceView(
                    session = session,
                    onBack = { mode = SheetMode.ACTIONS },
                )
            }
        }
    }
}

/**
 * 「一手资料」：这条日程是从哪来的。
 *
 * 用户要求：「点击时间轴的安排时，有一个看我上传的一手资料的选项，
 * 比如我上传给 AI 的图片，我发给 AI 的文字」。
 *
 * 为什么值得做：AI 读图会串列（实测真实课表 20 条里错 2 条），
 * 所以事后能回看原图、对着核一遍，是这套流程可信的前提。
 */
@Composable
private fun ProvenanceView(session: Session, onBack: () -> Unit) {
    // 原图是课表截图时，缩略图里的字根本看不清（用户 2026-10-19 反馈）。
    // 点一下进全屏查看器，支持双指缩放/拖动，另有 +/−/还原按钮给不方便双指的场景。
    var showImage by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Text("一手资料", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "这条日程是 AI 从下面这些内容里读出来的。原文保留在这里，方便你随时核对。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        if (session.sourceImagePath.isNotEmpty()) {
            Text("原图", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            val file = java.io.File(session.sourceImagePath)
            if (file.exists()) {
                androidx.compose.foundation.Image(
                    bitmap = rememberImageBitmap(session.sourceImagePath) ?: return@Column,
                    contentDescription = "日程来源原图（点击放大）",
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showImage = true },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "点击图片全屏查看，可双指缩放、拖动；课程表里的字放大了才看得清。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    "原图已经被清理掉了（App 只保留最近一段时间的图片）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(14.dp))
        }

        if (session.sourceText.isNotEmpty()) {
            Text("原文", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    session.sourceText,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .padding(10.dp)
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text("返回") }
        }
    }

    if (showImage) {
        ImageViewerDialog(
            path = session.sourceImagePath,
            onClose = { showImage = false },
        )
    }
}

/**
 * 全屏原图查看器：双指缩放 + 拖动，另配 +/−/还原按钮。
 *
 * 为什么不直接让缩略图可缩放：缩略图被 320dp 高度和 900px 采样压过，
 * 课表里的小字已经丢了；这里按最长边 2400px 重新解码，放大后还能看清。
 */
@Composable
private fun ImageViewerDialog(path: String, onClose: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var bitmap by remember(path) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }

    LaunchedEffect(path) {
        bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            decodeBitmap(path, maxLongest = 2400)
        }
    }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val next = (scale * zoomChange).coerceIn(1f, 8f)
        scale = next
        // 缩回 1 倍时把拖动位移清零，避免图片停在屏幕外。
        offset = if (next <= 1.01f) Offset.Zero else offset + panChange
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val image = bitmap
            if (image == null) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                androidx.compose.foundation.Image(
                    bitmap = image,
                    contentDescription = "日程来源原图",
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y,
                        )
                        .transformable(transformState)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = {
                                    if (scale > 1.01f) {
                                        scale = 1f
                                        offset = Offset.Zero
                                    } else {
                                        scale = 2.5f
                                    }
                                },
                            )
                        },
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    // Dialog 设了 decorFitsSystemWindows=false，不补这一层就会和状态栏时钟重叠。
                    .statusBarsPadding()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onClose) { Text("关闭", color = Color.White) }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = {
                        scale = (scale / 1.5f).coerceAtLeast(1f)
                        if (scale <= 1.01f) offset = Offset.Zero
                    },
                ) { Text("−", color = Color.White) }
                Text(
                    "${(scale * 100).toInt()}%",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
                TextButton(
                    onClick = { scale = (scale * 1.5f).coerceAtMost(8f) },
                ) { Text("+", color = Color.White) }
                TextButton(
                    onClick = {
                        scale = 1f
                        offset = Offset.Zero
                    },
                ) { Text("还原", color = Color.White) }
            }

            Text(
                "双指缩放 · 拖动查看 · 双击放大/还原",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
            )
        }
    }
}

/** 从本地路径解一张缩略图（不引图片库，理由见 AGENTS.md「依赖克制」）。 */
@Composable
private fun rememberImageBitmap(
    path: String,
    maxLongest: Int = 900,
): androidx.compose.ui.graphics.ImageBitmap? {
    var bitmap by remember(path) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(path) {
        bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            decodeBitmap(path, maxLongest)
        }
    }
    return bitmap
}

/**
 * 按「最长边不超过 [maxLongest]」采样解码，避免把整张原图读进内存。
 *
 * [maxLongest] 对缩略图取 900、全屏查看取 2400：
 * 课表截图里的小字在 900px 下已经糊了，全屏必须用更高的采样才看得清。
 */
private fun decodeBitmap(
    path: String,
    maxLongest: Int,
): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    var longest = maxOf(bounds.outWidth, bounds.outHeight)
    while (longest / 2 >= maxLongest) {
        sample *= 2
        longest /= 2
    }
    android.graphics.BitmapFactory.decodeFile(
        path,
        android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
    )
}.getOrNull()?.asImageBitmap()

@Composable
private fun ActionButton(
    title: String,
    subtitle: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 单次发生的编辑器：改日期 + **改时间**（时刻优先，也可切回节次）+ 地点。
 *
 * 时刻优先是用户明确要求了两次的：现实里「这节课调到 14:30」比「调到第 5-6 节」更自然。
 */
@Composable
private fun OccurrenceEditor(
    session: Session,
    timetable: Timetable = Timetable.DEFAULT,
    title: String,
    confirmLabel: String,
    onCancel: () -> Unit,
    onConfirm: (LocalDate, TimeSpec, String?) -> Unit,
) {
    var dayOffset by remember { mutableStateOf(0) }
    var location by remember { mutableStateOf(session.location) }

    // 按时刻 / 具体时刻 / 按节次：沿用这条日程原本的录入方式作为默认。
    var useClock by remember { mutableStateOf(session.spec is TimeSpec.ByClock) }
    var usePoint by remember { mutableStateOf(session.spec is TimeSpec.ByPoint) }
    var startText by remember {
        mutableStateOf(Timetable.format(session.startMinute))
    }
    var endText by remember { mutableStateOf(Timetable.format(session.endMinute)) }
    var pointText by remember { mutableStateOf(Timetable.format(session.startMinute)) }
    val initialByPeriod = session.spec as? TimeSpec.ByPeriod
    var firstPeriod by remember { mutableStateOf(initialByPeriod?.firstPeriod ?: 1) }
    var periodCount by remember { mutableStateOf(initialByPeriod?.periodCount ?: 1) }

    val startMinute = Timetable.parseMinute(startText)
    val endMinute = Timetable.parseMinute(endText)
    val pointMinute = Timetable.parseMinute(pointText)
    val clockValid = startMinute != null && endMinute != null && endMinute > startMinute

    val baseDate = LocalDate.now()
    val targetDate = baseDate.plusDays(dayOffset.toLong())

    Column {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        Text("日期：${DateTimeExt.formatDateWithWeek(targetDate)}", style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            for (offset in -3..14) {
                FilterChip(
                    selected = dayOffset == offset,
                    onClick = { dayOffset = offset },
                    label = {
                        val date = baseDate.plusDays(offset.toLong())
                        Text("${date.monthValue}/${date.dayOfMonth}")
                    },
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        // 三个 chip 放在同一行时「按第几节」会被挤成两行，拆成两行更稳。
        Text("时间", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = useClock,
                onClick = { useClock = true; usePoint = false },
                label = { Text("几点到几点") },
                modifier = Modifier.padding(end = 4.dp),
            )
            FilterChip(
                selected = usePoint,
                onClick = { useClock = false; usePoint = true },
                label = { Text("具体时刻") },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = !useClock && !usePoint,
                onClick = { useClock = false; usePoint = false },
                label = { Text("按第几节") },
            )
        }

        if (useClock) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = startText,
                    onValueChange = { startText = it },
                    label = { Text("开始") },
                    singleLine = true,
                    isError = startMinute == null,
                    modifier = Modifier.width(120.dp),
                )
                Text("—", Modifier.padding(horizontal = 8.dp))
                OutlinedTextField(
                    value = endText,
                    onValueChange = { endText = it },
                    label = { Text("结束") },
                    singleLine = true,
                    isError = endMinute == null || (startMinute != null && endMinute <= startMinute),
                    modifier = Modifier.width(120.dp),
                )
            }
            if (!clockValid) {
                Text(
                    "时间要写成 HH:mm（例如 14:30），且结束必须晚于开始。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        } else if (usePoint) {
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = pointText,
                onValueChange = { pointText = it },
                label = { Text("时刻") },
                placeholder = { Text("23:59") },
                singleLine = true,
                isError = pointMinute == null,
                modifier = Modifier.width(140.dp),
            )
            if (pointMinute == null) {
                Text(
                    "时间要写成 HH:mm（例如 23:59）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        } else {
            Spacer(Modifier.height(6.dp))
            Text("开始节次", style = MaterialTheme.typography.labelSmall)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                for (period in 1..timetable.maxPeriod) {
                    FilterChip(
                        selected = firstPeriod == period,
                        onClick = { firstPeriod = period },
                        label = { Text("$period") },
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "连堂：$periodCount 节（${timetable.rangeLabel(firstPeriod, periodCount)}）",
                style = MaterialTheme.typography.labelSmall,
            )
            Row {
                for (count in 1..4) {
                    FilterChip(
                        selected = periodCount == count,
                        onClick = { periodCount = count },
                        label = { Text("$count") },
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = location,
            onValueChange = { location = it },
            label = { Text("地点") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onCancel) { Text("返回") }
            Spacer(Modifier.width(8.dp))
            Button(
                enabled = when {
                    useClock -> clockValid
                    usePoint -> pointMinute != null
                    else -> true
                },
                onClick = {
                    val spec: TimeSpec = when {
                        useClock -> TimeSpec.ByClock(startMinute!!, endMinute!!)
                        usePoint -> TimeSpec.ByPoint(pointMinute!!)
                        else -> TimeSpec.ByPeriod(firstPeriod, periodCount)
                    }
                    onConfirm(targetDate, spec, location.ifEmpty { null })
                },
            ) { Text(confirmLabel) }
        }
    }
}

/** 整条规则的编辑器：Q23 = 从本周起 / 整学期，由用户当场选。时间同样是时刻优先。 */
@Composable
private fun SeriesEditor(
    event: Event,
    timetable: Timetable = Timetable.DEFAULT,
    session: Session,
    onCancel: () -> Unit,
    onConfirm: (Int, TimeSpec, String) -> Unit,
) {
    val currentWeek = Semester.weekOf(session.date)
    var fromWeek by remember { mutableStateOf(currentWeek) }
    var location by remember { mutableStateOf(event.location) }
    var useClock by remember { mutableStateOf(event.time is TimeSpec.ByClock) }
    var usePoint by remember { mutableStateOf(event.time is TimeSpec.ByPoint) }
    var startText by remember { mutableStateOf(Timetable.format(session.startMinute)) }
    var endText by remember { mutableStateOf(Timetable.format(session.endMinute)) }
    var pointText by remember { mutableStateOf(Timetable.format(session.startMinute)) }
    val initialByPeriod = event.time as? TimeSpec.ByPeriod
    var firstPeriod by remember { mutableStateOf(initialByPeriod?.firstPeriod ?: 1) }
    var periodCount by remember { mutableStateOf(initialByPeriod?.periodCount ?: 1) }

    val startMinute = Timetable.parseMinute(startText)
    val endMinute = Timetable.parseMinute(endText)
    val pointMinute = Timetable.parseMinute(pointText)
    val clockValid = startMinute != null && endMinute != null && endMinute > startMinute

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("改整条规则") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "「${event.title}」目前是 ${event.recurrence?.let { describe(it) } ?: "单次事件"}。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                Text("生效范围", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Row {
                    FilterChip(
                        selected = fromWeek == currentWeek,
                        onClick = { fromWeek = currentWeek },
                        label = { Text("从第 $currentWeek 周起") },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    FilterChip(
                        selected = fromWeek == (event.recurrence?.startWeek ?: 1),
                        onClick = { fromWeek = event.recurrence?.startWeek ?: 1 },
                        label = { Text("整学期") },
                    )
                }
                Text(
                    "「从本周起」会保留已经过去周次的记录——「我那天确实在 A101 上的」。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(12.dp))
                Text("时间", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = useClock,
                        onClick = { useClock = true; usePoint = false },
                        label = { Text("几点到几点") },
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    FilterChip(
                        selected = usePoint,
                        onClick = { useClock = false; usePoint = true },
                        label = { Text("具体时刻") },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = !useClock && !usePoint,
                        onClick = { useClock = false; usePoint = false },
                        label = { Text("按第几节") },
                    )
                }

                if (useClock) {
                    Spacer(Modifier.height(6.dp))
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
                    if (!clockValid) {
                        Text(
                            "时间要写成 HH:mm，且结束晚于开始。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else if (usePoint) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = pointText,
                        onValueChange = { pointText = it },
                        label = { Text("时刻") },
                        placeholder = { Text("23:59") },
                        singleLine = true,
                        isError = pointMinute == null,
                        modifier = Modifier.width(140.dp),
                    )
                } else {
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        for (period in 1..timetable.maxPeriod) {
                            FilterChip(
                                selected = firstPeriod == period,
                                onClick = { firstPeriod = period },
                                label = { Text("$period") },
                                modifier = Modifier.padding(end = 4.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "连堂：$periodCount 节（${timetable.rangeLabel(firstPeriod, periodCount)}）",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Row {
                        for (count in 1..4) {
                            FilterChip(
                                selected = periodCount == count,
                                onClick = { periodCount = count },
                                label = { Text("$count") },
                                modifier = Modifier.padding(end = 4.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("地点") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = when {
                    useClock -> clockValid
                    usePoint -> pointMinute != null
                    else -> true
                },
                onClick = {
                    val spec: TimeSpec = when {
                        useClock -> TimeSpec.ByClock(startMinute!!, endMinute!!)
                        usePoint -> TimeSpec.ByPoint(pointMinute!!)
                        else -> TimeSpec.ByPeriod(firstPeriod, periodCount)
                    }
                    onConfirm(fromWeek, spec, location)
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消") } },
    )
}

private fun describe(rule: Recurrence): String {
    val parity = when (rule.parity) {
        WeekParity.EVERY -> "每周"
        WeekParity.ODD -> "单周"
        WeekParity.EVEN -> "双周"
    }
    return buildString {
        append(DateTimeExt.weekName(Semester.mondayOf(rule.startWeek).plusDays((rule.dayOfWeek.value - 1).toLong())))
        append(' ')
        append(parity)
        if (rule.weeks.isNotEmpty()) {
            append("（第 ")
            append(rule.weeks.joinToString(","))
            append(" 周）")
        } else {
            append(" 第 ")
            append(rule.startWeek)
            append('-')
            append(rule.endWeek)
            append(" 周")
        }
    }
}

/**
 * 类别标签 → 行为类型。
 *
 * 用户自定义的标签（比如「实验」）没有对应的行为语义，统一按 ACTIVITY 处理；
 * 但内置那几个名字要映射到正确的 kind，否则「课程」不会按周重复、
 * 「截止」不会进「未完成的截止」列表。
 */
private fun kindOfCategory(label: String): EventKind = when (label) {
    "课程" -> EventKind.COURSE
    "截止" -> EventKind.DEADLINE
    "答疑" -> EventKind.OFFICE_HOUR
    else -> EventKind.ACTIVITY
}

/** 新建一条行程的对话框（右下角 ＋ 按钮的落地）。 */
@Composable
fun NewEventDialog(
    today: LocalDate,
    // 节次选择器要按当前作息表列出来，并且把「几点到几点」显示在旁边。
    timetable: Timetable = Timetable.DEFAULT,
    /** 用户自定义的类别列表（设置页可编辑）。 */
    categories: List<String> = Settings.DEFAULT_CATEGORIES,
    onDismiss: () -> Unit,
    onConfirm: (String, EventKind, String, LocalDate, TimeSpec, String, String, Recurrence?) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var category by remember(categories) { mutableStateOf(categories.firstOrNull().orEmpty()) }
    var dayOffset by remember { mutableStateOf(0) }
    var location by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    // 用户提了两次的需求：**默认按时间点填**，节次是可选项而不是唯一选项。
    // 所以默认 startText/endText 直接给出「下一个整点 + 一小时」，让最常见的
    // 「14:30-16:00 开会」这种录入不用先切模式。
    var timeMode by remember { mutableStateOf(NewEventTimeMode.CLOCK) }
    var startText by remember { mutableStateOf(defaultStartText()) }
    var endText by remember { mutableStateOf(defaultEndText()) }
    var pointText by remember { mutableStateOf(defaultStartText()) }
    // 按节次模式时用这两个
    var firstPeriod by remember { mutableStateOf(3) }
    var periodCount by remember { mutableStateOf(2) }

    // 整学期课程：用户反馈「他不能设置一学期的课程，只会一节一节设置」。
    // 选「课程」类别时默认就是「每周」，周次从所选日期所在周一直到学期末。
    var repeatWeekly by remember { mutableStateOf(kindOfCategory(category) == EventKind.COURSE) }
    var startWeek by remember { mutableStateOf(Semester.weekOf(today.plusDays(dayOffset.toLong()))) }
    var endWeek by remember { mutableStateOf(Semester.totalWeeks) }
    var parity by remember { mutableStateOf(WeekParity.EVERY) }

    val selectedDate = today.plusDays(dayOffset.toLong())
    val isCourse = kindOfCategory(category) == EventKind.COURSE

    // 换日期时把「起始周」跟着带到那一周，否则选了下周的日期、周次仍停在上周。
    LaunchedEffect(dayOffset) {
        startWeek = Semester.weekOf(selectedDate).coerceIn(1, Semester.totalWeeks)
    }
    val startMinute = Timetable.parseMinute(startText)
    val endMinute = Timetable.parseMinute(endText)
    val pointMinute = Timetable.parseMinute(pointText)
    val clockValid = startMinute != null && endMinute != null && endMinute > startMinute

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建行程") },
        text = {
            // 注意：这里**不能**加 Modifier.horizontalScroll。
            // 踩过的坑（真机闪退）：下面的日期/节次选择器各自又是横向滚动的 Row，
            // 横向滚动套横向滚动会让内层的最大宽度约束变成无穷大，Compose 直接抛
            //   IllegalStateException: Horizontally scrollable component was measured
            //   with an infinity maximum width constraints
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("做什么") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))

                Text("类别", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                // 列表来自设置页，用户可以自己加（用户反馈：类别不能自定义）。
                // 数量多时横向滚动，所以这里必须是可滚动的 Row——但不能在它外层再套
                // horizontalScroll（见本函数开头的闪退记录）。
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    for (option in categories) {
                        FilterChip(
                            selected = category == option,
                            onClick = {
                                category = option
                                // 选「课程」默认按整学期重复；选别的默认单次。
                                repeatWeekly = kindOfCategory(option) == EventKind.COURSE
                                // 选「截止」时自动切到「全天」：作业死线基本都没有具体钟点，
                                // 让用户再手动点一次「全天」是多余的一步。
                                // 只在他还没填过具体时刻时自动切——已经填了 14:30 的不要动。
                                if (kindOfCategory(option) == EventKind.DEADLINE &&
                                    timeMode == NewEventTimeMode.CLOCK &&
                                    startText == defaultStartText()
                                ) {
                                    timeMode = NewEventTimeMode.ALL_DAY
                                }
                            },
                            label = { Text(option) },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text("日期：${DateTimeExt.formatDateWithWeek(today.plusDays(dayOffset.toLong()))}", style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    for (offset in 0..14) {
                        FilterChip(
                            selected = dayOffset == offset,
                            onClick = { dayOffset = offset },
                            label = {
                                val date = today.plusDays(offset.toLong())
                                Text("${date.monthValue}/${date.dayOfMonth}")
                            },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                // ---------- 时间：四选一（区间 / 具体时刻 / 全天 / 按第几节）----------
                // 四个 chip 排成两行：一行放不下时第三个会被裁掉（真机上「全天」只露出半个字），
                // 而「全天」恰恰是截止类最常用的选项。
                Text("时间", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = timeMode == NewEventTimeMode.CLOCK,
                        onClick = { timeMode = NewEventTimeMode.CLOCK },
                        label = { Text("几点到几点") },
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    FilterChip(
                        // 「具体时刻」：用户 2026-10-19 明确要求「一个具体时间点」。
                        selected = timeMode == NewEventTimeMode.POINT,
                        onClick = { timeMode = NewEventTimeMode.POINT },
                        label = { Text("具体时刻") },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        // 「全天」：作业、报名这类没有具体时刻的事。
                        selected = timeMode == NewEventTimeMode.ALL_DAY,
                        onClick = { timeMode = NewEventTimeMode.ALL_DAY },
                        label = { Text("全天") },
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    FilterChip(
                        selected = timeMode == NewEventTimeMode.PERIOD,
                        onClick = { timeMode = NewEventTimeMode.PERIOD },
                        label = { Text("按第几节") },
                    )
                }

                if (timeMode == NewEventTimeMode.CLOCK) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = startText,
                            onValueChange = { startText = it },
                            label = { Text("开始") },
                            placeholder = { Text("14:30") },
                            singleLine = true,
                            isError = startMinute == null,
                            modifier = Modifier.width(120.dp),
                        )
                        Text("—", Modifier.padding(horizontal = 8.dp))
                        OutlinedTextField(
                            value = endText,
                            onValueChange = { endText = it },
                            label = { Text("结束") },
                            placeholder = { Text("16:00") },
                            singleLine = true,
                            isError = endMinute == null || (startMinute != null && endMinute <= startMinute),
                            modifier = Modifier.width(120.dp),
                        )
                    }
                    if (!clockValid) {
                        Text(
                            "时间要写成 HH:mm（例如 14:30），且结束必须晚于开始。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else if (timeMode == NewEventTimeMode.POINT) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = pointText,
                            onValueChange = { pointText = it },
                            label = { Text("时刻") },
                            placeholder = { Text("23:59") },
                            singleLine = true,
                            isError = pointMinute == null,
                            modifier = Modifier.width(140.dp),
                        )
                    }
                    Text(
                        if (pointMinute == null) {
                            "时间要写成 HH:mm（例如 23:59）。"
                        } else {
                            "只占这一个时间点，不会变成一段时长。"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (pointMinute == null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                } else if (timeMode == NewEventTimeMode.ALL_DAY) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "占整天，只显示「全天」。适合作业、报名截止这类没有具体钟点的事。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Spacer(Modifier.height(6.dp))
                    Text("开始节次", style = MaterialTheme.typography.labelSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        for (period in 1..timetable.maxPeriod) {
                            FilterChip(
                                selected = firstPeriod == period,
                                onClick = { firstPeriod = period },
                                label = { Text("$period") },
                                modifier = Modifier.padding(end = 4.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "连堂：$periodCount 节（${timetable.rangeLabel(firstPeriod, periodCount)}）",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Row {
                        for (count in 1..4) {
                            FilterChip(
                                selected = periodCount == count,
                                onClick = { periodCount = count },
                                label = { Text("$count") },
                                modifier = Modifier.padding(end = 4.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                if (isCourse) {
                    Text("重复", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(
                            selected = !repeatWeekly,
                            onClick = { repeatWeekly = false },
                            label = { Text("只这一次") },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                        FilterChip(
                            selected = repeatWeekly,
                            onClick = { repeatWeekly = true },
                            label = { Text("整学期每周") },
                        )
                    }
                    if (repeatWeekly) {
                        // 起止周分两行放：挤在一行时「第 19 周」会被折成「第 1 / 9 周」。
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("起始", style = MaterialTheme.typography.labelSmall)
                            WeekStepper(
                                value = startWeek,
                                min = 1,
                                max = endWeek,
                                onChange = { startWeek = it },
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("结束", style = MaterialTheme.typography.labelSmall)
                            WeekStepper(
                                value = endWeek,
                                min = startWeek,
                                max = Semester.totalWeeks,
                                onChange = { endWeek = it },
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("单双周", style = MaterialTheme.typography.labelSmall)
                            for (option in listOf(WeekParity.EVERY, WeekParity.ODD, WeekParity.EVEN)) {
                                FilterChip(
                                    selected = parity == option,
                                    onClick = { parity = option },
                                    label = { Text(option.descZh) },
                                    modifier = Modifier.padding(end = 4.dp),
                                )
                            }
                        }
                        // 摘要里的时间必须用「安全」的写法。
                        // 直接构造 TimeSpec.ByClock(start ?: 0, end ?: 0) 会在用户
                        // 刚清空结束时间、还没输入新值时抛 require(end > start) 崩溃
                        // （2026-10-19 真机 crash log：SessionSheet.kt:1030）。
                        val previewTime = when (timeMode) {
                            NewEventTimeMode.CLOCK ->
                                if (startMinute != null && endMinute != null && endMinute > startMinute) {
                                    "${Timetable.format(startMinute)}-${Timetable.format(endMinute)}"
                                } else {
                                    "时间待填"
                                }
                            NewEventTimeMode.POINT ->
                                pointMinute?.let { Timetable.format(it) } ?: "时间待填"
                            NewEventTimeMode.ALL_DAY -> "全天"
                            NewEventTimeMode.PERIOD -> timetable.rangeLabel(firstPeriod, periodCount)
                        }
                        Text(
                            "第 ${startWeek}-${endWeek} 周 · ${parity.descZh} · " +
                                "${DateTimeExt.weekName(selectedDate)} $previewTime",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
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
                enabled = title.isNotBlank() &&
                    (timeMode != NewEventTimeMode.CLOCK || clockValid) &&
                    (timeMode != NewEventTimeMode.POINT || pointMinute != null) &&
                    (!isCourse || !repeatWeekly || endWeek >= startWeek),
                onClick = {
                    val spec: TimeSpec = when (timeMode) {
                        NewEventTimeMode.CLOCK -> TimeSpec.ByClock(startMinute!!, endMinute!!)
                        NewEventTimeMode.POINT -> TimeSpec.ByPoint(pointMinute!!)
                        NewEventTimeMode.ALL_DAY -> TimeSpec.ALL_DAY
                        NewEventTimeMode.PERIOD -> TimeSpec.ByPeriod(firstPeriod, periodCount)
                    }
                    val recurrence = if (isCourse && repeatWeekly) {
                        Recurrence(
                            dayOfWeek = selectedDate.dayOfWeek,
                            startWeek = startWeek,
                            endWeek = endWeek,
                            parity = parity,
                        )
                    } else {
                        null
                    }
                    onConfirm(
                        title.trim(),
                        kindOfCategory(category),
                        category,
                        selectedDate,
                        spec,
                        location.trim(),
                        note.trim(),
                        recurrence,
                    )
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 新建时时间怎么填。 */
private enum class NewEventTimeMode {
    /** 一段区间，例如 14:30-16:00。最常用。 */
    CLOCK,

    /** 一个具体时刻，例如 23:59 截止。 */
    POINT,

    /** 全天。作业死线这类没有具体时刻的事。 */
    ALL_DAY,

    /** 按第几节。课程用。 */
    PERIOD,
}

/** 周次步进器：左右各一个按钮，中间显示「第 N 周」。 */
@Composable
private fun WeekStepper(
    value: Int,
    min: Int,
    max: Int,
    onChange: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = { if (value > min) onChange(value - 1) },
            enabled = value > min,
        ) { Text("−") }
        Text("第 $value 周", style = MaterialTheme.typography.labelSmall)
        TextButton(
            onClick = { if (value < max) onChange(value + 1) },
            enabled = value < max,
        ) { Text("+") }
    }
}

/** 默认开始时间：下一个整点。 */
private fun defaultStartText(): String {
    val now = java.time.LocalTime.now()
    val next = now.plusHours(1).withMinute(0)
    return Timetable.format(next.hour * 60 + next.minute)
}

/** 默认结束时间：下一个整点 + 1 小时。 */
private fun defaultEndText(): String {
    val now = java.time.LocalTime.now()
    val next = now.plusHours(2).withMinute(0)
    return Timetable.format(next.hour * 60 + next.minute)
}
