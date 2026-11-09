package com.worksched.profile

// Modified for WorkPause: share deferred reconciliation and revalidate location on unlock.
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.worksched.R
import com.worksched.WorkSchedApp
import com.worksched.alarm.ResumeRetryScheduler
import com.worksched.data.ScheduleStore
import com.worksched.location.LocationGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ResumeReconcileReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val fromRetry = intent.action == ResumeRetryScheduler.ACTION_RESUME_RETRY
        if (!fromRetry && intent.action != Intent.ACTION_USER_PRESENT) return
        val attempt = if (fromRetry) intent.getIntExtra(ResumeRetryScheduler.EXTRA_ATTEMPT, 0).coerceAtLeast(0) else null
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                reconcile(context.applicationContext, attempt)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "ResumeReconcile"

        suspend fun reconcile(context: Context, retryAttempt: Int? = null) {
            reconcilePending(context, retryAttempt)
            WorkProfileUnlockNotifier.refresh(context)
        }

        private suspend fun reconcilePending(context: Context, retryAttempt: Int?) {
            val store = ScheduleStore(context)
            if (!store.pendingResume()) return
            if (store.pendingResumeDeadline() <= System.currentTimeMillis()) {
                Log.i(TAG, "Pending resume expired; cancelling")
                store.setPendingResume(false, 0L)
                ResumeRetryScheduler.cancel(context)
                WorkProfileUnlockNotifier.cancel(context)
                return
            }
            if (QuietModeBackend.isDeviceLocked(context)) {
                Log.i(TAG, "Device still locked; keeping resume pending")
                if (retryAttempt != null) ResumeRetryScheduler.schedule(context, retryAttempt + 1)
                return
            }
            val schedule = store.scheduleFlow.first()
            if (!schedule.enabled || !schedule.isActiveAt() || !ManualSessions.allows(context, true)) {
                Log.i(TAG, "Schedule no longer authorizes this deferred resume")
                store.setPendingResume(false, 0L)
                ResumeRetryScheduler.cancel(context)
                return
            }
            if (schedule.locationEnabled) {
                if (!schedule.hasValidLocation() || !LocationGate.inResumeWindowNow(schedule)) {
                    Log.w(TAG, "Location-gated resume no longer has a valid work window")
                    store.setPendingResume(false, 0L)
                    ResumeRetryScheduler.cancel(context)
                    return
                }
                if (!WorkProfileToggler.isSilent(context) || !LocationGate.canResumeNow(context, schedule)) {
                    Log.w(TAG, "Deferred location-gated resume not authorized at current location")
                    if (retryAttempt != null) ResumeRetryScheduler.schedule(context, retryAttempt + 1)
                    return
                }
            }
            if (!store.pendingResume() || store.pendingResumeDeadline() <= System.currentTimeMillis()) return
            val result = WorkProfileToggler.toggle(context, enable = true)
            if (result == ToggleResult.BLOCKED) {
                store.setPendingResume(false, 0L)
                ResumeRetryScheduler.cancel(context)
                return
            }
            if (result == ToggleResult.SUCCEEDED) {
                store.setPendingResume(false, 0L)
                ResumeRetryScheduler.cancel(context)
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(1005, NotificationCompat.Builder(context, WorkSchedApp.CHANNEL_ID)
                    .setContentTitle(context.getString(R.string.app_name))
                    .setContentText("Work-profile resume accepted. A work PIN may still be needed.")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setAutoCancel(true)
                    .build())
                Log.i(TAG, "Deferred resume completed")
            } else {
                Log.i(TAG, "Resume not completed; keeping request pending")
                if (retryAttempt != null) ResumeRetryScheduler.schedule(context, retryAttempt + 1)
            }
        }
    }
}
