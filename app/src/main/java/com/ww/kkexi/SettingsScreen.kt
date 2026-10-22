package com.ww.kkexi

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 设置页：**入口列表 + 各自的详情页**。
 *
 * ## 为什么改成这样（用户反馈）
 * 原先把所有设置堆成一条长列表，滑半天找不到想改的那一项。
 * 用户原话：「设置界面不够简洁，我感觉可以有几个选项点进去可以设置这个板块的详细内容」。
 *
 * 所以现在是：一屏六个入口，每个入口一行（标题 + 当前值摘要 + ›），
 * 点进去是该板块的详情页，带返回按钮，**并且接管系统返回键**（不会一按就退出 App）。
 *
 * 参考的做法是 Android 系统设置与 Telegram 的设置页：入口行必须**显示当前值**
 * （「AI 服务 · DeepSeek」而不是光写「AI 服务」），这样大多数时候不用点进去就知道状态。
 */
@Composable
fun SettingsScreen(
    state: UiState,
    onAiEnabled: (Boolean) -> Unit,
    onSaveProvider: (AiProvider) -> Unit,
    onDeleteProvider: (String) -> Unit,
    onSetActiveProvider: (String) -> Unit,
    onTestConnection: () -> Unit,
    onApplyTimetablePreset: (Timetable) -> Unit,
    onUpdatePeriod: (Int, String, String) -> Unit,
    onResetTimetable: () -> Unit,
    onAddCategory: (String) -> Unit,
    onRemoveCategory: (String) -> Unit,
    onResetCategories: () -> Unit,
    onSeedSampleData: (Boolean) -> Unit,
    onLoadSample: () -> Unit,
    onRemindersEnabled: (Boolean) -> Unit,
    onDailyBriefing: (Boolean, Int) -> Unit,
    onLeadMinutes: (Int, Int) -> Unit,
    onExport: () -> String,
    onImport: (String) -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
) {
    var page by remember { mutableStateOf(SettingsPage.HUB) }
    // 服务商详情页要显示哪一份（null = 新增）。用独立页面 + 这两个状态，
    // 而**不是**在 AiSettingsPage 里面再套一层 Scaffold——
    // 那样会出现「竖向滚动套竖向滚动」，实测直接崩：
    //   IllegalStateException: Vertically scrollable component was measured
    //   with an infinity maximum height constraints
    // （跟之前那个 horizontalScroll 闪退是同一类错误，只是方向不同。）
    var aiEditing by remember { mutableStateOf<AiProvider?>(null) }

    // 系统返回键：详情页退回上一层，而不是直接退出 App。
    BackHandler(enabled = page != SettingsPage.HUB) {
        page = if (page == SettingsPage.AI_EDIT) SettingsPage.AI else SettingsPage.HUB
    }

    when (page) {
        SettingsPage.HUB -> SettingsHub(
            state = state,
            onOpen = { page = it },
        )

        SettingsPage.AI -> SettingsDetailScaffold("AI 服务", onBack = { page = SettingsPage.HUB }) {
            AiSettingsPage(
                state = state,
                onAiEnabled = onAiEnabled,
                onSetActiveProvider = onSetActiveProvider,
                onTestConnection = onTestConnection,
                onEditProvider = {
                    aiEditing = it
                    page = SettingsPage.AI_EDIT
                },
                onAddProvider = {
                    aiEditing = null
                    page = SettingsPage.AI_EDIT
                },
            )
        }

        SettingsPage.AI_EDIT -> SettingsDetailScaffold(
            title = aiEditing?.let { "修改「${it.name}」" } ?: "新增服务商",
            onBack = {
                aiEditing = null
                page = SettingsPage.AI
            },
        ) {
            val current = aiEditing
            ProviderForm(
                initial = current,
                onCancel = {
                    aiEditing = null
                    page = SettingsPage.AI
                },
                onSave = {
                    onSaveProvider(it)
                    aiEditing = null
                    page = SettingsPage.AI
                },
                onDelete = current?.let { provider ->
                    {
                        onDeleteProvider(provider.id)
                        aiEditing = null
                        page = SettingsPage.AI
                    }
                },
            )
        }

        SettingsPage.APPEARANCE -> SettingsDetailScaffold("外观", onBack = { page = SettingsPage.HUB }) {
            AppearanceSettingsPage(
                state = state,
                onThemeMode = onThemeMode,
                onDynamicColor = onDynamicColor,
            )
        }

        SettingsPage.TIMETABLE -> SettingsDetailScaffold("课程时间", onBack = { page = SettingsPage.HUB }) {
            TimetableSettingsPage(
                state = state,
                onApplyPreset = onApplyTimetablePreset,
                onUpdatePeriod = onUpdatePeriod,
                onReset = onResetTimetable,
            )
        }

        SettingsPage.CATEGORIES -> SettingsDetailScaffold("日程类别", onBack = { page = SettingsPage.HUB }) {
            CategorySettingsPage(
                state = state,
                onAdd = onAddCategory,
                onRemove = onRemoveCategory,
                onReset = onResetCategories,
            )
        }

        SettingsPage.REMINDERS -> SettingsDetailScaffold("提醒", onBack = { page = SettingsPage.HUB }) {
            ReminderSettingsPage(
                state = state,
                onRemindersEnabled = onRemindersEnabled,
                onDailyBriefing = onDailyBriefing,
                onLeadMinutes = onLeadMinutes,
            )
        }

        SettingsPage.DATA -> SettingsDetailScaffold("数据", onBack = { page = SettingsPage.HUB }) {
            DataSettingsPage(
                state = state,
                onExport = onExport,
                onImport = onImport,
                onSeedSampleData = onSeedSampleData,
                onLoadSample = onLoadSample,
            )
        }

        SettingsPage.ABOUT -> SettingsDetailScaffold("关于", onBack = { page = SettingsPage.HUB }) {
            AboutPage()
        }
    }
}

/** 设置里的页面。 */
private enum class SettingsPage(val title: String) {
    HUB("设置"),
    AI("AI 服务"),
    /** 编辑/新增某一份服务商配置（AI 页的子页）。 */
    AI_EDIT("AI 服务"),
    APPEARANCE("外观"),
    TIMETABLE("课程时间"),
    CATEGORIES("日程类别"),
    REMINDERS("提醒"),
    DATA("数据"),
    ABOUT("关于"),
}

/** 详情页外壳：标题栏 + 返回按钮 + 可滚动内容。 */
@Composable
private fun SettingsDetailScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) { content() }
    }
}

/**
 * 入口列表。
 *
 * 每一行都带上**当前值摘要**——这是"不用点进去就知道现在是什么状态"的关键。
 */
@Composable
private fun SettingsHub(state: UiState, onOpen: (SettingsPage) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp)
    ) {
        HubRow(
            title = "AI 服务",
            // 用 state.activeProviderName（由 AiConfigStore.active() 算出，含「按 id 找不到就回退到
            // 第一个配好的」这层逻辑），而不是自己按 activeProviderId 去列表里查。
            // 自己查的写法会在「active_id 指向已删除的配置」时显示成「尚未配置」，
            // 而实际上 AI 是能用的——状态与实际不符。
            summary = when {
                !state.aiEnabled -> "已关闭（完全离线）"
                state.activeProviderName.isNotEmpty() -> {
                    state.activeProviderName + " · 读图" + if (state.visionReady) "可用" else "不可用"
                }
                else -> "尚未配置"
            },
            onClick = { onOpen(SettingsPage.AI) },
        )
        HubRow(
            title = "外观",
            summary = buildString {
                append(state.themeMode.label)
                if (state.dynamicColor) append(" · 动态取色")
            },
            onClick = { onOpen(SettingsPage.APPEARANCE) },
        )
        HubRow(
            title = "课程时间",
            summary = if (state.timetableIsDefault) "默认作息表" else "已自定义 · ${state.timetable.slots.size} 节",
            onClick = { onOpen(SettingsPage.TIMETABLE) },
        )
        HubRow(
            title = "日程类别",
            summary = if (state.categoriesAreDefault) {
                "默认 ${state.categories.size} 个"
            } else {
                state.categories.joinToString("、").take(30)
            },
            onClick = { onOpen(SettingsPage.CATEGORIES) },
        )
        HubRow(
            title = "提醒",
            summary = when {
                !state.remindersEnabled -> "已关闭"
                state.dailyBriefingEnabled -> "已开启 · 每天 ${Timetable.format(state.dailyBriefingMinute)} 汇总"
                else -> "已开启"
            },
            onClick = { onOpen(SettingsPage.REMINDERS) },
        )
        HubRow(
            title = "数据",
            summary = if (state.libraryEmpty) "暂无日程 · 可导入备份" else "${state.eventCount} 条日程 · 可导出备份",
            onClick = { onOpen(SettingsPage.DATA) },
        )
        HubRow(
            title = "关于",
            summary = "课隙 · 本地日程本",
            onClick = { onOpen(SettingsPage.ABOUT) },
        )
    }
}

@Composable
private fun HubRow(title: String, summary: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "›",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.padding(start = 18.dp),
    )
}

// ==================================================================== 各详情页

/**
 * AI 服务详情页。
 *
 * ## 布局按用户明确要求做成两级
 * 用户原话：「关于这个 AI 配置我还是想看上去简单一点，设置界面就这样：
 * 初始配置只有一个 DeepSeek 和自定义，点修改的时候跳到另一个窗口详细的配置」。
 *
 * 所以这里：
 * - 默认只看到**一列名字**（DeepSeek / 自定义…），一行一个，右侧一个「修改」；
 * - 点「修改」跳到**独立的一页**（不是原地展开），里面才是地址、Key、模型那些细节；
 * - 顶部一个总开关，关掉即完全离线。
 *
 * 为什么要两级：一份配置有 7 个字段，全都摊在主界面上，第一次用的人会被吓退；
 * 而日常真正要动的只有「换一份」这一件事。
 */
@Composable
private fun AiSettingsPage(
    state: UiState,
    onAiEnabled: (Boolean) -> Unit,
    onSetActiveProvider: (String) -> Unit,
    onTestConnection: () -> Unit,
    /** 点某一行的「修改」→ 由上层切到 AI_EDIT 页。 */
    onEditProvider: (AiProvider) -> Unit,
    onAddProvider: () -> Unit,
) {
    SwitchRow(
        title = "启用 AI",
        subtitle = "关掉它，这个 App 完全离线。AI 只用于「解析文本 / 读图」和「问答、改日程」两件事。",
        checked = state.aiEnabled,
        onChange = onAiEnabled,
    )

    Spacer(Modifier.height(20.dp))
    FieldLabel("服务商")
    Spacer(Modifier.height(4.dp))
    Text(
        "点一行进去改详细的地址、Key、模型。Key 只存在这台手机上，只发往你自己填的地址。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))

    /*
     * 排序：当前在用的排最前，其次是其它已配好的，最后才是还没填 Key 的。
     *
     * 为什么要排序：内置预设是 4 份（日常 / 深度思考 / 智谱 / 自定义），
     * 用户通常只填其中一份。如果按存储顺序显示，配好的那份很可能排在最下面，
     * 一进来看到三行「未填写 Key」，会以为自己的配置丢了。
     */
    val ordered = state.aiProviders.sortedWith(
        compareByDescending<AiProvider> { it.id == state.activeProviderId }
            .thenByDescending { it.configured }
    )

    for (provider in ordered) {
        ProviderSimpleRow(
            provider = provider,
            active = provider.id == state.activeProviderId,
            onSelect = { onSetActiveProvider(provider.id) },
            onEdit = { onEditProvider(provider) },
        )
    }

    Spacer(Modifier.height(12.dp))
    Row {
        OutlinedButton(onClick = onAddProvider) { Text("添加服务商") }
        Spacer(Modifier.width(10.dp))
        TextButton(onClick = onTestConnection) { Text("测试连接") }
    }

    if (state.aiProviders.none { it.configured }) {
        Spacer(Modifier.height(16.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("还没有可用的配置", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "填一份 API Key 就能用了。日程功能完全不受影响——AI 只是可选的帮手。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 服务商列表里的一行：**只有名字和一个「修改」**。
 *
 * 与旧的 [ProviderRow] 的区别：不再把 baseUrl / 模型名 / 打码 Key 全摊出来。
 * 那些细节属于「详情页」，不该在概览里挤成一团。
 */
@Composable
private fun ProviderSimpleRow(
    provider: AiProvider,
    active: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 选中态用一个圆点，比整行高亮更安静
        Box(
            Modifier
                .size(18.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (active) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(5.dp))
                )
            } else {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(5.dp))
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                provider.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                when {
                    !provider.configured -> "未填写 Key"
                    active -> "正在使用"
                    else -> "已配置"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (!provider.configured) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (!active && provider.configured) {
            TextButton(onClick = onSelect) { Text("用它") }
        }
        Text(
            "›",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    )
}

/** 外观详情页：深浅色 + 动态取色 + 颜色约定说明。 */
@Composable
private fun AppearanceSettingsPage(
    state: UiState,
    onThemeMode: (ThemeMode) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
) {
    FieldLabel("深浅色")
    Spacer(Modifier.height(4.dp))
    Text(
        "「跟随系统」是默认。改成固定模式后，即使系统切换深浅色，这个 App 也保持你选的样子。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    Column {
        for (mode in ThemeMode.entries) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onThemeMode(mode) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = state.themeMode == mode,
                    onClick = { onThemeMode(mode) },
                )
                Text(mode.label, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    HorizontalDivider()
    Spacer(Modifier.height(14.dp))

    FieldLabel("动态取色")
    Spacer(Modifier.height(4.dp))
    Text(
        "打开后主色会从壁纸取（Material You，需要 Android 12+）。" +
            "四种日程类别的颜色不会变——它们要用来区分彼此，跟着壁纸变反而认不出来。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    SwitchRow(
        title = "使用系统配色",
        subtitle = if (state.dynamicColor) "已开启" else "已关闭（用 App 自己的蓝色主题）",
        checked = state.dynamicColor,
        onChange = onDynamicColor,
    )

    Spacer(Modifier.height(20.dp))
    HorizontalDivider()
    Spacer(Modifier.height(14.dp))
    FieldLabel("颜色约定")
    Spacer(Modifier.height(6.dp))
    Text(
        "这套配色全 App 统一，看到颜色就知道是什么类型：",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    for ((kind, label) in listOf(
        EventKind.COURSE to "课程（按周重复）",
        EventKind.ACTIVITY to "行程（自己加的事）",
        EventKind.DEADLINE to "截止（死线）",
        EventKind.OFFICE_HOUR to "答疑（固定时段）",
    )) {
        ColorLegendRow(color = KindColors.of(kind, state.isDarkTheme), label = label)
    }
    ColorLegendRow(color = KindColors.conflict, label = "时间冲突（角标）")
    ColorLegendRow(color = KindColors.cancelled, label = "已取消")
}

@Composable
private fun ColorLegendRow(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(14.dp)
                .background(color, RoundedCornerShape(4.dp))
        )
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 课程时间详情页：预设 + 逐节编辑。 */
@Composable
private fun TimetableSettingsPage(
    state: UiState,
    onApplyPreset: (Timetable) -> Unit,
    onUpdatePeriod: (Int, String, String) -> Unit,
    onReset: () -> Unit,
) {
    var showPeriodEditor by remember { mutableStateOf(false) }

    FieldLabel("预设")
    Spacer(Modifier.height(4.dp))
    Text(
        "这里配置每个课节的起止时刻。按课节录入的课程，显示时会换算成这里的真实时间。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Column {
        for (preset in Timetable.PRESETS) {
            OutlinedButton(
                onClick = { onApplyPreset(preset) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Text(preset.name, fontWeight = FontWeight.Medium)
                    Text(
                        "第 1 节 ${preset.label(1)} · 共 ${preset.slots.size} 节",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { showPeriodEditor = !showPeriodEditor }) {
            Text(if (showPeriodEditor) "收起逐节编辑" else "逐节编辑时间")
        }
        Spacer(Modifier.width(10.dp))
        TextButton(onClick = onReset) { Text("恢复默认") }
    }

    if (showPeriodEditor) {
        Spacer(Modifier.height(8.dp))
        Text(
            "改完按「保存」生效。格式 HH:mm，例如 08:20。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        for (slot in state.timetable.slots) {
            PeriodEditorRow(
                slot = slot,
                onSave = { start, end -> onUpdatePeriod(slot.period, start, end) },
            )
        }
    }
}

/** 类别详情页：增删。 */
@Composable
private fun CategorySettingsPage(
    state: UiState,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onReset: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }

    Text(
        "新建行程时能选的标签。加几个自己习惯的（比如「实验」「社团」「考试」）。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))

    for ((index, label) in state.categories.withIndex()) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (index == 0) {
                Text(
                    "默认",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                IconButton(onClick = { onRemove(label) }, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "删除类别 $label",
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("新类别名") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Button(
            enabled = draft.isNotBlank(),
            onClick = {
                onAdd(draft)
                draft = ""
            },
        ) { Text("添加") }
    }
    if (!state.categoriesAreDefault) {
        Spacer(Modifier.height(6.dp))
        TextButton(onClick = onReset) { Text("恢复默认四个") }
    }
}

/** 提醒详情页。 */
@Composable
private fun ReminderSettingsPage(
    state: UiState,
    onRemindersEnabled: (Boolean) -> Unit,
    onDailyBriefing: (Boolean, Int) -> Unit,
    onLeadMinutes: (Int, Int) -> Unit,
) {
    SwitchRow(
        title = "开启提醒",
        subtitle = "到点用通知提醒你。关掉就完全不打扰。",
        checked = state.remindersEnabled,
        onChange = onRemindersEnabled,
    )
    Spacer(Modifier.height(12.dp))
    SwitchRow(
        title = "每日汇总",
        subtitle = "每天早上把当天的安排汇总成一条通知。",
        checked = state.dailyBriefingEnabled,
        onChange = { onDailyBriefing(it, state.dailyBriefingMinute) },
    )
    Spacer(Modifier.height(8.dp))
    FieldLabel("汇总时间：${Timetable.format(state.dailyBriefingMinute)}")
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        for (minute in listOf(6 * 60, 7 * 60 + 30, 8 * 60, 12 * 60, 20 * 60)) {
            FilterChip(
                selected = state.dailyBriefingMinute == minute,
                onClick = { onDailyBriefing(state.dailyBriefingEnabled, minute) },
                label = { Text(Timetable.format(minute)) },
                modifier = Modifier.padding(end = 4.dp),
            )
        }
    }

    Spacer(Modifier.height(14.dp))
    FieldLabel("提前多久提醒")
    Spacer(Modifier.height(4.dp))
    LeadRow("课程", state.courseLeadMinutes) { onLeadMinutes(it, state.eventLeadMinutes) }
    LeadRow("其他", state.eventLeadMinutes) { onLeadMinutes(state.courseLeadMinutes, it) }
    Spacer(Modifier.height(8.dp))
    Text(
        "提醒默认只排未来 7 天，靠「每天重排 + 每次打开 App 重排」续期。" +
            "如果手机长期不开机也不打开 App，第 8 天起的提醒不会响——这是 Android 对后台任务的限制。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 数据详情页：备份导入导出 + 示例数据开关。 */
@Composable
private fun DataSettingsPage(
    state: UiState,
    onExport: () -> String,
    onImport: (String) -> Unit,
    onSeedSampleData: (Boolean) -> Unit,
    onLoadSample: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var backupDraft by remember { mutableStateOf("") }
    // 剪贴板为空 / 内容不像备份时的提示。不做成"点了没反应"，那是最难查的体验问题。
    var pasteHint by remember { mutableStateOf("") }
    // 文件导入/导出的结果提示（与剪贴板提示分开，避免互相覆盖）。
    var fileHint by remember { mutableStateOf("") }
    // 文件导出要先拿到字符串再交给系统文件选择器。
    var pendingExport by remember { mutableStateOf("") }
    // 大备份不能塞进 OutlinedTextField：几 MB 的 base64 会让 Compose 排版卡死（真机 ANR）。
    // 所以文件导入时把原文放这里，文本框只用于手改/小备份。
    var pendingBackupText by remember { mutableStateOf("") }
    // 导入前先给一份「要导入什么」的确认，避免误把旧备份盖上去。
    var pendingImport by remember { mutableStateOf<Backup.ImportResult?>(null) }

    val createBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(pendingExport.toByteArray(Charsets.UTF_8))
                    } ?: error("没有拿到输出流")
                }.isSuccess
            }
            fileHint = if (ok) "备份文件已保存（含原图）" else "保存失败，换个位置再试"
        }
    }

    val openBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("没有拿到输入流")
                }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                fileHint = "读取失败，或文件是空的"
            } else {
                // 备份带原图时可能有几 MB；解析（JSON + base64 解码）必须离开主线程，
                // 否则会 ANR——这是真机上实测到的（「课隙无响应」）。
                val parsed = withContext(Dispatchers.IO) { Backup.parse(text) }
                // 不把全文塞进文本框：大备份会让 Compose 排版卡死。
                backupDraft = ""
                pendingBackupText = text
                pendingImport = parsed
                fileHint = "已读取 ${text.length / 1024} KB（含原图），确认后导入"
            }
        }
    }

    FieldLabel("备份（JSON）")
    Spacer(Modifier.height(4.dp))
    Text(
        "导出后自己保存好。换手机时把它导回来即可恢复——包括课程、行程、改期记录、" +
            "一手资料的原图，以及作息表、类别、外观、提醒提前量这些设置。" +
            "原图会以 base64 一起写进 JSON，所以文件会明显变大，建议用「导出到文件」。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = {
            val text = onExport()
            pendingExport = text
            // 大备份不要塞进文本框，也不要硬塞剪贴板（可能超 Binder 限制）。
            if (text.length <= 200_000) {
                backupDraft = text
                fileHint = ""
                runCatching { clipboard.setText(AnnotatedString(text)) }
                    .onFailure { fileHint = "复制失败，请改用「导出到文件」" }
            } else {
                backupDraft = ""
                fileHint = "备份约 ${text.length / 1024 / 1024} MB（含原图），已生成；请用「导出到文件」"
            }
        }) { Text("复制到剪贴板") }
        Spacer(Modifier.width(10.dp))
        Button(onClick = {
            pendingExport = onExport()
            createBackup.launch("kkexi-backup-${java.time.LocalDate.now()}.json")
        }) { Text("导出到文件") }
    }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = backupDraft,
        onValueChange = { backupDraft = it },
        label = { Text("备份 JSON（粘贴到这里再点导入）") },
        modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
    )
    Spacer(Modifier.height(6.dp))
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        OutlinedButton(
            onClick = { openBackup.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
        ) { Text("从文件导入") }
        Spacer(Modifier.width(10.dp))
        // 手长按输入框再选「粘贴」在手机上很别扭，给一个按钮。
        OutlinedButton(
            onClick = {
                val text = clipboard.getText()?.text.orEmpty()
                when {
                    text.isBlank() -> pasteHint = "剪贴板是空的"
                    // 先粗筛一下：避免把一段无关文字粘进来后才在导入时才发现
                    !text.contains("kkexi-backup") ->
                        pasteHint = "剪贴板里的内容不像备份文件（缺少 kkexi-backup 标记）"
                    else -> {
                        if (text.length > 200_000) {
                            // 同上：大备份只进内存，不进文本框。
                            backupDraft = ""
                            pendingBackupText = text
                            pasteHint = ""
                            fileHint = "备份较大（${text.length / 1024} KB），已读取，不显示全文"
                            scope.launch {
                                pendingImport = withContext(Dispatchers.IO) { Backup.parse(text) }
                            }
                        } else {
                            backupDraft = text
                            pendingBackupText = ""
                            pasteHint = ""
                            fileHint = ""
                        }
                    }
                }
            },
        ) { Text("从剪贴板粘贴") }
        Spacer(Modifier.width(10.dp))
        OutlinedButton(
            enabled = backupDraft.isNotBlank() || pendingBackupText.isNotBlank(),
            onClick = {
                val source = pendingBackupText.ifEmpty { backupDraft }
                scope.launch {
                    // 同 openBackup：含原图的备份可能有几 MB，解析不能放主线程。
                    pendingImport = withContext(Dispatchers.IO) { Backup.parse(source) }
                    pasteHint = ""
                }
            },
        ) { Text("导入") }
        Spacer(Modifier.width(10.dp))
        TextButton(onClick = {
            val text = onExport()
            pendingExport = text
            if (text.length <= 200_000) {
                backupDraft = text
            } else {
                backupDraft = ""
                fileHint = "备份约 ${text.length / 1024 / 1024} MB（含原图），已生成；请用「导出到文件」"
            }
        }) { Text("只生成不复制") }
    }
    if (pasteHint.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text(
            pasteHint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (fileHint.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text(
            fileHint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    val confirm = pendingImport
    if (confirm != null) {
        val hasSettings = !confirm.settings.isEmpty
        AlertDialog(
            onDismissRequest = {
                pendingImport = null
                pendingBackupText = ""
            },
            title = { Text("确认导入") },
            text = {
                Column {
                    Text(
                        "将写入 ${confirm.events.size} 条日程" +
                            if (confirm.exceptions.isEmpty()) "" else "、${confirm.exceptions.size} 条改期记录",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (confirm.images.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "含 ${confirm.images.size} 张一手资料原图（会写回本机，日程可继续查看原图）。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (hasSettings) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "备份里还带设置：作息表、类别、外观、提醒提前量。导入会覆盖这些设置。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "同 id 的条目会被覆盖，其它条目保持不变（是合并，不是清空重来）。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (confirm.errors.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${confirm.errors.size} 条解析失败会被跳过：" + confirm.errors.first(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    onImport(pendingBackupText.ifEmpty { backupDraft })
                    pendingImport = null
                    pendingBackupText = ""
                    backupDraft = ""
                }) { Text("确认导入") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingImport = null
                    pendingBackupText = ""
                }) { Text("取消") }
            },
        )
    }

    Spacer(Modifier.height(18.dp))
    HorizontalDivider()
    Spacer(Modifier.height(14.dp))
    FieldLabel("示例数据")
    Spacer(Modifier.height(4.dp))
    Text(
        "「示例数据」是一份空壳课表，用来先看看效果，不含任何人的真实课程。" +
            "它只在首次安装时灌入，平时默认关闭。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    SwitchRow(
        title = "首次安装时灌入示例数据",
        subtitle = "想让新装的人一打开就有东西看，就打开它。分享 APK 前请保持关闭。",
        checked = state.seedSampleData,
        onChange = onSeedSampleData,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onLoadSample) { Text("现在导入示例课表") }
}

/** 关于页：纯说明，不放任何个人与学校信息。 */
@Composable
private fun AboutPage() {
    Column {
        Text("课隙", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text("一个本地日程本", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        Text(
            "把课表、行程、截止日期整理成一条时间轴，到点离线提醒你。\n\n" +
                "· 数据全部存在这台手机上，不需要账号，不上传，不做统计。\n" +
                "· 唯一会联网的功能是 AI 助手，可以随时关掉；关掉后完全离线。\n" +
                "· API Key 只存在本机，只发往你自己填的服务商地址。\n" +
                "· 提醒有提前量，课程和其他事项可以分别设置。",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "AI 读图会出错（实测真实课表会串列：把某一列的课归到相邻的星期），" +
                "所以读出来的结果一律要人工逐条核对后才写入。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ==================================================================== 服务商 UI


/** 新增/修改一份服务商配置。预设只是帮你填好地址与模型名，key 永远要自己填。 */
@Composable
private fun ProviderForm(
    initial: AiProvider?,
    onCancel: () -> Unit,
    onSave: (AiProvider) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var draft by remember(initial?.id) {
        mutableStateOf(initial ?: AiProvider.presets().first())
    }

    Column {
        Text("从预设开始（可再改）", style = MaterialTheme.typography.labelSmall)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            for (preset in AiProvider.presets()) {
                FilterChip(
                    selected = draft.model == preset.model && draft.baseUrl == preset.baseUrl,
                    onClick = {
                        // 保留已经填过的 key，只换地址与模型名。
                        draft = preset.copy(apiKey = draft.apiKey, id = draft.id)
                    },
                    label = { Text(preset.name) },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = draft.name,
            onValueChange = { draft = draft.copy(name = it) },
            label = { Text("名字（自己认得出就行）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = draft.baseUrl,
            onValueChange = { draft = draft.copy(baseUrl = it) },
            label = { Text("接口地址（OpenAI 兼容）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = draft.apiKey,
            onValueChange = { draft = draft.copy(apiKey = it) },
            label = { Text("API Key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = draft.model,
            onValueChange = { draft = draft.copy(model = it) },
            label = { Text("模型名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        FieldLabel("思考强度")
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            for (effort in AiProvider.EFFORT_VALUES) {
                FilterChip(
                    selected = draft.reasoningEffort == effort,
                    onClick = { draft = draft.copy(reasoningEffort = effort) },
                    label = { Text(AiProvider.effortLabel(effort)) },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }
        Text(
            "合法值是实测出来的：传 `off` 会被服务端拒绝（422），要关掉思考请用 `none`。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))
        FieldLabel("Temperature：${"%.1f".format(draft.temperature)}")
        // 去掉 steps 的刻度点：14 个 tick 在这个尺寸下会连成一条虚线，很吵。
        // 取值仍然按 0.1 吸附（在 onValueChange 里做），功能不变。
        Slider(
            value = draft.temperature.toFloat(),
            onValueChange = { draft = draft.copy(temperature = (it * 10).roundToInt() / 10.0) },
            valueRange = 0f..1.5f,
        )
        Text(
            "0 最稳定，推荐保持 0。抽日程、改时间这类任务需要确定性，调高会出现同样的话每次拆得不一样。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = draft.supportsVision,
                onCheckedChange = { draft = draft.copy(supportsVision = it) },
            )
            Column {
                Text("这个模型支持图片输入", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "勾了才会用它读图。注意：能读图 ≠ 读得准——实测真实课表会串列，所以读出来的结果一律要人工核对。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = draft.apiKey.isNotBlank() && draft.model.isNotBlank(),
                onClick = {
                    val id = draft.id.ifEmpty { "p-" + System.currentTimeMillis().toString(36) }
                    onSave(draft.copy(id = id, name = draft.name.ifEmpty { draft.model }))
                },
            ) { Text("保存并设为当前") }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onCancel) { Text("取消") }
            Spacer(Modifier.weight(1f))
            // 删除放在详情页里：概览页只有名字，误删的可能性更小。
            if (onDelete != null) {
                TextButton(onClick = onDelete) {
                    Text("删除这份配置", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** 一行「第 N 节 [开始] —— [结束] 保存」。 */
@Composable
private fun PeriodEditorRow(
    slot: PeriodSlot,
    onSave: (String, String) -> Unit,
) {
    var start by remember(slot.period, slot.startMinute) { mutableStateOf(slot.startLabel) }
    var end by remember(slot.period, slot.endMinute) { mutableStateOf(slot.endLabel) }
    val changed = start != slot.startLabel || end != slot.endLabel

    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("第 ${slot.period} 节", Modifier.width(64.dp), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = start,
            onValueChange = { start = it },
            singleLine = true,
            modifier = Modifier.width(96.dp),
        )
        Text("—", Modifier.padding(horizontal = 6.dp))
        OutlinedTextField(
            value = end,
            onValueChange = { end = it },
            singleLine = true,
            modifier = Modifier.width(96.dp),
        )
        Spacer(Modifier.width(6.dp))
        Button(enabled = changed, onClick = { onSave(start, end) }) {
            Text(if (changed) "保存" else "已保存")
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LeadRow(label: String, current: Int, onPick: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(72.dp), style = MaterialTheme.typography.bodyMedium)
        for (minutes in listOf(5, 10, 15, 30, 60)) {
            val selected = current == minutes
            TextButton(onClick = { onPick(minutes) }) {
                Text(
                    "${minutes}分",
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}
