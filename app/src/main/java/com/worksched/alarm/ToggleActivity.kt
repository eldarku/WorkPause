package com.worksched.alarm

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import com.worksched.profile.WorkProfileToggler
import com.worksched.profile.ManualSessions
import com.worksched.data.ScheduleStore
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * FALLBACK wake activity. Only launched by [ToggleReceiver] when the silent
 * backend is unavailable. Briefly wakes the screen so the accessibility-gesture
 * fallback can act on a rendered Quick Settings panel, then finishes.
 *
 * When MODIFY_QUIET_MODE is granted, this activity is never launched — the
 * receiver calls the silent API directly with no screen wake.
 */
class ToggleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val km = getSystemService(KeyguardManager::class.java)
            try {
                km?.requestDismissKeyguard(this, null)
            } catch (_: Throwable) { /* harmless */ }
        }

        val enable = intent.getBooleanExtra(EXTRA_ENABLE, true)
        Log.i(TAG, "alarm-triggered wake; will toggle(enable=$enable) after screen-wake delay")

        Handler(Looper.getMainLooper()).postDelayed({
            lifecycleScope.launch {
                val schedule = ScheduleStore(this@ToggleActivity).scheduleFlow.first()
                if (!schedule.enabled || schedule.isActiveAt() != enable ||
                    !ManualSessions.allows(this@ToggleActivity, enable)) {
                    Log.i(TAG, "Delayed toggle cancelled: WorkPause is off or schedule changed")
                    finish()
                    return@launch
                }
                Log.i(TAG, "screen wake settled; invoking toggle")
                WorkProfileToggler.toggle(this@ToggleActivity, enable)
            }
        }, WAKE_SETTLE_DELAY_MS)

        Handler(Looper.getMainLooper()).postDelayed({
            Log.i(TAG, "wake activity finishing")
            finish()
        }, FINISH_DELAY_MS)
    }

    companion object {
        private const val TAG = "WorkSchedWake"
        const val EXTRA_ENABLE = "enable"
        private const val WAKE_SETTLE_DELAY_MS = 1_200L
        private const val FINISH_DELAY_MS = 7_000L
    }
}
