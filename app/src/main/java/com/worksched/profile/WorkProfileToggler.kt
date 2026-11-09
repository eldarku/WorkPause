package com.worksched.profile

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import com.worksched.R
import com.worksched.WorkSchedApp
import com.worksched.service.WorkProfileA11yService

/**
 * Public entry point for "pause" / "resume" requests. Dispatches to the best
 * available backend:
 *
 *   1. QuietModeBackend — silent, direct API. Used when MODIFY_QUIET_MODE is granted.
 *   2. WorkProfileA11yService — visible Quick Settings gesture. Fallback when the
 *      permission has not been granted yet (so the app works on a fresh install).
 *
 * The result distinguishes a successful request, a credential-deferred resume,
 * and a request blocked by current scheduling or a manual session.
 * A deferred resume is NOT a failure and must NOT fall back to the
 * A11y gesture — the caller persists a pending-resume flag and retries on unlock.
 */
object WorkProfileToggler {

    private const val TAG = "WorkProfileToggler"

    /** True when the silent backend is usable — callers can skip the screen-wake path. */
    fun isSilent(context: Context): Boolean = QuietModeBackend.isAvailable(context)

    /**
     * The accessibility backend reports success when its gesture was dispatched;
     * delayed gestures recheck authorization before acting.
     */
    suspend fun toggle(context: Context, enable: Boolean): ToggleResult =
        ManualSessions.performToggle(context, enable) { toggleAuthorized(context, enable) }

    suspend fun resumeFromUserAction(context: Context): ToggleResult =
        ManualSessions.performToggle(context, true) { QuietModeBackend.resumeFromUserAction(context) }

    private suspend fun toggleAuthorized(context: Context, enable: Boolean): Boolean {
        if (enable) WorkProfileUnlockNotifier.recordResumeRequest(context)
        else WorkProfileUnlockNotifier.cancel(context)
        if (QuietModeBackend.isAvailable(context)) {
            // Silent backend is authoritative when the permission is held. A false
            // result on resume means "deferred (locked)", handled by the caller —
            // never fall back to the visible gesture here.
            val ok = QuietModeBackend.toggle(context, enable)
            Log.i(TAG, "toggle(enable=$enable) via QuietModeBackend → $ok")
            WorkProfileUnlockNotifier.refresh(context)
            return ok
        }

        // Fallback: visible Quick Settings gesture via accessibility service.
        Log.i(TAG, "toggle(enable=$enable) via A11y gesture (visible)")
        val handled = WorkProfileA11yService.requestToggle(context, enable)
        if (!handled) postSetupNotification(context, enable)
        if (handled) WorkProfileUnlockNotifier.refresh(context)
        return handled
    }

    private fun postSetupNotification(context: Context, enable: Boolean) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val text = "Cannot ${if (enable) "resume" else "pause"} — grant MODIFY_QUIET_MODE via ADB " +
            "or enable the 'Work Profile Toggler' accessibility service."
        val notif = NotificationCompat.Builder(context, WorkSchedApp.CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .build()
        nm.notify(if (enable) 1003 else 1004, notif)
    }
}
