package com.ww.kkexi

/**
 * 课表网格的**纯计算**部分：时间范围与轨道分配。
 *
 * ## 为什么单独放一个文件
 * 这两个函数不碰 Compose（不引 `Dp`、不引 `@Composable`），
 * 所以可以在**普通 JVM 单测**里直接调。
 * 如果把它们写在 `WeekScreen.kt` 里，单测一加载那个类就要 `androidx.compose.ui.unit.Dp`，
 * 而单元测试的 classpath 上没有 Compose 运行时——实测直接：
 *   `java.lang.NoClassDefFoundError: androidx/compose/ui/unit/Dp`
 * 把它们挪出来，逻辑就能被真正测到（而不是只好不测）。
 */

/**
 * 网格要覆盖的时间范围（分钟），返回 `IntRange`。
 *
 * [timedSessions] 必须**已经排除「全天」型**日程——它们的区间是 00:00–24:00，
 * 一算进来网格就被拉成整天（这是真机上看到过的 bug：
 * 晚上 9 点打开课程表，早上全空、还滚不到当前时刻）。
 * 全天型由日列头部的角标表示。
 *
 * [nowMinute] 传当前时刻（仅当这一周包含今天），让「现在」始终落在可见范围内；
 * 传 null 表示这一周不含今天。
 *
 * ## 为什么要给「现在」后面多留 90 分钟
 * 滚动只能滚到 `gridHeight - viewportHeight`，也就是滚到底时网格末端正好贴着屏幕底。
 * 如果范围只到 `now + 30` 再向上取整，那么「滚到底」时当前时间线距离屏幕底只剩几分钟，
 * 视觉上像是卡在最下面（真机截图里就是这样）。
 * 多留 90 分钟，滚到底时时间线下方还有约一小时的空间，看着才自然。
 *
 * 起点向下取整到整点、终点向上取整到整点，左侧刻度才落在整点上。
 * 结果夹在 [00:00, 24:00]，并保证至少 1 小时高。
 */
internal fun computeVisibleRange(timedSessions: List<Session>, nowMinute: Int?): IntRange {
    val earliest = timedSessions.minOfOrNull { it.startMinute } ?: (8 * 60)
    val latest = timedSessions.maxOfOrNull { it.endMinute } ?: (18 * 60)

    val rawFrom = minOf(earliest, nowMinute ?: earliest)
    // 「现在」后面至少留 90 分钟（见上面的说明）
    val rawTo = maxOf(latest, nowMinute?.plus(90) ?: latest)

    // 上下各留 30 分钟余量，让贴着边界的日程不顶到框上。
    // 注意这里**不能**硬性夹到 06:00：真机上建过一门 02:00 的课，
    // 网格从 06:00 起画、那条课被裁掉，时间轴里有、课程表里却完全看不见。
    val from = ((rawFrom - 30).coerceAtLeast(0) / 60) * 60
    val toRaw = (rawTo + 30).coerceAtMost(24 * 60)
    val to = if (toRaw % 60 == 0) toRaw else ((toRaw / 60) + 1) * 60
    return from..maxOf(to, from + 60)
}

/**
 * 把一条日程的时间区间**裁到网格可见范围内**。
 *
 * 返回裁剪后的 `(start, end)`；若整条都在范围外（或裁剪后为空）返回 null，表示不画。
 *
 * ## 为什么必须有这一步（真机上看到过）
 * 原先只把顶部夹到 0、却按**原始时长**算高度：
 *   `top = (start - rangeStart).coerceAtLeast(0)`，`height = end - start`
 * 库里一条 `00:00–15:45` 的死线（起点在网格上方）于是从网格顶端往下铺满 15.75 小时，
 * 把整列盖住，后面几天的内容全被挡住。
 *
 * 两端都夹才是对的：起点在网格上方就从 0 开始，终点在网格下方就截到网格底。
 */
internal fun clipToRange(
    startMinute: Int,
    endMinute: Int,
    rangeStart: Int,
    rangeMinutes: Int,
): Pair<Int, Int>? {
    val rangeEnd = rangeStart + rangeMinutes
    val start = maxOf(startMinute, rangeStart)
    val end = minOf(endMinute, rangeEnd)
    return if (end <= start) null else start to end
}

/**
 * 把一天的日程分到若干互不重叠的轨道上。
 *
 * 返回 `(日程, 轨道号, 该时段需要的栏数)`。贪心算法：按开始时刻排序，
 * 每条放进第一个「最后结束时刻 ≤ 它的开始时刻」的轨道。
 *
 * 这是区间图着色的标准贪心解，用的颜色数等于最大重叠层数，是最优的。
 */
internal fun assignTracks(sessions: List<Session>): List<Triple<Session, Int, Int>> {
    if (sessions.isEmpty()) return emptyList()
    val sorted = sessions.sortedWith(compareBy({ it.startMinute }, { it.endMinute }, { it.title }))
    val trackEnds = ArrayList<Int>()   // 每条轨道当前占用到几点
    val placement = ArrayList<Pair<Session, Int>>()

    for (session in sorted) {
        var placed = -1
        for (index in trackEnds.indices) {
            if (trackEnds[index] <= session.startMinute) {
                placed = index
                break
            }
        }
        if (placed < 0) {
            trackEnds.add(session.endMinute)
            placed = trackEnds.size - 1
        } else {
            trackEnds[placed] = session.endMinute
        }
        placement += session to placed
    }

    // 轨道总数取**每条日程所在时段**的最大并发数，而不是全天轨道数。
    // 否则一天里上午有 1 条、下午有 3 条时，上午那条也会被挤成 1/3 宽。
    return placement.map { (session, track) ->
        val concurrent = placement.count { (other, otherTrack) ->
            otherTrack != track &&
                other.startMinute < session.endMinute &&
                session.startMinute < other.endMinute
        }
        // 与自己重叠的条目数 + 自己 = 这个时段实际需要几栏
        val lanes = maxOf(concurrent + 1, track + 1, 1)
        Triple(session, track, lanes)
    }
}
