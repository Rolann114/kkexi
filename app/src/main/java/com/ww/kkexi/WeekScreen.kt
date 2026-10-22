package com.ww.kkexi

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

/**
 * 课程表周视图：**按时间轴的网格**，不是一个萝卜一个坑的节次格子。
 *
 * ## 为什么改成时间轴（用户反馈）
 * 原先是「12 个固定高度的节次格子，每门课塞进它覆盖的格子里」——那是**教务系统课表**的形状，
 * 前提是每门课都严格对齐节次。但用户的实际安排不满足这个前提：
 *
 * 用户原话：「每一个我安排的事件可以根据不同的时间放在不同的位置，即不固定每一个板块的位置，
 * 而是可以能够横跨半节或几节课」。
 *
 * 所以现在：
 * - **纵向 = 真实时间轴**（默认 07:00–23:00），一小时固定高度；
 * - 每条日程的**高度由它的时长决定**，位置由它的开始时刻决定；
 *   14:30 开始的活动就画在 14:30 那个位置，15:45 开始的就画在 15:45，
 *   谁也不用迁就「整节课」的边界；
 * - 一小时的高度是可以看懂的刻度，所以「横跨半节」这种需求自然就满足了。
 *
 * ## 重叠怎么处理
 * 用户说「至于重合的部分怎么处理，我还没有想好」。这里选的是**并排分栏**：
 * 同一时段重叠的几条各占一栏、横向平分宽度，每条都能看清。
 * 不做堆叠遮挡（那会丢信息），也不做「+N」折叠（那要多一次点击）。
 * 若是三条以上重叠，栏会变窄，但标题仍可读——比隐藏信息好。
 */
@Composable
fun WeekScreen(
    state: UiState,
    onWeekChange: (Int) -> Unit,
    onLongPress: (Session) -> Unit,
) {
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }

    Column(Modifier.fillMaxWidth()) {
        WeekHeader(state, onWeekChange)

        val range = remember(state.weekSessions) { visibleRange(state) }
        val labelWidth = 46.dp
        val gridHeight = minuteToDp(range.last - range.first)

        // 纵向滚动状态要提起来，才能在「打开就滚到当前时刻」时用。
        val verticalScroll = rememberScrollState()
        // 横向同理：打开时把「今天」那一列滚进视野。
        val horizontalScroll = rememberScrollState()

        // LocalDensity 是 @Composable 读取，必须在 LaunchedEffect 外面取好再带进去。
        val density = androidx.compose.ui.platform.LocalDensity.current

        /*
         * 打开课程表时把「今天」横向滚到可见处。
         *
         * 一屏只放得下三列左右，今天若是周日（第 7 列），一进来看到的是周一到周三，
         * 「当前时间线」画在今天那列里也就等于没画。
         *
         * 起始索引取「今天那一列」和「倒数第 3 列」的较小值，
         * 这样今天永远在可见范围内、且尽量靠左（后面几天也还能看到）。
         */
        LaunchedEffect(state.week, state.today) {
            val todayIndex = state.weekDays.indexOfFirst { it == state.today }
            if (todayIndex < 0) {
                horizontalScroll.scrollTo(0)
                return@LaunchedEffect
            }
            val startIndex = minOf(todayIndex, maxOf(0, state.weekDays.size - 3))
            horizontalScroll.scrollTo(with(density) { (dayWidth * startIndex).roundToPx() })
        }

        /*
         * 打开课程表时自动滚到「现在」。
         *
         * 为什么需要：网格范围已经把当前时刻算进去了（见 visibleRange），
         * 于是网格可能铺到 22:00——但一屏放不下，用户打开看到的仍是早上那段空白，
         * 「我现在在哪」还是得自己往下滑。日历类应用的通行做法是直接滚到当前时刻。
         *
         * 只在「这一周包含今天」时滚；翻到别的周就保持顶部（那里没有"现在"）。
         * key 用 date+range：换周、换范围时重新定位。
         */
        val includesToday = state.weekDays.any { it == state.today }
        LaunchedEffect(state.week, range.first, range.last, includesToday) {
            if (!includesToday) {
                verticalScroll.scrollTo(0)
                return@LaunchedEffect
            }
            // 直接滚到当前时刻；超出可滚范围会自动夹到最大值（＝滚到底）。
            // 配合 computeVisibleRange 给「现在」后面留的 90 分钟，时间线不会贴在最下面。
            val nowOffset = minuteToDp(Timetable.nowMinute() - range.first)
            verticalScroll.scrollTo(with(density) { nowOffset.roundToPx() })
        }

        /*
         * 布局要点一：**左侧时刻列固定，只有日期列横向滚动**。
         *
         * 踩过的坑：第一版把时刻列和日期列放在同一个 Row 里，整个 Row 一起横滚——
         * 结果往右滑看后几天时，左边的时刻刻度也一起滑走了，
         * 「这块占多久」就失去了参照。固定住刻度列才好读。
         *
         * 布局要点二：**星期表头固定，不跟着纵向滚**。
         *
         * 踩过的坑：表头原先在纵向滚动容器里面，于是往下滑看下午/晚上时
         * 表头一起滚走了，屏幕上只剩一片格子——根本分不出哪一列是哪天。
         * 现在拆成「表头行」+「网格行」两层，只有网格行纵向滚。
         *
         * 代价：两个 Row 各有一个 horizontalScroll，必须共用同一个 ScrollState
         * 才能左右同步（两行内容宽度相同，都是 7 × dayWidth，所以共用一个状态是安全的）。
         */
        Row(Modifier.fillMaxWidth()) {
            // 左上角留白，与左下角的时刻列同宽，保证两行的日期列对齐
            Spacer(Modifier.width(labelWidth).height(DAY_HEADER_HEIGHT))
            Row(Modifier.horizontalScroll(horizontalScroll)) {
                for (date in state.weekDays) {
                    val daySessions = state.weekSessions[date].orEmpty()
                    val allDayCount = daySessions.count { it.isAllDay }
                    DayHeaderCell(
                        date = date,
                        isToday = date == state.today,
                        allDayCount = allDayCount,
                        onClick = { selectedDate = if (selectedDate == date) null else date },
                    )
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .verticalScroll(verticalScroll)
        ) {
            Column(Modifier.width(labelWidth)) {
                Box(Modifier.height(gridHeight)) {
                    for (hour in range.first / 60..range.last / 60) {
                        val top = minuteToDp(hour * 60 - range.first)
                        Text(
                            Timetable.format(hour * 60),
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.offset(y = top - 5.dp),
                        )
                    }
                }
            }

            // 与表头行共用 horizontalScroll，左右滑动时两行一起动。
            Row(Modifier.horizontalScroll(horizontalScroll)) {
                for (date in state.weekDays) {
                    val daySessions = state.weekSessions[date].orEmpty()
                    // 全天型不画在时间网格里（它们没有「几点」，画出来会贯穿整列），
                    // 改由日列头部的角标表示条数。
                    val timed = daySessions.filterNot { it.isAllDay }
                    DayColumn(
                        sessions = timed,
                        rangeStart = range.first,
                        height = gridHeight,
                        // 只在「今天」那一列画当前时间线。别的天没有「现在」。
                        nowMinute = if (date == state.today) Timetable.nowMinute() else null,
                        onClickEmpty = { selectedDate = if (selectedDate == date) null else date },
                        onLongPress = onLongPress,
                    )
                }
            }
        }

        // 点某一天后，在下面给出当天的完整细节（格子里放不下的地点、描述都在这儿）。
        val detailDate = selectedDate
        if (detailDate != null) {
            val sessions = state.weekSessions[detailDate].orEmpty()
            HorizontalDivider()
            LazyColumn(Modifier.fillMaxWidth()) {
                item {
                    Text(
                        "${DateTimeExt.formatDateWithWeek(detailDate)} · ${DateTimeExt.weekLabel(detailDate)}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                if (sessions.isEmpty()) {
                    item {
                        Text(
                            "这天没有安排。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
                items(sessions, key = { it.id }) { session ->
                    Box(Modifier.padding(horizontal = 12.dp)) {
                        SessionRow(session, false, { _, _ -> }, onLongPress)
                    }
                }
            }
        }
    }
}

/** 每小时占多少高度。60dp 够放下「标题 + 地点」两行，也能让半小时的差异看得清。 */
private val HOUR_HEIGHT = 60.dp

/** 每天一列的宽度。 */
private val dayWidth = 104.dp

/** 表头（星期）那一条的高度，左列要空出同样的高度才能让刻度与格子对齐。 */
private val DAY_HEADER_HEIGHT = 44.dp

private fun minuteToDp(minutes: Int): Dp = HOUR_HEIGHT * (minutes / 60f)

/**
 * 这一周要画的时间范围。
 *
 * 具体算法在 [computeVisibleRange]（`WeekLayout.kt`，纯函数，有单测）。
 * 这里只负责两件事：**排除全天型**日程、判断这一周是否含今天。
 *
 * 为什么排除全天型：它们的区间是 00:00–24:00，一算进来网格就被拉成整天
 * （真机上出现过：晚上打开课程表，早上全空、还滚不到当前时刻）。
 */
private fun visibleRange(state: UiState): IntRange = computeVisibleRange(
    timedSessions = state.weekSessions.values.flatten().filterNot { it.isAllDay },
    nowMinute = if (state.weekDays.any { it == state.today }) Timetable.nowMinute() else null,
)

@Composable
private fun WeekHeader(state: UiState, onWeekChange: (Int) -> Unit) {
    val currentWeek = Semester.weekOf(state.today)

    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("◀", Modifier.clickable { onWeekChange(state.week - 1) }.padding(8.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("第 ${state.week} 周", style = MaterialTheme.typography.titleMedium)
                    if (state.week == currentWeek) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "本周",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                    RoundedCornerShape(4.dp),
                                )
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }
                val monday = state.weekDays.firstOrNull()
                val sunday = state.weekDays.lastOrNull()
                if (monday != null && sunday != null) {
                    Text(
                        "${DateTimeExt.formatDate(monday)} - ${DateTimeExt.formatDate(sunday)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 翻到别的周之后给一个一键回来的入口——翻了十几周再手动翻回来很难受。
                if (state.week != currentWeek) {
                    Text(
                        "回到本周",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { onWeekChange(currentWeek) }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            Text("▶", Modifier.clickable { onWeekChange(state.week + 1) }.padding(8.dp))
        }
    }
}

@Composable
private fun DayHeaderCell(date: LocalDate, isToday: Boolean, allDayCount: Int = 0, onClick: () -> Unit) {
    Column(
        Modifier
            .width(104.dp)
            .height(DAY_HEADER_HEIGHT)
            .clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            DateTimeExt.weekName(date),
            style = MaterialTheme.typography.labelSmall,
            color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
        )
        Text(
            "${date.monthValue}/${date.dayOfMonth}",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 全天型日程的角标：它们没有「几点」，不进时间网格，
        // 只用这一行提示「这天还有 N 件全天的事」。点这一列可在下方看到详情。
        if (allDayCount > 0) {
            Text(
                "全天 $allDayCount",
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.tertiary,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * 一天的列：绝对定位地画出每条日程。
 *
 * 做法（参考日历类应用的通用做法，例如 Etar / Simple Calendar 的日视图）：
 * 1. 先把当天所有日程按开始时刻排序；
 * 2. 贪心地把它们分到若干「互相不重叠的轨道」里 —— 每条进入第一个
 *    与它不冲突的轨道。同一时段重叠的条目因此落在不同轨道上；
 * 3. 一天里总共用了 K 条轨道，每条就占 `1/K` 宽。
 *
 * 这样重叠的事件**并排显示**，谁也不遮谁，也不用点开才能看全。
 */
@Composable
private fun DayColumn(
    sessions: List<Session>,
    rangeStart: Int,
    height: Dp,
    /** 当前时刻（分钟）。只有「今天」那一列会传非 null，用来画当前时间线。 */
    nowMinute: Int? = null,
    onClickEmpty: () -> Unit,
    onLongPress: (Session) -> Unit,
) {
    val tracks = remember(sessions) { assignTracks(sessions) }
    // 网格覆盖多少分钟（由高度反推，保证与 minuteToDp 的比例完全一致）
    val gridMinutes = (height / HOUR_HEIGHT * 60).toInt()

    Box(
        Modifier
            .width(104.dp)
            .height(height)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.16f))
            .clickable { onClickEmpty() }
    ) {
        // 整点横线：让「这块占多久」一眼可估
        Box(Modifier.fillMaxSize()) {
            var hour = ((rangeStart + 59) / 60) * 60
            while (minuteToDp(hour - rangeStart) < height) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .offset(y = minuteToDp(hour - rangeStart))
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f))
                )
                hour += 60
            }
        }

        for ((session, track, trackCount) in tracks) {
            /*
             * 先裁到网格可见范围内（见 clipToRange 的注释：
             * 不裁的话，一条起点在网格上方的长日程会把整列盖住）。
             * 完全在范围外的返回 null，直接不画。
             */
            val visible = clipToRange(
                startMinute = session.startMinute,
                // 「具体时刻」的起止相同，clipToRange 会当成空区间丢掉；
                // 给它 1 分钟的可见高度，再靠下面的 20dp 下限画成一个可点的小块。
                endMinute = if (session.isPoint) session.startMinute + 1 else session.endMinute,
                rangeStart = rangeStart,
                rangeMinutes = gridMinutes,
            ) ?: continue
            val (visibleStart, visibleEnd) = visible

            val top = minuteToDp(visibleStart - rangeStart)
            // 太短的日程也要能点到，所以最少给 20dp
            val rawHeight = minuteToDp(visibleEnd - visibleStart)
            val blockHeight = if (rawHeight < 20.dp) 20.dp else rawHeight
            val laneWidth = 104.dp / trackCount
            SessionBlock(
                session = session,
                top = top,
                height = blockHeight,
                width = laneWidth,
                leftOffset = laneWidth * track,
                onLongPress = onLongPress,
            )
        }

        /*
         * 当前时间线。
         *
         * 日历类应用的标配：一条细红线横穿「今天」那一列，左边配个小圆点。
         * 没有它，看课表时要先看手机时间再到列里找位置，很费劲。
         *
         * 只在时刻落在可见区间内才画，否则会跑到格子外面去。
         */
        if (nowMinute != null && nowMinute in rangeStart..(rangeStart + gridMinutes)) {
            Box(
                Modifier
                    .offset(y = minuteToDp(nowMinute - rangeStart))
                    .fillMaxWidth()
                    .height(1.5.dp)
                    .background(KindColors.conflict)
            )
            Box(
                Modifier
                    .offset(x = (-3).dp, y = minuteToDp(nowMinute - rangeStart) - 3.dp)
                    .size(7.dp)
                    .background(KindColors.conflict, RoundedCornerShape(4.dp))
            )
        }
    }
}

@Composable
private fun SessionBlock(
    session: Session,
    top: Dp,
    height: Dp,
    width: Dp,
    leftOffset: Dp,
    onLongPress: (Session) -> Unit,
) {
    val accent = if (session.state == SessionState.CANCELLED) KindColors.cancelled else KindColors.of(session.kind)
    // 矮块用紧凑排版：字号更小、不显示地点、标题单行。
    // 一行时间 + 一行标题在 10sp 下约需 26dp；矮于这个高度就必须让位，否则标题会被裁掉
    // （40 分钟的活动实测就会被裁，那正是答疑这类常见时长）。
    val compact = height < 40.dp
    Box(
        Modifier
            .offset(x = leftOffset + 1.dp, y = top)
            .width(width - 2.dp)
            .height(height - 2.dp)
            .background(accent.copy(alpha = 0.24f), RoundedCornerShape(4.dp))
            .clickable { onLongPress(session) }
            .padding(horizontal = 3.dp, vertical = 1.dp)
    ) {
        if (compact) {
            Text(
                "${session.startLabel} ${session.title}",
                fontSize = 9.sp,
                lineHeight = 10.sp,
                color = accent,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                // 时刻放在最前面：这是最该一眼看到的信息（也是用户反复强调的那一点）
                Text(
                    session.timeLabel,
                    fontSize = 10.sp,
                    lineHeight = 11.sp,
                    color = accent,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    session.title,
                    fontSize = 10.sp,
                    lineHeight = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium,
                )
                if (session.location.isNotEmpty() && height > 56.dp) {
                    Text(
                        session.location,
                        fontSize = 9.sp,
                        lineHeight = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (session.state == SessionState.CANCELLED) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.22f), RoundedCornerShape(4.dp))
            )
        }
    }
}
