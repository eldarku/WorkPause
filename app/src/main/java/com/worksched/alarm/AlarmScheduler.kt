package com.worksched.alarm

// Modified for WorkPause: scope internal actions to this application.
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.worksched.BuildConfig
import com.worksched.data.Schedule
import com.worksched.data.DayMode
import com.worksched.profile.ManualSessions
import com.worksched.service.ManualSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

object AlarmScheduler {

    const val ACTION_TOGGLE = BuildConfig.APPLICATION_ID + ".action.TOGGLE"
    const val EXTRA_ENABLE = "enable"
    const val EXTRA_DAY = "day"

    private val ALL_DAYS = listOf(
        Calendar.SUNDAY, Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
        Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY
    )

    fun scheduleAllAsync(context: Context) {
        CoroutineScope(Dispatchers.Default).launch {
            val schedule = ManualSessions.refresh(context.applicationContext).schedule
            ManualSessionService.update(context.applicationContext)
            scheduleAll(context.applicationContext, schedule)
            requestReconcile(context.applicationContext)
        }
    }

    /**
     * Iterate over ALL 7 days: schedule resume+pause slots for selected days, and
     * cancel both slots for unselected days (so de-selecting a day clears its events).
     */
    fun scheduleAll(context: Context, schedule: Schedule) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        ALL_DAYS.forEach { day ->
            val hours = schedule.forDay(day)
            if (!schedule.enabled) {
                am.cancel(pendingIntent(context, day, enable = true))
                am.cancel(pendingIntent(context, day, enable = false))
            } else {
                when (hours.effectiveMode()) {
                    DayMode.SCHEDULE -> {
                        scheduleSlot(context, am, day, enable = true, hours.resumeMinutes)
                        val pauseDay = if (hours.isOvernight()) day % 7 + 1 else day
                        scheduleSlot(context, am, day, enable = false, hours.pauseMinutes, pauseDay)
                    }
                    DayMode.ALL_ON -> {
                        scheduleSlot(context, am, day, enable = true, 0)
                        scheduleSlot(context, am, day, enable = false, 0, day % 7 + 1)
                    }
                    DayMode.ALL_OFF -> {
                        am.cancel(pendingIntent(context, day, enable = true))
                        scheduleSlot(context, am, day, enable = false, 0)
                    }
                }
            }
        }
    }

    fun requestReconcile(context: Context) {
        context.sendBroadcast(Intent(context, ToggleReceiver::class.java).setAction(ACTION_TOGGLE))
    }

    fun cancelAll(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        ALL_DAYS.forEach { day ->
            am.cancel(pendingIntent(context, day, enable = true))
            am.cancel(pendingIntent(context, day, enable = false))
        }
    }

    private fun pendingIntent(context: Context, day: Int, enable: Boolean): PendingIntent {
        val intent = Intent(context, ToggleReceiver::class.java).apply {
            action = ACTION_TOGGLE
            putExtra(EXTRA_ENABLE, enable)
            putExtra(EXTRA_DAY, day)
            setPackage(context.packageName)
        }
        val rc = day * 10 + if (enable) 1 else 0
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, rc, intent, flags)
    }

    private fun scheduleSlot(context: Context, am: AlarmManager, day: Int, enable: Boolean, minutes: Int, eventDay: Int = day) {
        val pi = pendingIntent(context, day, enable)
        val triggerMs = nextOccurrenceMillis(eventDay, minutes)
        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.canScheduleExactAlarms() else true
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
        }
    }

    /**
     * Next future occurrence of (dayOfWeek, minutesOfDay).
     *
     * Locale-independent forward scan: walk today..+7 days, pick the first date whose
     * weekday matches and whose time is still in the future. This returns *today's*
     * occurrence when it hasn't passed yet, and only rolls to next week once today's
     * time is gone — avoiding the firstDayOfWeek-dependent off-by-a-day bug that the
     * old `set(Calendar.DAY_OF_WEEK, …)` approach had.
     */
    fun nextOccurrenceMillis(dayOfWeek: Int, minutesOfDay: Int): Long {
        val nowMs = System.currentTimeMillis()
        for (offset in 0..7) {
            val c = Calendar.getInstance()
            c.add(Calendar.DAY_OF_YEAR, offset)
            if (c.get(Calendar.DAY_OF_WEEK) != dayOfWeek) continue
            c.set(Calendar.HOUR_OF_DAY, minutesOfDay / 60)
            c.set(Calendar.MINUTE, minutesOfDay % 60)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            if (c.timeInMillis > nowMs) return c.timeInMillis
        }
        // Unreachable: a given weekday always recurs within 8 days.
        return nowMs
    }
}
