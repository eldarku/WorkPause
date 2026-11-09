package com.worksched.profile

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.worksched.R
import com.worksched.WorkSchedApp
import com.worksched.alarm.ResumeRetryScheduler
import com.worksched.data.ScheduleStore
import com.worksched.location.LocationGate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object WorkProfileUnlockNotifier {
    const val CHANNEL_ID = "workpause_work_profile_unlock"
    private const val NOTIFICATION_ID = 1010
    private const val TAG = "WorkPauseUnlock"
    private val mutex = Mutex()

    fun notificationsEnabled(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    suspend fun recordResumeRequest(context: Context) = mutex.withLock {
        val detector = WorkProfileDetector(context)
        if (detector.isWorkProfilePresent() &&
            UnlockReminderPolicy.shouldRequest(detector.isWorkProfileQuiet(), detector.isWorkProfileStorageUnlocked())) {
            ScheduleStore(context).setUnlockReminderRequested(true)
        }
    }

    suspend fun cancel(context: Context) = mutex.withLock {
        ScheduleStore(context).setUnlockReminderRequested(false)
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    suspend fun refresh(context: Context): UnlockReminderState = mutex.withLock {
        val store = ScheduleStore(context)
        val schedule = store.scheduleFlow.first()
        val manual = store.automationSnapshot().manual
        val detector = WorkProfileDetector(context)
        val locationWindowValid = !schedule.locationEnabled ||
            (schedule.hasValidLocation() && LocationGate.inResumeWindowNow(schedule))
        val state = UnlockReminderPolicy.evaluate(
            enabled = schedule.enabled && !(manual.enabled && manual.state.active),
            active = schedule.isActiveAt() && locationWindowValid,
            profilePresent = detector.isWorkProfilePresent(),
            requested = store.unlockReminderRequested(),
            deviceLocked = QuietModeBackend.isDeviceLocked(context),
            quiet = detector.isWorkProfileQuiet(),
            storageUnlocked = detector.isWorkProfileStorageUnlocked()
        )
        val manager = context.getSystemService(NotificationManager::class.java)
        if (state == UnlockReminderState.NONE || state == UnlockReminderState.WAIT_FOR_PHONE_UNLOCK) {
            manager.cancel(NOTIFICATION_ID)
            if (state == UnlockReminderState.NONE) store.setUnlockReminderRequested(false)
            // USER_PRESENT delivery varies by OEM; reuse the bounded schedule-aware retry path.
            if (state == UnlockReminderState.WAIT_FOR_PHONE_UNLOCK) ResumeRetryScheduler.schedule(context, 2)
            return@withLock state
        }
        if (!notificationsEnabled(context)) {
            Log.w(TAG, "Work-profile reminder cannot be shown: notifications or unlock channel blocked")
            return@withLock state
        }
        val pendingIntent = PendingIntent.getActivity(
            context, NOTIFICATION_ID, Intent(context, UnlockWorkProfileActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val locked = state == UnlockReminderState.UNLOCK_REQUIRED
        val title = context.getString(if (locked) R.string.unlock_notification_title else R.string.work_ready_notification_title)
        val text = context.getString(if (locked) R.string.unlock_notification_text else R.string.work_ready_notification_text)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent)
            .addAction(0, context.getString(R.string.open_work_apps), pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setGroup(CHANNEL_ID)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Android denied the work-profile notification", e)
        }
        state
    }
}
