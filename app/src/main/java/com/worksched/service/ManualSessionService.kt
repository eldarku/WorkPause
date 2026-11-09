package com.worksched.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.UserHandle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.worksched.MainActivity
import com.worksched.R
import com.worksched.data.ScheduleStore
import com.worksched.profile.ManualSessions
import com.worksched.profile.WorkProfileDetector
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

data class ManualMonitorStatus(val running: Boolean = false, val error: String? = null)

class ManualSessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var registered = false
    private var observedProfile: UserHandle? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val user = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_USER, UserHandle::class.java)
            if (user == null) {
                Log.w(TAG, "Profile event missing its user handle: ${intent.action}")
                return
            }
            val pending = goAsync()
            scope.launch {
                try {
                    val current = WorkProfileDetector(this@ManualSessionService).workProfileHandle()
                    if (user == current || user == observedProfile) {
                        ManualSessions.refresh(this@ManualSessionService)
                        observedProfile = current
                    }
                } catch (e: IOException) {
                    fail("Could not read manual-session state", e)
                } finally {
                    pending.finish()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, getString(R.string.manual_channel_name), NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.manual_channel_description)
            setShowBadge(false)
            setSound(null, null)
        })
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(false),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
            ContextCompat.registerReceiver(this, receiver, IntentFilter().apply {
                addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
                addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
                addAction(Intent.ACTION_MANAGED_PROFILE_ADDED)
                addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
            observedProfile = WorkProfileDetector(this).workProfileHandle()
            mutableStatus.value = ManualMonitorStatus(running = true)
        } catch (e: SecurityException) {
            fail("Android denied background monitoring. Open WorkPause settings to retry.", e)
            return
        } catch (e: IllegalStateException) {
            fail("Android could not start monitoring. Open WorkPause settings to retry.", e)
            return
        }
        scope.launch {
            try {
                ManualSessions.refresh(this@ManualSessionService)
            } catch (e: IOException) {
                fail("Could not restore manual-session state", e)
            }
        }
        val store = ScheduleStore(this)
        scope.launch {
            combine(store.scheduleFlow, store.manualSessionFlow) { schedule, manual ->
                Triple(manual.enabled, schedule.enabled, manual.state.active)
            }.distinctUntilChanged().catch { e ->
                if (e is IOException) fail("Could not read monitoring settings", e) else throw e
            }.collect { (enabled, scheduling, active) ->
                if (!enabled) stopSelf()
                else manager.notify(NOTIFICATION_ID, notification(active, scheduling))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (registered) unregisterReceiver(receiver)
        scope.cancel()
        mutableStatus.value = mutableStatus.value.copy(running = false)
        super.onDestroy()
    }

    private fun notification(active: Boolean, scheduling: Boolean = true): Notification {
        val open = PendingIntent.getActivity(this, NOTIFICATION_ID, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle(getString(R.string.manual_channel_name))
            .setContentText(getString(when {
                active -> R.string.manual_monitor_paused
                !scheduling -> R.string.manual_monitor_off
                else -> R.string.manual_monitor_watching
            }))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    private fun fail(message: String, error: Exception) {
        Log.e(TAG, message, error)
        mutableStatus.value = ManualMonitorStatus(error = message)
        stopSelf()
    }

    companion object {
        const val CHANNEL_ID = "workpause_manual_sessions"
        private const val NOTIFICATION_ID = 1020
        private const val TAG = "WorkPauseMonitor"
        private val mutableStatus = MutableStateFlow(ManualMonitorStatus())
        val status = mutableStatus.asStateFlow()

        suspend fun update(context: Context) {
            val snapshot = ScheduleStore(context).automationSnapshot()
            if (!snapshot.manual.enabled) {
                context.stopService(Intent(context, ManualSessionService::class.java))
                mutableStatus.value = ManualMonitorStatus()
                return
            }
            if (status.value.running) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, ManualSessionService::class.java))
            } catch (e: SecurityException) {
                Log.e(TAG, "Android denied monitoring startup", e)
                mutableStatus.value = ManualMonitorStatus(error = "Monitoring blocked. Open Settings to retry.")
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Monitoring startup is not currently allowed", e)
                mutableStatus.value = ManualMonitorStatus(error = "Monitoring unavailable. Open Settings to retry.")
            }
        }
    }
}
