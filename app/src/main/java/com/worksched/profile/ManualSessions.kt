package com.worksched.profile

import android.content.Context
import android.util.Log
import com.worksched.alarm.AlarmScheduler
import com.worksched.alarm.ResumeRetryScheduler
import com.worksched.data.ScheduleStore.AutomationSnapshot
import com.worksched.data.ScheduleStore
import com.worksched.location.GeofenceManager
import com.worksched.service.WorkProfileA11yService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

enum class ToggleResult { SUCCEEDED, DEFERRED, BLOCKED }

object ManualSessions {
    private val mutex = Mutex()
    private const val TAG = "WorkPauseManual"

    suspend fun refresh(context: Context): AutomationSnapshot = mutex.withLock { observe(context) }

    suspend fun allows(context: Context, enable: Boolean): Boolean = mutex.withLock {
        val snapshot = observe(context)
        ManualSessionPolicy.allows(snapshot.schedule.enabled, snapshot.manual, snapshot.schedule.isActiveAt(), enable)
    }

    suspend fun performToggle(context: Context, enable: Boolean, action: suspend () -> Boolean): ToggleResult =
        mutex.withLock {
            val store = ScheduleStore(context)
            val snapshot = observe(context)
            if (!ManualSessionPolicy.allows(snapshot.schedule.enabled, snapshot.manual,
                    snapshot.schedule.isActiveAt(), enable)) {
                Log.i(TAG, "Profile request blocked: automation disabled, manual session, or changed schedule")
                return@withLock ToggleResult.BLOCKED
            }
            if (snapshot.manual.enabled) {
                store.saveManualState(ManualSessionPolicy.requesting(snapshot.manual.state, enable, System.currentTimeMillis()))
            }
            var succeeded = false
            try {
                succeeded = action()
                if (succeeded) ToggleResult.SUCCEEDED else ToggleResult.DEFERRED
            } finally {
                if (!succeeded && snapshot.manual.enabled) withContext(NonCancellable) {
                    val current = store.automationSnapshot()
                    store.saveManualState(ManualSessionPolicy.requestFailed(current.manual.state))
                }
            }
        }

    private suspend fun observe(context: Context): AutomationSnapshot {
        val store = ScheduleStore(context)
        val snapshot = store.automationSnapshot()
        if (!snapshot.manual.enabled && snapshot.manual.state == ManualSessionState()) return snapshot
        val detector = WorkProfileDetector(context)
        val profile = detector.workProfileHandle()
        val serial = detector.workProfileSerial()
        val quiet = if (profile != null) detector.isWorkProfileQuiet() else null
        if (snapshot.manual.enabled && snapshot.schedule.enabled && profile != null &&
            (serial == null || quiet == null)) {
            Log.w(TAG, "Cannot verify profile state; keeping persisted session and withholding automation")
            return snapshot.copy(manual = snapshot.manual.copy(state = snapshot.manual.state.copy(quiet = null)))
        }
        val next = ManualSessionPolicy.observe(
            snapshot.manual.state, snapshot.manual.enabled && snapshot.schedule.enabled,
            serial, quiet,
            snapshot.schedule.isActiveAt(), System.currentTimeMillis()
        )
        if (next == snapshot.manual.state) return snapshot
        val saved = store.saveManualState(next)
        if (saved.state.active && !snapshot.manual.state.active) {
            Log.i(TAG, "Manual work session started; scheduled changes suspended")
            store.setPendingResume(false, 0L)
            ResumeRetryScheduler.cancel(context)
            GeofenceManager.disarm(context)
            store.setGeofenceArmed(false)
            WorkProfileA11yService.instance?.cancelPendingToggle()
            WorkProfileUnlockNotifier.cancel(context)
        } else if (!saved.state.active && snapshot.manual.state.active) {
            Log.i(TAG, "Manual work session ended; restoring the current schedule")
            AlarmScheduler.requestReconcile(context)
        }
        return snapshot.copy(manual = saved)
    }
}
