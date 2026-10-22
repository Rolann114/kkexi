package com.ww.kkexi

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 本地提醒调度。
 *
 * 硬约束（AGENTS.md 第 1 条）：整个提醒链路不依赖网络、不依赖推送、不依赖账号。
 * 用 [AlarmManager.setExactAndAllowWhileIdle]，断网、锁屏、后台被杀之后（重启有
 * [BootReceiver] 重排）都能响。
 *
 * 调度策略：每次重排只排「现在起 [DAYS] 天内」的闹钟。原因有两条：
 * 1. AlarmManager 有条数上限，整学期几千条会被系统丢弃；
 * 2. 排太远，用户改了规则也来不及取消。
 * 因此 App 任何一次进入前台都会重排（见 MainActivity），并额外卖一个「明天 00:05」的每日重排闹钟。
 */
object Alarms {

    const val CHANNEL_EVENT = "kkexi_event"
    const val CHANNEL_DAILY = "kkexi_daily"

    private const val DAYS = 7
    private const val RESCHEDULE_HOUR = 0
    private const val RESCHEDULE_MINUTE = 5

    /**
     * 重建全部闹钟。
     *
     * 幂等：同一 (eventId, date) 永远算出同一个 requestCode，重复调用只会覆盖，不会重复响。
     */
    fun reschedule(context: Context) {
        val repository = Repository(context)
        val settings = Settings(context)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now()
        val today = now.toLocalDate()

        // 总开关关掉时：先取消所有已排的提醒，再只保留每日重排与汇总，避免关不干净。
        if (!settings.remindersEnabled) {
            for (session in repository.sessions(today, today.plusDays(DAYS.toLong()))) {
                cancel(context, session)
            }
            scheduleDailyBriefing(context, settings, alarmManager, zone)
            scheduleRescheduleTick(context, alarmManager, zone, now)
            return
        }

        for (session in repository.sessions(today, today.plusDays(DAYS.toLong()))) {
            if (session.state == SessionState.CANCELLED) continue
            cancel(context, session)

            val event = repository.event(session.eventId)
            if (event != null && !event.reminderEnabled) continue

            val lead = when (session.kind) {
                EventKind.COURSE -> settings.courseLeadMinutes
                else -> event?.remindMinutesBefore ?: settings.eventLeadMinutes
            }
            val fireAt = session.date.atStartOfDay().plusMinutes(session.startMinute.toLong()).minusMinutes(lead.toLong())
            if (fireAt.isBefore(now)) continue

            val intent = Intent(context, ReminderReceiver::class.java).apply {
                action = ReminderReceiver.ACTION_REMIND
                putExtra(ReminderReceiver.EXTRA_EVENT_ID, session.eventId)
                putExtra(ReminderReceiver.EXTRA_DATE, session.date.toString())
                putExtra(ReminderReceiver.EXTRA_TITLE, session.title)
                putExtra(ReminderReceiver.EXTRA_LOCATION, session.location)
                putExtra(ReminderReceiver.EXTRA_START, session.timeLabel)
                putExtra(ReminderReceiver.EXTRA_KIND, session.kind.name)
                putExtra(ReminderReceiver.EXTRA_LEAD, lead)
            }
            val pending = PendingIntent.getBroadcast(
                context,
                requestCode(session),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val triggerAt = fireAt.atZone(zone).toInstant().toEpochMilli()
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } catch (_: SecurityException) {
                // Android 12+ 未授予「闹钟与提醒」权限时退化为非精确闹钟：
                // 可能晚几分钟，但绝不崩、也绝不静默不响。界面会提示用户去授权。
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
        }

        scheduleDailyBriefing(context, settings, alarmManager, zone)
        scheduleRescheduleTick(context, alarmManager, zone, now)
    }

    /** 取消某一次发生的提醒。 */
    fun cancel(context: Context, session: Session) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java).apply { action = ReminderReceiver.ACTION_REMIND }
        val pending = PendingIntent.getBroadcast(
            context,
            requestCode(session),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.cancel(pending)
    }

    /** 稳定且唯一的 requestCode：同一条事件同一天永远得到同一个值。 */
    private fun requestCode(session: Session): Int =
        (session.eventId.hashCode() * 31 + session.date.toEpochDay().toInt()) and 0x7FFFFFFF

    private fun scheduleDailyBriefing(
        context: Context,
        settings: Settings,
        alarmManager: AlarmManager,
        zone: ZoneId,
    ) {
        val intent = Intent(context, ReminderReceiver::class.java).apply { action = ReminderReceiver.ACTION_DAILY }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pending = PendingIntent.getBroadcast(context, 1, intent, flags)
        alarmManager.cancel(pending)
        if (!settings.dailyBriefingEnabled) return

        val now = LocalDateTime.now()
        val minute = settings.dailyBriefingMinute
        var fire = now.toLocalDate().atStartOfDay().plusMinutes(minute.toLong())
        if (!fire.isAfter(now)) fire = fire.plusDays(1)
        val triggerAt = fire.atZone(zone).toInstant().toEpochMilli()
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        } catch (_: SecurityException) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    /** 每天 00:05 重排一次滚动窗口，避免 7 天之后的行程没有闹钟。 */
    private fun scheduleRescheduleTick(
        context: Context,
        alarmManager: AlarmManager,
        zone: ZoneId,
        now: LocalDateTime,
    ) {
        val intent = Intent(context, ReminderReceiver::class.java).apply { action = ReminderReceiver.ACTION_RESCHEDULE }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pending = PendingIntent.getBroadcast(context, 2, intent, flags)
        alarmManager.cancel(pending)
        var fire = now.toLocalDate().atStartOfDay().plusHours(RESCHEDULE_HOUR.toLong()).plusMinutes(RESCHEDULE_MINUTE.toLong())
        if (!fire.isAfter(now)) fire = fire.plusDays(1)
        val triggerAt = fire.atZone(zone).toInstant().toEpochMilli()
        try {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        } catch (_: SecurityException) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    // ---------------------------------------------------------------- 通知通道

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_EVENT, "行程提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "每一条行程开始前的提醒"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DAILY, "每日汇总", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "每天早上把当天的安排汇总成一条"
            }
        )
    }

    /** 「闹钟与提醒」精确闹钟权限是否已授予（Android 12+）。 */
    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return manager.canScheduleExactAlarms()
    }

    /** 把当前时刻转成便于调试的字符串。 */
    fun nowLabel(): String = Instant.now().atZone(ZoneId.systemDefault()).toLocalDateTime().toString()
}

/** 单次行程提醒。 */
class ReminderReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_REMIND = "com.ww.kkexi.REMIND"
        const val ACTION_DAILY = "com.ww.kkexi.DAILY"
        const val ACTION_RESCHEDULE = "com.ww.kkexi.RESCHEDULE"

        const val EXTRA_EVENT_ID = "eventId"
        const val EXTRA_DATE = "date"
        const val EXTRA_TITLE = "title"
        const val EXTRA_LOCATION = "location"
        const val EXTRA_START = "start"
        const val EXTRA_PERIOD = "period"
        const val EXTRA_KIND = "kind"
        const val EXTRA_LEAD = "lead"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_RESCHEDULE -> {
                Alarms.reschedule(context)
                return
            }
            ACTION_DAILY -> {
                notifyDaily(context)
                Alarms.reschedule(context)
                return
            }
            ACTION_REMIND -> notifyOne(context, intent)
        }
    }

    private fun notifyOne(context: Context, intent: Intent) {
        Alarms.ensureChannels(context)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: return
        val location = intent.getStringExtra(EXTRA_LOCATION).orEmpty()
        val start = intent.getStringExtra(EXTRA_START).orEmpty()
        val lead = intent.getIntExtra(EXTRA_LEAD, 30)

        val tail = if (location.isEmpty()) "" else "  @$location"
        val body = "$start$tail · ${lead} 分钟后"

        val notification = NotificationCompat.Builder(context, Alarms.CHANNEL_EVENT)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .build()

        val id = intent.getStringExtra(EXTRA_EVENT_ID).hashCode() * 31 +
            (intent.getStringExtra(EXTRA_DATE)?.hashCode() ?: 0)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        try {
            manager.notify(id and 0x7FFFFFFF, notification)
        } catch (_: SecurityException) {
            // 通知权限被关：不崩，用户在设置里能看到提示。
        }
    }

    /** 早晨汇总：今天有几件事、第一件是什么。 */
    private fun notifyDaily(context: Context) {
        val settings = Settings(context)
        if (!settings.dailyBriefingEnabled) return
        Alarms.ensureChannels(context)

        val repository = Repository(context)
        val today = LocalDate.now()
        val sessions = repository.sessionsOn(today).filter { it.state != SessionState.CANCELLED }
        if (sessions.isEmpty()) return

        val first = sessions.first()
        val body = buildString {
            append("今天 ")
            append(sessions.size)
            append(" 件事，第一件：")
            append(first.timeLabel)
            append(' ')
            append(first.title)
            if (first.location.isNotEmpty()) {
                append(" @")
                append(first.location)
            }
        }

        val notification = NotificationCompat.Builder(context, Alarms.CHANNEL_DAILY)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle("今日 ${sessions.size} 件事")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        try {
            manager.notify(9001, notification)
        } catch (_: SecurityException) {
            // 同上：权限被关不崩。
        }
    }
}

/** 开机或应用被更新后重排闹钟——否则重启一次提醒就全丢了。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            Alarms.reschedule(context)
        }
    }
}
