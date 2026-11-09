package com.worksched.profile

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.worksched.MainActivity
import com.worksched.R
import com.worksched.alarm.AlarmScheduler
import com.worksched.data.ScheduleStore
import com.worksched.ui.theme.WorkSchedTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class UnlockWorkProfileActivity : ComponentActivity() {
    private var apps by mutableStateOf<List<LauncherActivityInfo>>(emptyList())
    private var message by mutableStateOf("")
    private var available by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var attemptedEntryResume = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        attemptedEntryResume = savedInstanceState?.getBoolean("attempted_entry_resume") ?: false
        setContent {
            WorkSchedTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(getString(R.string.open_work_apps), style = MaterialTheme.typography.headlineMedium)
                        Text(getString(R.string.unlock_picker_description), style = MaterialTheme.typography.bodyLarge)
                        if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodyLarge)
                        if (available) {
                            Button(onClick = { resumeProfile() }, enabled = !busy) {
                                Text(getString(R.string.unlock_retry))
                            }
                        }
                        TextButton(onClick = {
                            startActivity(Intent(this@UnlockWorkProfileActivity, MainActivity::class.java))
                            finish()
                        }) { Text(getString(R.string.open_workpause)) }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(apps, key = { it.componentName.flattenToString() }) { app ->
                                Button(
                                    onClick = { launchWorkApp(app) },
                                    enabled = available && !busy,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(app.label.toString(), style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            refresh()
            if (!attemptedEntryResume) {
                attemptedEntryResume = true
                if (available && QuietModeBackend.isAvailable(this@UnlockWorkProfileActivity) &&
                    WorkProfileDetector(this@UnlockWorkProfileActivity).isWorkProfileQuiet() == true) {
                    resumeProfile()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("attempted_entry_resume", attemptedEntryResume)
        super.onSaveInstanceState(outState)
    }

    private suspend fun refresh() {
        val state = WorkProfileUnlockNotifier.refresh(this)
        available = state == UnlockReminderState.UNLOCK_REQUIRED || state == UnlockReminderState.OPEN_WORK_APP
        apps = emptyList()
        if (!available) {
            message = getString(R.string.unlock_no_longer_needed)
            return
        }
        val handle = WorkProfileDetector(this).workProfileHandle() ?: return
        try {
            apps = getSystemService(LauncherApps::class.java).getActivityList(null, handle)
                .sortedBy { it.label.toString().lowercase() }
            message = getString(if (apps.isEmpty()) R.string.unlock_no_apps else R.string.unlock_choose_app)
        } catch (e: SecurityException) {
            Log.w(TAG, "Android denied access to work-profile launcher apps", e)
            message = getString(R.string.unlock_apps_denied)
        }
    }

    private fun resumeProfile() {
        busy = true
        lifecycleScope.launch {
            try {
                if (!authorize()) return@launch
                if (!QuietModeBackend.isAvailable(this@UnlockWorkProfileActivity)) {
                    message = getString(R.string.unlock_use_phone_controls)
                    return@launch
                }
                val resumed = WorkProfileToggler.resumeFromUserAction(this@UnlockWorkProfileActivity)
                AlarmScheduler.requestReconcile(this@UnlockWorkProfileActivity)
                refresh()
                if (resumed == ToggleResult.DEFERRED) message = getString(R.string.unlock_wait_for_android)
            } finally {
                busy = false
            }
        }
    }

    private fun launchWorkApp(app: LauncherActivityInfo) {
        busy = true
        lifecycleScope.launch {
            try {
                if (!authorize()) return@launch
                val handle = WorkProfileDetector(this@UnlockWorkProfileActivity).workProfileHandle() ?: return@launch
                // LauncherApps lets Android enforce the work challenge; no PIN is collected here.
                getSystemService(LauncherApps::class.java).startMainActivity(app.componentName, handle, null, null)
            } catch (e: SecurityException) {
                Log.w(TAG, "Android or policy blocked launching the work app", e)
                message = getString(R.string.unlock_launch_failed)
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "The selected work app is no longer available", e)
                message = getString(R.string.unlock_launch_failed)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Work profile is not ready to launch apps", e)
                message = getString(R.string.unlock_wait_for_android)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "The work profile changed before the selected app could open", e)
                message = getString(R.string.unlock_launch_failed)
            } finally {
                busy = false
            }
        }
    }

    private suspend fun authorize(): Boolean {
        if (!ManualSessions.allows(this, true)) {
            available = false
            message = getString(R.string.unlock_no_longer_needed)
            return false
        }
        val state = WorkProfileUnlockNotifier.refresh(this)
        if (state == UnlockReminderState.NONE || state == UnlockReminderState.WAIT_FOR_PHONE_UNLOCK) {
            refresh()
            return false
        }
        val schedule = ScheduleStore(this).scheduleFlow.first()
        if (schedule.locationEnabled && !com.worksched.location.LocationGate.canResumeNow(this, schedule)) {
            message = getString(R.string.unlock_location_not_verified)
            return false
        }
        return true
    }

    companion object {
        private const val TAG = "WorkPauseUnlock"
    }
}
