package com.ww.kkexi

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒绝也不影响主功能 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Android 13+ 通知权限：不给也不崩，只是不弹通知。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            // 主题必须在能读到 UiState 的地方套上——否则「跟随系统/浅色/深色」
            // 与动态取色这两个设置改了界面也不会变（第一版我把主题写在外层，
            // 结果改成全局变量硬塞，既不触发重组也不受设置控制）。
            val viewModel: AppViewModel = viewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()
            // 系统栏图标明暗必须跟着 **App 自己的** 深浅色走，不能只跟系统走。
            // values-night 只在系统深色时生效；用户把 App 强制成浅色、系统仍是深色时，
            // 状态栏会保留白色图标叠在浅色背景上——真机截图里 12:23 几乎看不见。
            val dark = state.isDarkTheme
            LaunchedEffect(dark) {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            KkexiTheme(
                darkTheme = state.isDarkTheme,
                dynamicColor = state.dynamicColor,
            ) {
                AppRoot(
                    viewModel = viewModel,
                    state = state,
                    requestExactAlarmPermission = { requestExactAlarm() },
                )
            }
        }
    }

    /** Android 12+ 的「闹钟与提醒」是特殊权限，必须跳到系统页面让用户手动开。 */
    private fun requestExactAlarm() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val manager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (manager.canScheduleExactAlarms()) return
        try {
            startActivity(
                Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).setData(
                    Uri.fromParts("package", packageName, null)
                )
            )
        } catch (_: Exception) {
            // 个别 ROM 没有这个页面：忽略，Alarms 已退化为非精确闹钟。
        }
    }
}

private enum class Tab(val label: String) {
    TIMELINE("时间轴"),
    WEEK("课程表"),
    ASSISTANT("AI 助手"),
    SETTINGS("设置"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(
    viewModel: AppViewModel,
    state: UiState,
    requestExactAlarmPermission: () -> Unit,
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    var tab by remember { mutableStateOf(Tab.TIMELINE) }
    var showNewEvent by remember { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<Session?>(null) }

    // 每次进入前台都重排闹钟：滚动窗口只有 7 天，靠这一步把它续上。
    LaunchedEffect(Unit) {
        val repository = Repository(context)
        Alarms.ensureChannels(context)
        Alarms.reschedule(context)
        if (!Alarms.canScheduleExact(context)) requestExactAlarmPermission()
        // 引用一下只是为了确保 Repository 被构造，无副作用。
        repository.allEvents().size
    }

    LaunchedEffect(state.toast) {
        state.toast?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeToast()
        }
    }

    Scaffold(
        // 键盘弹起时**整块**上移（含底部导航栏）。
        //
        // 踩过的坑：第一版把 imePadding 只加在 AI 输入区，结果输入框是浮上去了，
        // 但底部导航栏仍被键盘压住——用户打字的时候切不了 Tab，必须先收键盘。
        // 放在 Scaffold 这一层就只有一个来源，不会重复垫高。
        modifier = Modifier.imePadding(),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                for (item in Tab.entries) {
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = {
                            Text(
                                when (item) {
                                    Tab.TIMELINE -> "☰"
                                    Tab.WEEK -> "▦"
                                    Tab.ASSISTANT -> "✦"
                                    Tab.SETTINGS -> "⚙"
                                },
                                style = MaterialTheme.typography.titleMedium,
                            )
                        },
                        label = { Text(item.label) },
                    )
                }
            }
        },
        floatingActionButton = {
            // 只在时间轴/课程表两页显示新建按钮，**空库时也要显示**。
            //
            // 这里修过一个自相矛盾的 bug：曾经把可见性耦合到 `!state.libraryEmpty`，
            // 于是空库时按钮消失，而引导页第 1 条却写着「点右下角 ＋ 手动加一条」——
            // 用户按提示去找，找不到按钮。「纯净版」的意思是**不要自动灌数据**，
            // 不是**不要新建按钮**。引导页底部已留 100dp，不会再压住文字。
            //
            // 搜索时也隐藏：那时用户在做「找已有的事」，按钮会盖住搜索结果，
            // 而且「新建」不是他此刻想做的事。
            if ((tab == Tab.TIMELINE || tab == Tab.WEEK) && state.searchQuery.isEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { showNewEvent = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("新建") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (!state.ready) {
                Text("正在准备数据…", Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium)
            } else {
                when (tab) {
                    Tab.TIMELINE -> TimelineScreen(
                        state = state,
                        onToggleHistory = viewModel::toggleHistory,
                        onToggleDone = viewModel::setDone,
                        onLongPress = { actionTarget = it },
                        onImportSample = viewModel::loadSampleData,
                        onOpenAssistant = { tab = Tab.ASSISTANT },
                        onSearch = viewModel::search,
                        onClearSearch = viewModel::clearSearch,
                    )

                    Tab.WEEK -> WeekScreen(
                        state = state,
                        onWeekChange = viewModel::setWeek,
                        onLongPress = { actionTarget = it },
                    )

                    Tab.ASSISTANT -> AssistantScreen(
                        state = state,
                        onSend = viewModel::sendMessage,
                        onAttachImage = { uri -> viewModel.attachImage(uri, context.contentResolver) },
                        onAttachFile = { uri, name -> viewModel.attachFile(uri, name, context.contentResolver) },
                        onRemoveAttachment = viewModel::removeAttachment,
                        onSendWithAttachments = viewModel::sendWithAttachments,
                        onAcceptCandidates = viewModel::acceptCandidates,
                        onDismissCandidates = viewModel::dismissCandidates,
                        onUpdateCandidate = viewModel::updateCandidate,
                        onRemoveCandidate = viewModel::removeCandidate,
                        onSetEffort = viewModel::setEffort,
                        onClearChat = viewModel::clearChat,
                        onToggleReasoning = viewModel::toggleReasoning,
                        onOpenSettings = { tab = Tab.SETTINGS },
                    )

                    Tab.SETTINGS -> SettingsScreen(
                        state = state,
                        onAiEnabled = viewModel::setAiEnabled,
                        onSaveProvider = viewModel::saveProvider,
                        onDeleteProvider = viewModel::deleteProvider,
                        onSetActiveProvider = viewModel::setActiveProvider,
                        onTestConnection = viewModel::testAiConnection,
                        onApplyTimetablePreset = viewModel::applyTimetablePreset,
                        onUpdatePeriod = viewModel::updatePeriod,
                        onResetTimetable = viewModel::resetTimetable,
                        onAddCategory = viewModel::addCategory,
                        onRemoveCategory = viewModel::removeCategory,
                        onResetCategories = viewModel::resetCategories,
                        onSeedSampleData = viewModel::setSeedSampleData,
                        onLoadSample = viewModel::loadSampleData,
                        onRemindersEnabled = viewModel::setRemindersEnabled,
                        onDailyBriefing = viewModel::setDailyBriefing,
                        onLeadMinutes = viewModel::setLeadMinutes,
                        onExport = viewModel::exportJson,
                        onImport = viewModel::importJson,
                        onThemeMode = viewModel::setThemeMode,
                        onDynamicColor = viewModel::setDynamicColor,
                    )
                }
            }
        }
    }

    if (showNewEvent) {
        NewEventDialog(
            today = LocalDate.now(),
            timetable = state.timetable,
            categories = state.categories,
            onDismiss = { showNewEvent = false },
            onConfirm = { title, kind, category, date, spec, location, note, recurrence ->
                viewModel.createSingleEvent(title, kind, category, date, spec, location, note, recurrence)
                showNewEvent = false
            },
        )
    }

    val target = actionTarget
    if (target != null) {
        val seriesEvent = remember(target.id) {
            runCatching { Repository(context).event(target.eventId) }.getOrNull()
        }
        SessionActionSheet(
            session = target,
            seriesEvent = seriesEvent,
            timetable = state.timetable,
            onDismiss = { actionTarget = null },
            onCancelOccurrence = viewModel::cancelOccurrence,
            onMove = { session, date, spec, location ->
                viewModel.moveOccurrence(session, date, spec, location)
            },
            onMakeup = { session, date, spec ->
                viewModel.addMakeup(session, date, spec)
            },
            onUpdateSeries = viewModel::updateSeries,
            onRevert = viewModel::revertOccurrence,
            onDeleteEvent = viewModel::deleteEvent,
        )
    }
}
