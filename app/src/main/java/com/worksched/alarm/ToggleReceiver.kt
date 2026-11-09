package com.worksched.alarm

// Modified for WorkPause: fail closed on location setup errors, including the visible fallback.
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.worksched.R
import com.worksched.WorkSchedApp
import com.worksched.data.ScheduleStore
import com.worksched.location.GeofenceManager
import com.worksched.location.LocationGate
import com.worksched.profile.WorkProfileToggler
import com.worksched.profile.WorkProfileDetector
import com.worksched.profile.WorkProfileUnlockNotifier
import com.worksched.profile.ManualSessions
import com.worksched.profile.ToggleResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ToggleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmScheduler.ACTION_TOGGLE) return
        val appCtx = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val store = ScheduleStore(appCtx)
                val schedule = store.scheduleFlow.first()
                try {
                    if (!schedule.enabled) {
                        WorkProfileUnlockNotifier.cancel(appCtx)
                        store.setPendingResume(false, 0L)
                        ResumeRetryScheduler.cancel(appCtx)
                        GeofenceManager.disarm(appCtx)
                        store.setGeofenceArmed(false)
                        return@launch
                    }
                    if (!WorkProfileDetector(appCtx).isWorkProfilePresent()) {
                        WorkProfileUnlockNotifier.cancel(appCtx)
                        Log.i(TAG, "No work profile; schedule retained without toggling")
                        return@launch
                    }
                    val enable = schedule.isActiveAt()
                    if (!ManualSessions.allows(appCtx, enable)) {
                        Log.i(TAG, "Reconciliation yielded to manual work session or changed schedule")
                        return@launch
                    }
                    val silent = WorkProfileToggler.isSilent(appCtx)
                    if (enable && schedule.locationEnabled &&
                        (!silent || !schedule.hasValidLocation() || !LocationGate.inResumeWindowNow(schedule))) {
                        Log.w(TAG, "Location-gated resume blocked: setup incomplete or outside work window")
                        postNotification(appCtx, true, failure = "Work apps remain paused. Check location and silent-mode setup.")
                        return@launch
                    }
                    if (!silent) {
                        val launch = Intent(appCtx, ToggleActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                            putExtra(ToggleActivity.EXTRA_ENABLE, enable)
                        }
                        try {
                            appCtx.startActivity(launch)
                            postNotification(appCtx, enable)
                        } catch (e: ActivityNotFoundException) {
                            Log.e(TAG, "Visible toggle activity unavailable", e)
                            postNotification(appCtx, enable, failure = "Could not open the work-profile toggle.")
                        } catch (e: SecurityException) {
                            Log.e(TAG, "Visible toggle activity denied", e)
                            postNotification(appCtx, enable, failure = "Android blocked the work-profile toggle.")
                        }
                        return@launch
                    }
                    if (!enable) {
                        if (WorkProfileToggler.toggle(appCtx, false) == ToggleResult.BLOCKED) return@launch
                        store.setPendingResume(false, 0L)
                        ResumeRetryScheduler.cancel(appCtx)
                        if (store.geofenceArmed()) {
                            GeofenceManager.disarm(appCtx)
                            store.setGeofenceArmed(false)
                        }
                        postNotification(appCtx, false)
                    } else if (schedule.locationEnabled) {
                        if (LocationGate.canResumeNow(appCtx, schedule)) {
                            resumeNow(store, appCtx, minOf(System.currentTimeMillis() + DEFER_WINDOW_MS, LocationGate.resumeWindowEndMillis(schedule)))
                        } else {
                            store.setPendingResume(false, 0L)
                            ResumeRetryScheduler.cancel(appCtx)
                            val armed = GeofenceManager.arm(
                                appCtx, requireNotNull(schedule.latitude), requireNotNull(schedule.longitude),
                                schedule.radiusMeters, LocationGate.resumeWindowEndMillis(schedule)
                            )
                            store.setGeofenceArmed(armed)
                            postNotification(appCtx, true, locationPending = armed,
                                failure = if (armed) null else "Location monitoring unavailable. Work apps remain paused.")
                        }
                    } else {
                        resumeNow(store, appCtx, System.currentTimeMillis() + DEFER_WINDOW_MS)
                    }
                } finally {
                    AlarmScheduler.scheduleAll(appCtx, schedule)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun resumeNow(store: ScheduleStore, context: Context, deadline: Long) {
        val result = WorkProfileToggler.toggle(context, true)
        if (result == ToggleResult.BLOCKED) return
        if (result == ToggleResult.SUCCEEDED) {
            store.setPendingResume(false, 0L)
            ResumeRetryScheduler.cancel(context)
            postNotification(context, true)
        } else {
            store.setPendingResume(true, deadline)
            ResumeRetryScheduler.schedule(context, 0)
            Log.i(TAG, "Resume deferred; retry chain started")
            postNotification(context, true, deferred = true)
        }
        WorkProfileUnlockNotifier.refresh(context)
    }

    private fun postNotification(context: Context, enable: Boolean, deferred: Boolean = false,
                                 locationPending: Boolean = false, failure: String? = null) {
        val text = failure ?: when {
            locationPending -> "Work apps will turn on when you reach your location."
            deferred -> "Work profile needs unlocking. Use the unlock reminder after unlocking your phone."
            enable -> "Resuming work profile"
            else -> "Pausing work profile"
        }
        val notification = NotificationCompat.Builder(context, WorkSchedApp.CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(if (enable) 1001 else 1002, notification)
    }

    companion object {
        private const val TAG = "WorkPauseAlarm"
        private const val DEFER_WINDOW_MS = 6 * 60 * 60 * 1000L
    }
}
