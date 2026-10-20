package com.ww.kkexi

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** 时间轴上的一天。 */
data class DayGroup(val date: LocalDate, val header: String, val sessions: List<Session>, val conflicts: Set<String>)

/**
 * AI 助手页的一条消息。
 *
 * 形状参考了 leavesCZY/compose_chat（Apache-2.0）里 `Message` 的 sealed class 设计：
 * 它把 TextMessage 与 ImageMessage 分开建模，图片消息**自带预览地址**。
 * 我第一版只有 `(fromUser, text)` 两个字段，所以图片根本没地方放——
 * 用户就反馈了「图片我就看不见上下文」。现在补上。
 */
data class ChatMessage(
    val fromUser: Boolean,
    val text: String,
    /** 模型给出的思考过程（可能为空）。折叠显示，但会被存进会话历史。 */
    val reasoning: String = "",
    /** 本地图片路径（用户发的图片消息）。为空表示纯文字消息。 */
    val imagePath: String = "",
    /** 附件类型标签，例如 "图片"、"PPT"。 */
    val attachmentLabel: String = "",
    /** 图片附件识别出来的原文，核对候选时用。 */
    val attachmentText: String = "",
    /**
     * 这条回复花掉的 token（用户反馈要能看到消耗）。
     *
     * 三个数都记：prompt 是发过去的（含日程上下文，所以会随数据变多而变大），
     * completion 是模型吐出来的，reasoning 是其中「思考」占的部分。
     */
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val reasoningTokens: Int = 0,
) {
    val totalTokens: Int get() = promptTokens + completionTokens
    val hasUsage: Boolean get() = totalTokens > 0
}

/**
 * 待发送的附件（已选、还没发）。
 *
 * 用户反馈：「我一点图片，它就自动发送了，我没法图片和文字同时发送」。
 * 所以图片先落到这里，在输入框上方显示缩略图，可以删掉，也可以配一句话再发。
 */
data class PendingAttachment(
    val id: String,
    val uri: android.net.Uri,
    val displayName: String,
    val isImage: Boolean,
)

data class UiState(
    val ready: Boolean = false,
    val today: LocalDate = LocalDate.now(),
    val timeline: List<DayGroup> = emptyList(),
    val deadlines: List<Session> = emptyList(),
    val historyCollapsed: Boolean = true,
    val history: List<DayGroup> = emptyList(),
    val week: Int = 1,
    val weekDays: List<LocalDate> = (0..6).map { Semester.mondayOf(1).plusDays(it.toLong()) },
    val weekSessions: Map<LocalDate, List<Session>> = emptyMap(),
    val unverifiedNotes: List<String> = emptyList(),
    val aiEnabled: Boolean = false,
    /** 多份服务商配置（用户反馈第 6 条：像 cc switch 那样能配几个、随时切）。 */
    val aiProviders: List<AiProvider> = emptyList(),
    val activeProviderId: String = "",
    /** 当前有没有可用来读图的服务商（配好的 + 勾了支持图片）。 */
    val visionReady: Boolean = false,
    val seedSampleData: Boolean = false,
    /** 库里是否一条日程都没有——用来决定显示「纯净版」的引导页。 */
    val libraryEmpty: Boolean = true,
    /** 库里主规则的条数（设置页「数据」入口的摘要用）。 */
    val eventCount: Int = 0,
    /** 当前生效的作息表。界面上所有「几点到几点」都由它算出来。 */
    val timetable: Timetable = Timetable.DEFAULT,

    /** 用户自定义的类别列表（设置页可编辑，新建对话框直接用它）。 */
    val categories: List<String> = Settings.DEFAULT_CATEGORIES,
    val categoriesAreDefault: Boolean = true,
    /** 作息表是不是还是内置默认那份（界面据此显示「默认」还是「已自定义」）。 */
    val timetableIsDefault: Boolean = true,
    val dailyBriefingMinute: Int = 7 * 60 + 30,
    val dailyBriefingEnabled: Boolean = true,
    val courseLeadMinutes: Int = 15,
    val eventLeadMinutes: Int = 30,
    val exactAlarmAllowed: Boolean = true,
    /** 提醒总开关（Q18 答复：「最好是可以设置能响和不能响的」）。 */
    val remindersEnabled: Boolean = true,

    /** 外观：跟随系统 / 强制浅色 / 强制深色。 */
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** 是否用系统的动态取色（Material You，Android 12+）。 */
    val dynamicColor: Boolean = false,
    /** 深色模式是否生效（已把 themeMode 与系统当前模式合成一个结果）。 */
    val isDarkTheme: Boolean = false,
    /**
     * 时间轴默认停在哪一天（Q27-A 的落地）。
     *
     * 用户 2026-10-14 追答的口径是「默认选 3 天之后，但那天有课才是今天（不然往后顺延）」，
     * 这里取它的本意：**从有事的最近一天开始看**——今天有事就是今天，
     * 今天没事就顺延到下一个有事的日子，并把中间空档的天数写在标题里。
     */
    val anchorDate: LocalDate = LocalDate.now(),
    /** 从今天到 [anchorDate] 之间跳过了几天（0 表示就停在今天）。 */
    val skippedDays: Int = 0,

    /**
     * 搜索。
     *
     * 为什么加：日程条目一多，只能靠时间轴一天天翻——而人找东西时是「记得名字」，
     * 不是「记得哪天」。搜的是**整个学期**（不是屏幕上那 44 天），
     * 所以三周前那条「交作业」也能搜到。
     */
    val searchQuery: String = "",
    val searchResults: List<DayGroup> = emptyList(),
    /** 搜索命中的条数（分组前），用于提示「0 条」还是「很多条」。 */
    val searchHitCount: Int = 0,

    val aiBusy: Boolean = false,
    /** 正在流式输出的助手正文（逐字追加）。 */
    val streamingText: String = "",
    /** 正在流式输出的思考过程（逐字追加，可折叠显示）。 */
    val streamingReasoning: String = "",
    /** 正在执行的工具名，用于显示「正在查日程…」这类状态。 */
    val streamingTool: String = "",
    /** 是否展开思考过程。 */
    val showReasoning: Boolean = true,
    /** 当前服务商的显示名（AI 助手页顶部展示）。 */
    val activeProviderName: String = "",
    /** 可选的思考等级——取自当前服务商配置里列出的那些。 */
    val availableEfforts: List<String> = emptyList(),
    /** 当前思考等级。 */
    val currentEffort: String = "",
    /** AI 助手首屏的问候语（含今天安排）。 */
    val greeting: String = "",
    /**
     * 待发送的附件（已选、还没发）。
     *
     * 用户反馈：「我一点图片，它就自动发送了，我没法图片和文字同时发送」。
     * 所以选完先进这里，界面上显示缩略图，可以删、可以配文字，最后一起发。
     */
    val pendingAttachments: List<PendingAttachment> = emptyList(),
    val aiCandidates: List<LlmClient.Candidate> = emptyList(),
    val aiRawText: String = "",
    val chat: List<ChatMessage> = emptyList(),
    val toast: String? = null,
    val backupJson: String = "",
)

/**
 * 唯一的界面状态持有者。
 *
 * 所有写操作都走「写库 → refresh()」这条固定路径，避免界面出现只改内存不改库的假状态。
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = Repository(application)
    private val settings = Settings(application)
    private val aiConfig = AiConfigStore(application)
    private val chatStore = ChatStore(application)

    /**
     * 用户最近一次发来的**原始内容**。
     *
     * 给 `propose_events` 的「依据核对」用：模型摘的 evidence 必须真的出现在这段原文里。
     * 读图时是空串（图片没有可比对的文本），此时校验会如实标注「无法机器核对」。
     */
    private var lastUserSourceText: String = ""

    /**
     * 这一轮解析用的原图路径（没发图时为空）。
     *
     * 写进 [Event.sourceImagePath]，让用户在时间轴点开这条日程时能回看原图——
     * 这正是用户要的「点击时间轴的安排时，有一个看我上传的一手资料的选项」。
     */
    private var lastSourceImagePath: String = ""
    private val llm = LlmClient(aiConfig, settings) { name, arguments ->
        executeTool(name, arguments)
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // 只有用户明确要样例数据时才灌（默认留空，这样分享给同学的是纯净版）。
                // 已经装过 App 的人库里已有数据，这里不会重复灌，也不会覆盖。
                if (settings.seedSampleData) repository.seedIfEmpty()
                Alarms.ensureChannels(getApplication())
                Alarms.reschedule(getApplication())
            }
            refresh()
        }
    }

    // ---------------------------------------------------------------- 读

    fun refresh() {
        viewModelScope.launch {
            val today = LocalDate.now()
            val loaded = withContext(Dispatchers.IO) {
                val horizon = settings.horizonDays
                val upcoming = repository.sessions(today, today.plusDays(horizon.toLong()))
                val deadlines = repository.pendingDeadlines(today)
                val week = Semester.weekOf(today)
                val weekSessions = repository.weekSessions(week)
                val past = repository.sessions(today.minusDays(14), today.minusDays(1))
                val eventCount = repository.allEvents().size
                val todaySessions = repository.sessionsOn(today).filter { it.state != SessionState.CANCELLED }
                Loaded(upcoming, deadlines, week, weekSessions, past, eventCount, todaySessions)
            }

            val futureGroups = groupByDay(loaded.upcoming, today)
            val historyGroups = groupByDay(loaded.past.reversed(), today)

            // Q27-A：默认停在「有事的最早一天」，无事可做时不把空白页砸给用户。
            val anchor = futureGroups.firstOrNull()?.date ?: today
            val skipped = java.time.temporal.ChronoUnit.DAYS.between(today, anchor).toInt()

            _state.value = _state.value.copy(
                ready = true,
                today = today,
                timeline = futureGroups,
                anchorDate = anchor,
                skippedDays = if (skipped > 0) skipped else 0,
                deadlines = loaded.deadlines,
                week = loaded.week,
                weekDays = (0..6).map { Semester.mondayOf(loaded.week).plusDays(it.toLong()) },
                weekSessions = loaded.weekSessions,
                history = historyGroups,
                historyCollapsed = _state.value.historyCollapsed,
                unverifiedNotes = SeedData.unverifiedNotes,
                aiEnabled = settings.aiEnabled,
                aiProviders = aiConfig.all(),
                activeProviderId = aiConfig.active()?.id.orEmpty(),
                activeProviderName = aiConfig.active()?.name.orEmpty(),
                availableEfforts = buildEffortOptions(aiConfig.active()?.reasoningEffort.orEmpty()),
                currentEffort = aiConfig.active()?.reasoningEffort.orEmpty(),
                greeting = DailyGreeting.build(today, loaded.todaySessions),
                visionReady = aiConfig.visionCapable() != null,
                seedSampleData = settings.seedSampleData,
                libraryEmpty = loaded.eventCount == 0,
                eventCount = loaded.eventCount,
                timetable = settings.timetable,
                categories = settings.categories,
                categoriesAreDefault = settings.isUsingDefaultCategories(),
                timetableIsDefault = settings.isUsingDefaultTimetable(),
                dailyBriefingMinute = settings.dailyBriefingMinute,
                dailyBriefingEnabled = settings.dailyBriefingEnabled,
                courseLeadMinutes = settings.courseLeadMinutes,
                eventLeadMinutes = settings.eventLeadMinutes,
                exactAlarmAllowed = Alarms.canScheduleExact(getApplication()),
                remindersEnabled = settings.remindersEnabled,
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
                isDarkTheme = isDarkNow(settings.themeMode),
                // 会话历史从本地库读回来——用户反馈「会话历史可保存」，之前退出就没了。
                // 只在内存里还没有对话时加载，避免把正在流式输出的内容冲掉。
                chat = if (_state.value.chat.isEmpty()) {
                    chatStore.all().map {
                        ChatMessage(
                            fromUser = it.fromUser,
                            text = it.text,
                            reasoning = it.reasoning,
                            // token 用量一起读回来：用户要「回看每次花了多少」，
                            // 只存内存的话重启就没了。
                            promptTokens = it.promptTokens,
                            completionTokens = it.completionTokens,
                            reasoningTokens = it.reasoningTokens,
                        )
                    }
                } else {
                    _state.value.chat
                },
            )
        }
    }

    private data class Loaded(
        val upcoming: List<Session>,
        val deadlines: List<Session>,
        val week: Int,
        val weekSessions: Map<LocalDate, List<Session>>,
        val past: List<Session>,
        val eventCount: Int,
        val todaySessions: List<Session>,
    )

    /**
     * 思考等级的可选项。
     *
     * 用**实测确认过的**完整值域，而不是凭印象写几个。
     * 证据：给 DeepSeek 传 `off` 时服务端 422，报错原文列出了
     * `none, minimal, low, medium, high, xhigh, ultra, max`；
     * 且实测 none → reasoning_tokens 0、low → 385、medium → 732，说明这个参数真生效。
     */
    private fun buildEffortOptions(current: String): List<String> =
        AiProvider.EFFORT_VALUES.filter { it.isNotEmpty() }.let { values ->
            if (current.isNotEmpty() && current !in values) values + current else values
        }

    /** 按天分组，并算出每一条是否与其他发生时间重叠（Q26：只标不拦）。 */
    private fun groupByDay(sessions: List<Session>, today: LocalDate): List<DayGroup> {
        val byDate = sessions.groupBy { it.date }
        return byDate.keys.sorted().map { date ->
            val daySessions = byDate.getValue(date)
            val conflicts = HashSet<String>()
            for (i in daySessions.indices) {
                for (j in i + 1 until daySessions.size) {
                    val a = daySessions[i]
                    val b = daySessions[j]
                    if (a.state == SessionState.CANCELLED || b.state == SessionState.CANCELLED) continue
                    if (a.startMinute < b.endMinute && b.startMinute < a.endMinute) {
                        conflicts += a.id
                        conflicts += b.id
                    }
                }
            }
            DayGroup(date, DateTimeExt.dayHeader(date, today), daySessions, conflicts)
        }
    }

    // ---------------------------------------------------------------- 搜索

    /**
     * 按关键字搜整个学期的日程。
     *
     * 匹配标题、地点、描述、教师、类别——就是用户会记得的那几个字段。
     * 空查询直接清空结果（不查库）。
     *
     * 为什么搜「整个学期」而不是只搜已加载的时间轴：
     * 时间轴只装未来 30 天 + 过去 14 天，而人找东西时想的是「上次那个交作业是哪天」，
     * 那条可能在两周以前。窗口太小会给出「没有结果」的错误结论。
     */
    fun search(rawQuery: String) {
        val query = rawQuery.trim()
        _state.value = _state.value.copy(searchQuery = query)
        if (query.isEmpty()) {
            _state.value = _state.value.copy(searchResults = emptyList(), searchHitCount = 0)
            return
        }
        viewModelScope.launch {
            val today = LocalDate.now()
            val hits = withContext(Dispatchers.IO) {
                // 学期区间：锚点往前留 2 周、往后铺满整个学期，覆盖所有可能的日子
                val from = Semester.firstMonday.minusDays(14)
                val to = Semester.firstMonday.plusDays(Semester.totalWeeks * 7L + 7)
                repository.sessions(from, to).filter { session ->
                    session.state != SessionState.CANCELLED && session.matches(query)
                }
            }
            // 查询可能在计算期间又变了，丢弃过期结果
            if (_state.value.searchQuery != query) return@launch
            _state.value = _state.value.copy(
                searchResults = groupByDay(hits, today),
                searchHitCount = hits.size,
            )
        }
    }

    fun clearSearch() {
        _state.value = _state.value.copy(searchQuery = "", searchResults = emptyList(), searchHitCount = 0)
    }

    // ---------------------------------------------------------------- 写

    fun toggleHistory() {
        _state.value = _state.value.copy(historyCollapsed = !_state.value.historyCollapsed)
    }

    fun setWeek(week: Int) {
        val clamped = week.coerceIn(1, Semester.totalWeeks)
        viewModelScope.launch {
            val map = withContext(Dispatchers.IO) { repository.weekSessions(clamped) }
            _state.value = _state.value.copy(
                week = clamped,
                weekDays = (0..6).map { Semester.mondayOf(clamped).plusDays(it.toLong()) },
                weekSessions = map,
            )
        }
    }

    fun saveEvent(event: Event) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.upsert(event)
                Alarms.reschedule(getApplication())
            }
            refresh()
        }
    }

    fun deleteEvent(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.deleteEvent(id)
                Alarms.reschedule(getApplication())
            }
            refresh()
        }
    }

    /**
     * 新建一条行程。
     *
     * [recurrence] 为空 = 只发生一次；非空 = 按周重复（整学期课程）。
     * 用户反馈（2026-10-19）：「不能设置一学期的课程，只会一节一节设置」——
     * 这个参数就是那句反馈的落点。
     */
    fun createSingleEvent(
        title: String,
        kind: EventKind,
        category: String,
        date: LocalDate,
        time: TimeSpec,
        location: String,
        note: String,
        recurrence: Recurrence? = null,
    ) {
        val id = "u-" + System.currentTimeMillis().toString(36)
        // 重复课程的「首次发生日」取规则的第 startWeek 周那一天，
        // 而不是用户点的那一天：否则从第 7 周建课、把起始周改成第 5 周时，
        // 第 5-6 周的课会被 anchorDate 截掉。
        val anchor = if (recurrence == null) {
            date
        } else {
            val firstWeek = recurrence.weeks.minOrNull() ?: recurrence.startWeek
            Semester.mondayOf(firstWeek).plusDays((recurrence.dayOfWeek.value - 1).toLong())
        }
        saveEvent(
            Event(
                id = id,
                title = title,
                kind = kind,
                category = category,
                location = location,
                note = note,
                anchorDate = anchor,
                time = time,
                recurrence = recurrence,
                source = "手动创建",
            )
        )
    }

    /** Q24 动作一：取消这一次。 */
    fun cancelOccurrence(session: Session) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.cancelOccurrence(session)
                Alarms.cancel(getApplication(), session)
                Alarms.reschedule(getApplication())
            }
            refresh()
            toast("已取消 ${DateTimeExt.formatDate(session.date)} 的这一次")
        }
    }

    /** Q24 动作二：调到别的时间。 */
    fun moveOccurrence(session: Session, toDate: LocalDate, time: TimeSpec, location: String?) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.moveOccurrence(session, toDate, time, location)
                Alarms.reschedule(getApplication())
            }
            refresh()
            toast("已调到 ${DateTimeExt.formatDate(toDate)}")
        }
    }

    /** Q24 动作三：这周不上但补课。 */
    fun addMakeup(session: Session, toDate: LocalDate, time: TimeSpec) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.cancelOccurrence(session, note = "本次取消，另安排补课")
                repository.upsertException(
                    EventException(
                        eventId = session.eventId,
                        originalDate = toDate,
                        state = SessionState.EXTRA,
                        movedToDate = toDate,
                        movedTime = time,
                        movedLocation = session.location,
                        note = "补课（原 ${session.date.monthValue}月${session.date.dayOfMonth}日）",
                    )
                )
                Alarms.reschedule(getApplication())
            }
            refresh()
            toast("已取消原课并加一节补课")
        }
    }

    /** 撤销覆盖，回到主规则。 */
    fun revertOccurrence(session: Session) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.revertOccurrence(session)
                Alarms.reschedule(getApplication())
            }
            refresh()
            toast("已恢复为主规则")
        }
    }

    /** Q23 动作四：改整条规则，默认「从本周起生效」。 */
    fun updateSeries(event: Event, fromWeek: Int, time: TimeSpec, location: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.updateSeries(event, fromWeek, time, location)
                Alarms.reschedule(getApplication())
            }
            refresh()
            toast("已从第 $fromWeek 周起修改")
        }
    }

    fun setDone(session: Session, done: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.setDone(session, done) }
            refresh()
        }
    }

    // ---------------------------------------------------------------- 设置

    fun setAiEnabled(enabled: Boolean) {
        settings.aiEnabled = enabled
        _state.value = _state.value.copy(aiEnabled = enabled)
    }

    // ---------------------------------------------------------------- AI 服务商（多份配置）

    /** 新增或覆盖一份服务商配置。 */
    fun saveProvider(provider: AiProvider) {
        val list = aiConfig.add(provider)
        aiConfig.setActive(provider.id)
        _state.value = _state.value.copy(
            aiProviders = list,
            activeProviderId = provider.id,
        )
        toast("已保存「${provider.name}」并切换为当前使用")
    }

    fun deleteProvider(id: String) {
        val list = aiConfig.remove(id)
        _state.value = _state.value.copy(
            aiProviders = list,
            activeProviderId = aiConfig.activeId(),
        )
        toast("已删除该服务商配置")
    }

    fun setActiveProvider(id: String) {
        aiConfig.setActive(id)
        _state.value = _state.value.copy(activeProviderId = id)
        val name = aiConfig.all().firstOrNull { it.id == id }?.name ?: id
        toast("已切换到「$name」")
    }

    /**
     * 连接自检：结果直接弹出来，让用户当场知道配对了没有。
     * 报出服务商、模型、思考强度是否被接受。
     */
    fun testAiConnection() {
        if (aiConfig.active() == null) {
            toast("先填 API Key 与模型名，并打开 AI 开关")
            return
        }
        _state.value = _state.value.copy(aiBusy = true)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { llm.ping() }
            _state.value = _state.value.copy(aiBusy = false)
            toast(
                if (result.isSuccess) result.getOrNull().orEmpty()
                else "连接失败：${result.exceptionOrNull()?.message}"
            )
        }
    }

    /**
     * 导入示例数据（我本人的课表）。
     *
     * 只在库里为空时生效——不会动你已经录进去的东西。
     * 这条路径就是给「纯净版分发给同学」准备的：同学拿到的是空 App，
     * 想看示例就自己点这一下。
     */
    fun loadSampleData() {
        viewModelScope.launch {
            val inserted = withContext(Dispatchers.IO) {
                val ok = repository.seedIfEmpty()
                if (ok) Alarms.reschedule(getApplication())
                ok
            }
            refresh()
            toast(if (inserted) "已导入示例课表" else "库里已有数据，未导入（示例只在空库时可用）")
        }
    }

    fun setDailyBriefing(enabled: Boolean, minute: Int) {
        settings.dailyBriefingEnabled = enabled
        settings.dailyBriefingMinute = minute
        _state.value = _state.value.copy(dailyBriefingEnabled = enabled, dailyBriefingMinute = minute)
        viewModelScope.launch {
            withContext(Dispatchers.IO) { Alarms.reschedule(getApplication()) }
            toast("已更新每日汇总：${Timetable.format(minute)}")
        }
    }

    fun setLeadMinutes(course: Int, event: Int) {
        settings.courseLeadMinutes = course
        settings.eventLeadMinutes = event
        _state.value = _state.value.copy(courseLeadMinutes = course, eventLeadMinutes = event)
        viewModelScope.launch {
            withContext(Dispatchers.IO) { Alarms.reschedule(getApplication()) }
            toast("已更新提前量")
        }
    }

    /** 外观：跟随系统 / 浅色 / 深色。 */
    fun setThemeMode(mode: ThemeMode) {
        settings.themeMode = mode
        _state.value = _state.value.copy(
            themeMode = mode,
            isDarkTheme = isDarkNow(mode),
        )
    }

    /** 动态取色（Material You）。只在 Android 12+ 有实际效果。 */
    fun setDynamicColor(enabled: Boolean) {
        settings.dynamicColor = enabled
        _state.value = _state.value.copy(dynamicColor = enabled)
        if (enabled && android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) {
            toast("这个功能需要 Android 12 及以上，当前系统上不会有变化")
        }
    }

    /**
     * 把 [ThemeMode] 与系统当前的深浅色合成「现在到底该用深色吗」。
     *
     * 跟随系统时读系统当前值；强制模式直接返回。
     * 注意：跟随系统时如果用户在系统里切换深浅色，Activity 会重建，
     * `refresh()` 会重新算一次，所以不需要额外监听。
     */
    private fun isDarkNow(mode: ThemeMode): Boolean {
        if (mode == ThemeMode.LIGHT) return false
        if (mode == ThemeMode.DARK) return true
        val nightMode = getApplication<android.app.Application>()
            .resources.configuration.uiMode
        val isNight = (nightMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        return isNight
    }

    /** 提醒总开关：一键关掉全部提醒，不必逐条改。 */
    fun setRemindersEnabled(enabled: Boolean) {
        settings.remindersEnabled = enabled
        _state.value = _state.value.copy(remindersEnabled = enabled)
        viewModelScope.launch {
            withContext(Dispatchers.IO) { Alarms.reschedule(getApplication()) }
            toast(if (enabled) "提醒已打开" else "提醒已全部关闭")
        }
    }

    /** 样例数据开关：只影响**下次**在空库上启动时是否自动灌入。 */
    fun setSeedSampleData(enabled: Boolean) {
        settings.seedSampleData = enabled
        _state.value = _state.value.copy(seedSampleData = enabled)
        toast(if (enabled) "下次空库启动会自动导入示例课表" else "已关闭自动导入（分享给同学时保持纯净）")
    }

    // ---------------------------------------------------------------- 作息表（课程时间）

    /** 采用某个内置预设。 */
    /** 类别列表的增删改（用户反馈：类别不能自定义）。 */
    fun addCategory(label: String) {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return
        if (settings.categories.contains(trimmed)) {
            toast("「$trimmed」已经在列表里了")
            return
        }
        settings.categories = settings.categories + trimmed
        refresh()
        toast("已添加类别「$trimmed」")
    }

    fun removeCategory(label: String) {
        val rest = settings.categories.filterNot { it == label }
        if (rest.isEmpty()) {
            toast("至少要留一个类别")
            return
        }
        settings.categories = rest
        refresh()
    }

    fun resetCategories() {
        settings.resetCategories()
        refresh()
        toast("类别已恢复为默认四个")
    }

    fun applyTimetablePreset(preset: Timetable) {
        settings.timetable = preset
        viewModelScope.launch {
            withContext(Dispatchers.IO) { Alarms.reschedule(getApplication()) }
            refresh()
            toast("已切换到「${preset.name}」")
        }
    }

    /** 改某一节的起止时刻。这是「每节课时间可配置」的核心入口。 */
    fun updatePeriod(period: Int, startText: String, endText: String) {
        val start = Timetable.parseMinute(startText)
        val end = Timetable.parseMinute(endText)
        if (start == null || end == null) {
            toast("时间格式要写成 HH:mm，例如 08:20")
            return
        }
        if (end <= start) {
            toast("结束时间必须晚于开始时间")
            return
        }
        val updated = settings.timetable.withSlot(period, start, end)
        settings.timetable = updated
        viewModelScope.launch {
            withContext(Dispatchers.IO) { Alarms.reschedule(getApplication()) }
            refresh()
            toast("第 $period 节已改为 ${updated.label(period)}")
        }
    }

    /** 恢复成内置默认作息表。 */
    fun resetTimetable() {
        settings.resetTimetable()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { Alarms.reschedule(getApplication()) }
            refresh()
            toast("已恢复默认作息表")
        }
    }

    fun exportJson(): String {
        // 连设置一起导出：换手机时作息表、类别、外观、提醒提前量都要跟着走，
        // 否则得重配一遍（而且用户未必记得自己改过第 3 节的时刻）。
        val snapshot = Backup.SettingsSnapshot(
            timetable = settings.timetable,
            categories = settings.categories,
            themeMode = settings.themeMode,
            dynamicColor = settings.dynamicColor,
            remindersEnabled = settings.remindersEnabled,
            dailyBriefingEnabled = settings.dailyBriefingEnabled,
            dailyBriefingMinute = settings.dailyBriefingMinute,
            courseLeadMinutes = settings.courseLeadMinutes,
            eventLeadMinutes = settings.eventLeadMinutes,
        )
        val text = Backup.export(repository.allEvents(), repository.allExceptions(), snapshot)
        _state.value = _state.value.copy(backupJson = text)
        return text
    }

    fun importJson(text: String) {
        viewModelScope.launch {
            // 含原图的备份可能有几 MB；JSON + base64 解码必须离开主线程。
            val parsed = withContext(Dispatchers.IO) { Backup.parse(text) }
            if (parsed.events.isEmpty() && parsed.errors.isNotEmpty()) {
                toast("导入失败：${parsed.errors.first()}")
                return@launch
            }
            var restoredImages = 0
            withContext(Dispatchers.IO) {
                // 设置：备份里带了的才覆盖，没带的保留当前值。
                // 这样「导一份只有日程的老备份」不会把现有外观/作息冲掉。
                parsed.settings.timetable?.let { settings.timetable = it }
                parsed.settings.categories?.let { settings.categories = it }
                parsed.settings.themeMode?.let { settings.themeMode = it }
                parsed.settings.dynamicColor?.let { settings.dynamicColor = it }
                parsed.settings.remindersEnabled?.let { settings.remindersEnabled = it }
                parsed.settings.dailyBriefingEnabled?.let { settings.dailyBriefingEnabled = it }
                parsed.settings.dailyBriefingMinute?.let { settings.dailyBriefingMinute = it }
                parsed.settings.courseLeadMinutes?.let { settings.courseLeadMinutes = it }
                parsed.settings.eventLeadMinutes?.let { settings.eventLeadMinutes = it }

                // v3：先把原图写回 App 私有目录，再把路径挂到事件上。
                // 同一张图被多条事件引用时，saveImportedImage 按内容哈希去重，不会写多份。
                val resolved = parsed.events.map { event ->
                    val imageId = parsed.eventImageIds[event.id]
                    val payload = imageId?.let { parsed.images[it] }
                    val path = payload?.let { saveImportedImage(it) }
                    if (path != null) restoredImages++
                    if (path == null) event else event.copy(sourceImagePath = path)
                }
                for (event in resolved) repository.upsert(event)
                for (exception in parsed.exceptions) repository.upsertException(exception)
                Alarms.reschedule(getApplication())
            }
            refresh()
            val extra = if (parsed.errors.isEmpty()) "" else "，${parsed.errors.size} 条被跳过"
            val settingsNote = if (parsed.settings.isEmpty) "" else "，设置也一并恢复"
            val imageNote = when {
                restoredImages > 0 -> "，恢复 $restoredImages 张原图"
                parsed.eventImageIds.isNotEmpty() -> "，原图写入失败"
                else -> ""
            }
            toast("已导入 ${parsed.events.size} 条$settingsNote$imageNote$extra")
        }
    }

    /** 把备份里的原图写回 `filesDir/chat_images`；按内容哈希命名，重复导入不会越写越多。 */
    private fun saveImportedImage(payload: Backup.ImagePayload): String? = try {
        val app = getApplication<android.app.Application>()
        val dir = java.io.File(app.filesDir, "chat_images")
        if (!dir.exists()) dir.mkdirs()
        val ext = when (payload.mime) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> "jpg"
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(payload.bytes)
        val hash = digest.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        val target = java.io.File(dir, "imported-$hash.$ext")
        if (!target.exists()) target.writeBytes(payload.bytes)
        target.absolutePath
    } catch (_: Exception) {
        null
    }

    // ---------------------------------------------------------------- AI

    fun parseWithAi(text: String) {
        if (!llm.available()) {
            toast("AI 未启用或未填写 API Key（设置页里可以填）")
            return
        }
        _state.value = _state.value.copy(aiBusy = true, aiRawText = text, aiCandidates = emptyList())
        viewModelScope.launch {
            val today = LocalDate.now()
            val result = withContext(Dispatchers.IO) { llm.parseSchedules(text, today, settings.timetable) }
            _state.value = _state.value.copy(aiBusy = false).let { current ->
                // 注意：Kotlin 1.9 起 kotlin.Result 的 success/failure 不再是可用于穷尽匹配的子类，
                // 所以这里用 isSuccess 判断，不能写 `is Result.success`。
                if (result.isSuccess) {
                    current.copy(aiCandidates = result.getOrNull().orEmpty())
                } else {
                    toast("解析失败：${result.exceptionOrNull()?.message}")
                    current
                }
            }
        }
    }

    /** 把用户确认过的候选写进库。默认必须人工确认（Q15）。 */
    fun acceptCandidates(candidates: List<LlmClient.Candidate>) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                for ((index, candidate) in candidates.withIndex()) {
                    val id = "ai-" + System.currentTimeMillis().toString(36) + "-" + index
                    // 有周次规则的（整学期课程）从规则的第一周开始展开，
                    // 不能拿「这一周的那一天」当 anchorDate，否则前面的周次会被截掉。
                    val recurrence = candidate.recurrence
                    val anchor = if (recurrence == null) {
                        candidate.date
                    } else {
                        val firstWeek = recurrence.weeks.minOrNull() ?: recurrence.startWeek
                        Semester.mondayOf(firstWeek)
                            .plusDays((recurrence.dayOfWeek.value - 1).toLong())
                    }
                    repository.upsert(
                        Event(
                            id = id,
                            title = candidate.title,
                            kind = candidate.kind,
                            category = candidate.kind.defaultCategory,
                            location = candidate.location,
                            note = candidate.note.ifEmpty { candidate.evidence },
                            anchorDate = anchor,
                            // 候选现在直接带起止时刻，写库时也是「按时刻」——
                            // 中间不再经过节次换算，所以不会再出现「时间对不上」。
                            //
                            // 例外一：**截止型且原文没写时刻**的，落成「全天」。
                            // 例外二：原文只给了一个时刻（如「23:59 前交」）的，落成「具体时刻」。
                            time = when {
                                candidate.shouldBeAllDay -> TimeSpec.ALL_DAY
                                candidate.isPoint -> TimeSpec.ByPoint(candidate.startMinute)
                                else -> TimeSpec.ByClock(candidate.startMinute, candidate.endMinute)
                            },
                            recurrence = recurrence,
                            source = "AI 解析（已人工确认）",
                            // 「一手资料」：把喂给 AI 的原文留在日程上，
                            // 以后点这条日程就能回看当初是从哪段文字/哪张图来的。
                            sourceText = candidate.evidence.ifEmpty { lastUserSourceText },
                            sourceImagePath = lastSourceImagePath,
                        )
                    )
                }
                Alarms.reschedule(getApplication())
            }
            _state.value = _state.value.copy(aiCandidates = emptyList(), aiRawText = "")
            refresh()
            toast("已写入 ${candidates.size} 条")
        }
    }

    fun dismissCandidates() {
        _state.value = _state.value.copy(aiCandidates = emptyList(), aiRawText = "")
    }

    /**
     * 改一条候选（在核对阶段、**还没写库之前**）。
     *
     * 用户反馈：「图片识别错误时，无法先修改再导入」。
     * 原先候选只能整条勾选或丢弃，识别错了（读错日期、时间、标题）就只能先导入再回头改，
     * 或者干脆重发一遍图。现在可以在核对卡上直接改，改完再写入。
     */
    fun updateCandidate(index: Int, candidate: Candidate) {
        val current = _state.value.aiCandidates
        if (index !in current.indices) return
        _state.value = _state.value.copy(
            aiCandidates = current.toMutableList().also { it[index] = candidate },
        )
    }

    /** 从候选列表里删掉一条（识别出来但根本不想要的）。 */
    fun removeCandidate(index: Int) {
        val current = _state.value.aiCandidates
        if (index !in current.indices) return
        _state.value = _state.value.copy(
            aiCandidates = current.filterIndexed { i, _ -> i != index },
        )
    }

    // ---------------------------------------------------------------- 读图导入（强制人工核对）

    /**
     * 从一张图片（课表截图、时间表截图、通知截图）里解析日程。
     *
     * ## 为什么强制走候选列表，绝不直接入库（用户 2026-10-14 的决定）
     * 实测结论（`_tools/probe-vision*.py`，用真实课表截图）：
     *   - 程序生成的色块图：**完全正确**（红底、左下蓝方块，位置都对）；
     *   - **真实课表截图：20 条里读错 2 条**，错法是「串列」——把某一列的课
     *     归到了相邻的星期（高数被读成周四、心理健康被读成周五）；
     *   - 给它一张**它看不见的图（1×1 空图），它会凭空编**出「白底 + 居中黑方块」。
     *
     * 串列意味着「课排到了错误的星期」，你可能两周后才发现；而
     * 「依据是否出现在原文里」那道校验**对图片无效**（没有可比对的原文）。
     * 所以这里只产出候选，必须由人逐条确认后才写库。
     */
    // parseImageWithAi 已被「先附件、后发送」的流程取代（见 attachImage / sendWithAttachments），
    // 这里只保留一段说明，避免后来者以为漏了实现。

    /**
     * 向 AI 提问（流式）。
     *
     * 与上一版的区别：正文与思考过程**逐字追加**到 [UiState.streamingText] /
     * [UiState.streamingReasoning]，界面上能看见它在想什么、而不是干等一个整段答案。
     * 这是用户反馈第 3 条「感觉挺人机的」的直接对策。
     *
     * [alreadyAppended] 为 true 时表示用户消息已经由调用方放进 [UiState.chat] 了
     * （附件流程会先摆出带缩略图的那条消息），这里就不再重复追加。
     *
     * [keepSourceText] 为 true 时不覆盖 [lastUserSourceText]。
     * 读图流程要用它：那时「原文」应该是图片的 OCR 文字（由 runImageFlow 先设好），
     * 而这里传进去的只是给模型看的系统提示，不是用户的原话。
     */
    fun sendMessage(
        text: String,
        alreadyAppended: Boolean = false,
        keepSourceText: Boolean = false,
    ) {
        if (!llm.available()) {
            toast("AI 未启用，或没有配置好的服务商。去设置页填 Key 并打开开关。")
            return
        }
        // 记下这一轮的原文，供 propose_events 核对依据用。
        // 图片流程会把它设成 OCR 文字并通过 keepSourceText 保护住。
        if (!keepSourceText) {
            lastUserSourceText = text
            // 纯文字输入没有配图，清掉上一轮残留的图片路径，
            // 否则新建的文字日程会挂到上一次的图片上（张冠李戴）。
            lastSourceImagePath = ""
        }
        _state.value = _state.value.copy(
            chat = if (alreadyAppended) _state.value.chat else _state.value.chat + ChatMessage(true, text),
            aiBusy = true,
            streamingText = "",
            streamingReasoning = "",
            streamingTool = "",
        )
        if (!alreadyAppended) chatStore.append(fromUser = true, text = text)
        viewModelScope.launch {
            val today = LocalDate.now()
            val context = withContext(Dispatchers.IO) { buildContext(today) }
            // 会话历史：把之前聊过的内容一起发过去，模型才有上下文。
            // 用户反馈「我发现它没有上下文」的根因就是这里以前只发了当前这一句。
            // 取最近若干条（不是全部）：上下文越长越贵，而且太久远的内容帮助有限。
            val history = _state.value.chat
                .dropLast(if (alreadyAppended) 1 else 1) // 去掉刚追加的这一条，它就是 question
                .takeLast(MAX_HISTORY_MESSAGES)
                .map { it.fromUser to it.text }
                .filter { it.second.isNotBlank() }

            var usage = LlmClient.Usage.ZERO
            // 回调发生在 IO 线程，直接改 StateFlow 是安全的（它是线程安全的）。
            val sink = object : LlmClient.StreamSink {
                private val reasoning = StringBuilder()
                private val body = StringBuilder()

                override fun onReasoning(text: String) {
                    reasoning.append(text)
                    _state.value = _state.value.copy(streamingReasoning = reasoning.toString())
                }

                override fun onDelta(text: String) {
                    body.append(text)
                    _state.value = _state.value.copy(streamingText = body.toString())
                }

                override fun onToolStarted(name: String) {
                    _state.value = _state.value.copy(streamingTool = toolLabel(name))
                }

                override fun onUsage(promptTokens: Int, completionTokens: Int, reasoningTokens: Int) {
                    usage = LlmClient.Usage(promptTokens, completionTokens, reasoningTokens)
                }
            }

            // 必须把 today 传进去：不传的话模型不知道「今天」是哪天，
            // 会回答「数据里没有说明今天是哪一天」——用户已经踩到过这个。
            val result = withContext(Dispatchers.IO) {
                llm.askStreaming(text, context, today, settings.timetable, history, sink)
            }
            val answer = if (result.isSuccess) {
                result.getOrNull().orEmpty()
            } else {
                "出错了：${result.exceptionOrNull()?.message}"
            }
            val reasoningText = _state.value.streamingReasoning
            // AI 可能真的改了库，所以刷新界面，让改动立刻可见。
            refresh()
            chatStore.append(
                fromUser = false,
                text = answer,
                reasoning = reasoningText,
                promptTokens = usage.promptTokens,
                completionTokens = usage.completionTokens,
                reasoningTokens = usage.reasoningTokens,
            )
            _state.value = _state.value.copy(
                aiBusy = false,
                streamingText = "",
                streamingReasoning = "",
                streamingTool = "",
                chat = _state.value.chat + ChatMessage(
                    fromUser = false,
                    text = answer,
                    reasoning = reasoningText,
                    promptTokens = usage.promptTokens,
                    completionTokens = usage.completionTokens,
                    reasoningTokens = usage.reasoningTokens,
                ),
            )
        }
    }

    /** 工具名的中文显示。 */
    private fun toolLabel(name: String): String = when (name) {
        "query_schedule" -> "正在查日程…"
        "create_event" -> "正在新建…"
        "move_occurrence" -> "正在改时间…"
        "cancel_occurrence" -> "正在取消…"
        "delete_event" -> "正在删除…"
        "list_events" -> "正在读取全部日程…"
        else -> "正在执行 $name…"
    }

    fun toggleReasoning() {
        _state.value = _state.value.copy(showReasoning = !_state.value.showReasoning)
    }

    /** 改思考等级：直接写进当前服务商配置，下一句就生效，不必去设置页。 */
    fun setEffort(effort: String) {
        val active = aiConfig.active() ?: aiConfig.all().firstOrNull()
            ?: return
        val updated = active.copy(reasoningEffort = effort)
        aiConfig.add(updated)
        aiConfig.setActive(updated.id)
        _state.value = _state.value.copy(
            currentEffort = effort,
            aiProviders = aiConfig.all(),
        )
        toast("思考等级已设为 ${if (effort.isEmpty()) "不指定" else effort}")
    }

    private companion object {
        /** 单个文件读进来最多用多少字，防止把上下文撑爆。 */
        const val MAX_FILE_CHARS = 8000

        /**
         * 回灌给模型的对话历史最多几条。
         *
         * 不是「越多越好」：prompt 会随历史线性变长（也就越贵），
         * 而且十几轮以前的内容对当前问题帮助很小。20 条大约等于 10 轮对话。
         */
        const val MAX_HISTORY_MESSAGES = 20
    }

    // ---------------------------------------------------------------- 附件（先选、后发）

    /** 选了一张图片：**只放进待发区**，不立即发送。 */
    fun attachImage(uri: android.net.Uri, resolver: android.content.ContentResolver) {
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "图片"
        val attachment = PendingAttachment(
            id = "att-" + System.currentTimeMillis().toString(36),
            uri = uri,
            displayName = name,
            isImage = true,
        )
        _state.value = _state.value.copy(pendingAttachments = _state.value.pendingAttachments + attachment)
    }

    /** 选了一个文件：同样是先放进待发区。 */
    fun attachFile(uri: android.net.Uri, fileName: String, resolver: android.content.ContentResolver) {
        val lower = fileName.lowercase()
        val mime = resolver.getType(uri).orEmpty()
        val isImage = mime.startsWith("image/") ||
            lower.endsWith(".png") || lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") || lower.endsWith(".webp")
        val attachment = PendingAttachment(
            id = "att-" + System.currentTimeMillis().toString(36),
            uri = uri,
            displayName = fileName.ifEmpty { "文件" },
            isImage = isImage,
        )
        _state.value = _state.value.copy(pendingAttachments = _state.value.pendingAttachments + attachment)
    }

    fun removeAttachment(id: String) {
        _state.value = _state.value.copy(
            pendingAttachments = _state.value.pendingAttachments.filterNot { it.id == id },
        )
    }

    /**
     * 把「文字 + 待发附件」一起发出去。
     *
     * 用户要的正是这个：图片和文字**同一条消息**发。所以这里把配文与每张附件合并成
     * 一条用户消息；图片会先拷进 App 私有目录（content Uri 不保证长期可用），
     * 然后把图片路径记进消息里——这样图片在会话里**一直看得见**。
     */
    fun sendWithAttachments(text: String) {
        val attachments = _state.value.pendingAttachments
        if (attachments.isEmpty()) {
            sendMessage(text)
            return
        }
        _state.value = _state.value.copy(pendingAttachments = emptyList())

        viewModelScope.launch {
            val resolver = getApplication<android.app.Application>().contentResolver
            for (attachment in attachments) {
                if (attachment.isImage) {
                    val saved = withContext(Dispatchers.IO) { saveImageToInternal(attachment.uri) }
                    if (saved == null) {
                        toast("这张图片读不出来")
                        continue
                    }
                    runImageFlow(saved, text)
                } else {
                    val content = withContext(Dispatchers.IO) { readTextFile(attachment.uri) }
                    if (content == null) {
                        toast("「${attachment.displayName}」这个格式暂时读不了。可以截图后当图片发给我。")
                        continue
                    }
                    runTextFlow(content, text, attachmentLabel = attachment.displayName)
                }
            }
        }
    }

    /** 把 content Uri 里的图片拷进 App 私有目录，返回绝对路径。 */
    private fun saveImageToInternal(uri: android.net.Uri): String? = try {
        val dir = java.io.File(getApplication<android.app.Application>().filesDir, "chat_images")
        if (!dir.exists()) dir.mkdirs()
        val target = java.io.File(dir, "img-" + System.currentTimeMillis().toString(36) + ".jpg")
        getApplication<android.app.Application>().contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        if (target.length() > 0) target.absolutePath else null
    } catch (_: Exception) {
        null
    }

    /** 读文本文件（只支持真文本，二进制一律返回 null，由调用方如实告诉用户）。 */
    private fun readTextFile(uri: android.net.Uri): String? = try {
        val bytes = getApplication<android.app.Application>().contentResolver
            .openInputStream(uri)?.use { it.readBytes() }
        if (bytes == null || bytes.isEmpty()) {
            null
        } else {
            val text = bytes.toString(Charsets.UTF_8)
            // 二进制文件的 UTF-8 解码会出现大量替换字符，据此判定"不是文本"。
            val replacements = text.count { it == '\uFFFD' }
            if (replacements > text.length / 20) null
            else if (text.length > MAX_FILE_CHARS) text.take(MAX_FILE_CHARS)
            else text
        }
    } catch (_: Exception) {
        null
    }

    /**
     * 走读图流程：先把图片消息**记进会话**（这样用户看得见图），再让视觉模型读。
     */
    /**
     * 走读图流程。
     *
     * ## 踩过的坑（用户反馈：「发送日程图片没有弹出候选」）
     * 旧版本这里**只调了读图、从来没有把图片发给模型**，所以模型只看到用户配的那句
     * 「帮我识别，并且导入」，于是回答「你还没把要识别的内容发给我」——
     * 用户看到的正是这个。修法是两步：
     *
     * 1. 先用视觉模型把图读成**候选**（这一步本来就有），候选摆到界面下方；
     * 2. **再把读出来的文字回灌给对话**，让模型知道「我已经看过这张图了」，
     *    这样它既能接着聊，也能对后续追问（「第二条改到周五」）有上下文。
     *
     * 另外这里把 `lastUserSourceText` 设成**图片的 OCR 文字**而不是空串：
     * `propose_events` 那套「依据是否真在原文里」的校验才有东西可核对。
     */
    private fun runImageFlow(imagePath: String, caption: String) {
        if (!llm.available()) {
            toast("AI 未启用，或没有配置好的服务商")
            return
        }
        if (!llm.visionAvailable()) {
            toast("没有勾选「支持图片」的服务商。去设置页勾上，或加一个视觉模型（如智谱 GLM-4V）。")
            return
        }
        // 图片没有可核对的文本原文，如实置空；但原图路径要记住，写库时挂到日程上。
        lastUserSourceText = ""
        lastSourceImagePath = imagePath

        val userMessage = ChatMessage(
            fromUser = true,
            text = caption,
            imagePath = imagePath,
            attachmentLabel = "图片",
        )
        _state.value = _state.value.copy(
            chat = _state.value.chat + userMessage,
            aiBusy = true,
            aiCandidates = emptyList(),
            aiRawText = "（正在读图…）",
        )
        chatStore.append(fromUser = true, text = "[图片] $caption", reasoning = "")
        // 注意：上面这条只是占位。读图完成后会用 chatStore.replaceLastFromUser()
        // 把 OCR 文字补写进去——不补的话下一轮模型看到的是一条空消息，
        // 它会说「我并没有收到图片」并否认自己上一条说过的话。

        viewModelScope.launch {
            val today = LocalDate.now()
            val result = withContext(Dispatchers.IO) {
                llm.parseScheduleImageFile(imagePath, today, settings.timetable)
            }
            if (result.isFailure) {
                val reason = result.exceptionOrNull()?.message ?: "未知错误"
                _state.value = _state.value.copy(aiBusy = false, aiRawText = "")
                val failure = "读图失败了：$reason\n\n可以换张更清楚的图，或者把文字直接复制粘贴给我。"
                chatStore.append(fromUser = false, text = failure)
                _state.value = _state.value.copy(chat = _state.value.chat + ChatMessage(false, failure))
                return@launch
            }

            val candidates = result.getOrNull().orEmpty()
            _state.value = _state.value.copy(
                aiBusy = false,
                aiCandidates = candidates,
                aiRawText = "（来自图片。图片没有可比对的原文，所以「依据」一栏是模型自述，请对着截图核对）",
            )

            if (candidates.isEmpty()) {
                val empty = "这张图里我没能读出任何日程。可以换张更清楚的图（文字大一点、别截到无关区域），" +
                    "或者把文字直接复制粘贴给我。"
                chatStore.append(fromUser = false, text = empty)
                _state.value = _state.value.copy(chat = _state.value.chat + ChatMessage(false, empty))
                return@launch
            }

            val extracted = candidates.joinToString("\n") { candidate ->
                buildString {
                    append("- ${candidate.date} ")
                    append("${candidate.timeText} ")
                    append(candidate.title)
                    if (candidate.location.isNotEmpty()) append(" @${candidate.location}")
                }
            }

            /*
             * 把 OCR 出来的文字**补写进那条用户消息**。
             *
             * ## 踩过的坑（用户反馈：「图片没有上下文」，而 AI 回答自相矛盾）
             * 原先这里只往历史里写 `"[图片] "`——**空内容**。于是下一轮把历史发回给模型时，
             * 模型看到的是一条**空的用户消息**，它就会说「我这边并没有收到任何图片」，
             * 甚至否认自己上一条说过的话。数据库里的原文就是这么记的：
             *     #39 用户: '[图片] '      ← 空的
             *     #40 助手: '我从这张图里读出 15 条候选…'
             *     #42 助手: '我这边并没有收到任何图片，上一条是我凭空说的'
             * 现在把读出来的文字真正写进那条消息，模型才有东西可记。
             */
            val supplemented = if (caption.isBlank()) {
                "[图片] 读出的内容：\n$extracted"
            } else {
                "[图片] $caption\n读出的内容：\n$extracted"
            }
            _state.value = _state.value.copy(
                chat = _state.value.chat.map { message ->
                    if (message.imagePath == imagePath && message.fromUser) {
                        message.copy(attachmentText = extracted, text = caption)
                    } else {
                        message
                    }
                },
            )
            chatStore.replaceLastFromUser(supplemented)

            // 让模型知道"这张图我读过了"，并请它汇报。
            lastUserSourceText = extracted
            sendMessage(
                buildString {
                    append("（系统提示：你刚刚已经看过用户发的图片，并用视觉模型读出了以下 ")
                    append(candidates.size)
                    append(" 条候选。它们已经显示在界面下方的核对区里，**还没有写入日程库**。）\n")
                    append(extracted)
                    append("\n\n")
                    if (caption.isNotBlank()) {
                        append("用户当时配的话是：「$caption」。")
                    } else {
                        append("用户没有配文字。")
                    }
                    append("请简短汇报你读到了几条、分别是哪几天，并提醒他逐条核对后再确认写入。")
                    append("**不要**重复把每条的完整细节再列一遍，也不要重复调用工具。")
                },
                alreadyAppended = true,
                keepSourceText = true,
            )
        }
    }

    /** 走文字流程：把文件内容与配文一起作为一条用户消息发给智能体。 */
    private fun runTextFlow(content: String, caption: String, attachmentLabel: String) {
        val combined = buildString {
            if (caption.isNotEmpty()) {
                append(caption)
                append("\n\n")
            }
            append("（以下来自文件「$attachmentLabel」）\n")
            append(content)
        }
        _state.value = _state.value.copy(
            chat = _state.value.chat + ChatMessage(
                fromUser = true,
                text = caption.ifEmpty { "（来自文件「$attachmentLabel」）" },
                attachmentLabel = attachmentLabel,
                attachmentText = content,
            ),
        )
        sendMessage(combined, alreadyAppended = true)
    }

    /**
     * 喂给模型的日程上下文。
     *
     * 用户反馈第 5 条的根因之一就在这里：原来只给「未来 14 天」，
     * 所以模型既看不到过去（没法回答「上周三有什么课」），也看不到更远（排不了期末）。
     * 现在给 **过去 14 天 + 未来 60 天**，并且每条都写全 **绝对日期 + 起止时刻 + 节次**，
     * 模型不需要自己算，也就不会算错。
     */
    private fun buildContext(today: LocalDate): String {
        val from = today.minusDays(14)
        val to = today.plusDays(60)
        val sessions = repository.sessions(from, to)
        if (sessions.isEmpty()) {
            return "（$from 到 $to 之间没有任何安排；库里是空的，用户还没录任何东西）"
        }
        val header = "区间：$from ~ $to（今天 = $today）共 ${sessions.size} 条\n"
        return header + sessions.joinToString("\n") { session ->
            val location = if (session.location.isEmpty()) "" else " @${session.location}"
            val teacher = if (session.teacher.isEmpty()) "" else " ${session.teacher}"
            val state = when (session.state) {
                SessionState.NORMAL -> ""
                SessionState.CANCELLED -> " [已取消]"
                SessionState.MOVED -> " [已调整]"
                SessionState.EXTRA -> " [补课]"
            }
            "${session.date} ${DateTimeExt.weekName(session.date)} ${session.timeLabel} " +
                "${session.title}$teacher$location$state [${session.kind}]"
        }
    }

    // ---------------------------------------------------------------- AI 工具执行

    /**
     * 执行模型请求的工具调用。
     *
     * 全部走 [Repository]，跟手动改是同一条路径、同一个库 —— AI 改完刷新界面就能看到。
     * 出错一律返回 `ok=false` 并把原因写清楚，模型会据此改口或换工具，而不是静默失败。
     */
    private fun executeTool(name: String, arguments: org.json.JSONObject): LlmClient.ToolResult = try {
        when (name) {
            "query_schedule" -> {
                val from = parseDate(arguments.text("from")) ?: LocalDate.now()
                val to = parseDate(arguments.text("to")) ?: from
                val sessions = repository.sessions(from, to)
                if (sessions.isEmpty()) {
                    LlmClient.ToolResult(true, "$from ~ $to 之间没有任何安排")
                } else {
                    val text = sessions.joinToString("\n") { session ->
                        val location = if (session.location.isEmpty()) "" else " @${session.location}"
                        "${session.date} ${session.timeLabel} ${session.title}$location [${session.kind}]"
                    }
                    LlmClient.ToolResult(true, "$from ~ $to 共 ${sessions.size} 条：\n$text")
                }
            }

            "list_events" -> {
                val events = repository.allEvents()
                val text = events.joinToString("\n") { event ->
                    val rule = event.recurrence?.let { recurrence ->
                        "${DateTimeExt.weekName(Semester.mondayOf(recurrence.startWeek).plusDays((recurrence.dayOfWeek.value - 1).toLong()))} " +
                            "${recurrence.parity} 第${recurrence.startWeek}-${recurrence.endWeek}周"
                    } ?: "单次 ${event.anchorDate}"
                    val timeText = TimeSpec.label(event.time, settings.timetable)
                    "${event.id} | ${event.title} | ${event.kind} | $rule | $timeText | ${event.location}"
                }
                LlmClient.ToolResult(true, "共 ${events.size} 条主规则：\n$text")
            }

            // 用户在聊天里粘了一段课表/公告 → 模型把条目提出来，**只摆候选、不写库**。
            // 这是「读图/粘贴 → 逐条确认 → 入库」那条流水线在聊天里的入口。
            "propose_events" -> {
                val raw = arguments.text("items_json")
                if (raw.isBlank()) {
                    LlmClient.ToolResult(false, "items_json 是空的")
                } else {
                    val today = LocalDate.now()
                    val candidates = try {
                        llm.candidatesFromJson(raw, lastUserSourceText, today, settings.timetable)
                    } catch (error: Exception) {
                        return LlmClient.ToolResult(false, "items_json 解析失败：${error.message}")
                    }
                    if (candidates.isEmpty()) {
                        LlmClient.ToolResult(
                            false,
                            "没能从 items_json 里解析出任何条目（可能格式不对）。" +
                                "请按 [{\"title\":\"…\",\"kind\":\"ACTIVITY\",\"date\":\"YYYY-MM-DD\",…}] 重新调用。",
                        )
                    } else {
                        _state.value = _state.value.copy(
                            aiCandidates = candidates,
                            aiRawText = "（由 AI 从你发的内容里提取，逐条确认后再写入）",
                        )
                        LlmClient.ToolResult(
                            true,
                            "已提取 ${candidates.size} 条候选，正在等用户逐条确认。请告诉用户有几条、分别是哪几天，让他在下方勾选。",
                        )
                    }
                }
            }

            "create_event" -> {
                val date = parseDate(arguments.text("date"))
                    ?: return LlmClient.ToolResult(false, "date 无法解析：${arguments.text("date")}")
                val start = Timetable.parseMinute(arguments.text("start"))
                    ?: return LlmClient.ToolResult(
                        false,
                        "start 必须是 HH:mm（例如 14:30），收到的是「${arguments.text("start")}」",
                    )
                val end = Timetable.parseMinute(arguments.text("end"))
                val time = if (end != null && end > start) {
                    TimeSpec.ByClock(start, end)
                } else {
                    TimeSpec.ByPoint(start)
                }
                val recurrence = CandidateParser.recurrenceFromText(
                    arguments.text("weeks"),
                    arguments.text("parity"),
                    date,
                )
                val title = arguments.text("title").ifEmpty { "（未命名）" }
                createSingleEvent(
                    title = title,
                    kind = EventKind.ACTIVITY,
                    // AI 建的日程用用户类别表的第一个（默认是「行程」），
                    // 这样不会出现一个用户根本没定义的类别名。
                    category = settings.categories.firstOrNull().orEmpty(),
                    date = date,
                    time = time,
                    location = arguments.text("location"),
                    note = arguments.text("note").ifEmpty { "由 AI 创建" },
                    recurrence = recurrence,
                )
                val weekText = recurrence?.let {
                    "（${it.parity.descZh} 第 ${it.startWeek}-${it.endWeek} 周）"
                } ?: ""
                LlmClient.ToolResult(
                    true,
                    "已新建「$title」：$date ${TimeSpec.label(time, settings.timetable)}$weekText",
                )
            }

            "move_occurrence" -> {
                val title = arguments.text("title")
                val from = parseDate(arguments.text("date"))
                    ?: return LlmClient.ToolResult(false, "date 无法解析")
                val to = parseDate(arguments.text("to_date"))
                    ?: return LlmClient.ToolResult(false, "to_date 无法解析")
                val target = findSession(title, from)
                    ?: return LlmClient.ToolResult(
                        false,
                        "$from 找不到标题含「$title」的安排。请先用 query_schedule 查清楚那天有什么。",
                    )
                // 时间：模型给新的时刻（to_start="14:30"）；只给开始时刻表示一个具体时间点。
                // 两个都没给就沿用原时间。
                val toStartText = arguments.text("to_start")
                val toEndText = arguments.text("to_end")
                val newTime: TimeSpec = run {
                    val s = Timetable.parseMinute(toStartText)
                    val e = Timetable.parseMinute(toEndText)
                    when {
                        s != null && e != null && e > s -> TimeSpec.ByClock(s, e)
                        s != null -> TimeSpec.ByPoint(s)
                        else -> target.spec
                    }
                }
                moveOccurrence(
                    session = target,
                    toDate = to,
                    time = newTime,
                    location = arguments.text("to_location").ifEmpty { null },
                )
                LlmClient.ToolResult(
                    true,
                    "已把「${target.title}」从 $from 调到 $to ${TimeSpec.label(newTime, settings.timetable)}",
                )
            }

            "cancel_occurrence" -> {
                val title = arguments.text("title")
                val date = parseDate(arguments.text("date"))
                    ?: return LlmClient.ToolResult(false, "date 无法解析")
                val target = findSession(title, date)
                    ?: return LlmClient.ToolResult(
                        false,
                        "$date 找不到标题含「$title」的安排。",
                    )
                cancelOccurrence(target)
                LlmClient.ToolResult(true, "已取消 $date 的「${target.title}」（只取消这一次）")
            }

            "delete_event" -> {
                val title = arguments.text("title")
                val match = repository.allEvents().firstOrNull { it.title.contains(title) }
                    ?: return LlmClient.ToolResult(false, "库里找不到标题含「$title」的整条日程")
                deleteEvent(match.id)
                LlmClient.ToolResult(true, "已删除整条「${match.title}」（含全部周次）")
            }

            else -> LlmClient.ToolResult(false, "没有名为 $name 的工具")
        }
    } catch (error: Exception) {
        LlmClient.ToolResult(false, "工具执行异常：${error.message}")
    }

    private fun parseDate(raw: String): LocalDate? = try {
        LocalDate.parse(raw.trim())
    } catch (_: Exception) {
        null
    }

    /** 在 [date] 那天按标题（含部分匹配）找一条发生。 */
    private fun findSession(title: String, date: LocalDate): Session? {
        val sessions = repository.sessionsOn(date).filter { it.state != SessionState.CANCELLED }
        if (title.isBlank()) return sessions.firstOrNull()
        return sessions.firstOrNull { it.title.contains(title) }
            ?: sessions.firstOrNull { title.contains(it.title) }
    }

    fun clearChat() {
        chatStore.clear()
        _state.value = _state.value.copy(chat = emptyList())
    }

    fun toast(message: String) {
        _state.value = _state.value.copy(toast = message)
    }

    fun consumeToast() {
        _state.value = _state.value.copy(toast = null)
    }
}
