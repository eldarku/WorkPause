package com.worksched.data

// Modified for WorkPause: validate daily schedules and disable automation on corrupt data.
import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.worksched.profile.ManualSessionSettings
import com.worksched.profile.ManualSessionState
import com.worksched.profile.ManualSessionPolicy
import com.worksched.profile.ManualSessionObservation

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "worksched")

class ScheduleStore(private val context: Context) {
    private val scheduleKey = stringPreferencesKey("schedule_json")
    private val pendingResumeKey = booleanPreferencesKey("pending_resume")
    private val pendingResumeDeadlineKey = longPreferencesKey("pending_resume_deadline")
    private val geofenceArmedKey = booleanPreferencesKey("geofence_armed")
    private val unlockReminderKey = booleanPreferencesKey("unlock_reminder_requested")
    private val manualEnabledKey = booleanPreferencesKey("manual_sessions_enabled")
    private val manualProfileKey = longPreferencesKey("manual_session_profile_serial")
    private val manualQuietKey = booleanPreferencesKey("manual_session_quiet")
    private val manualActiveKey = booleanPreferencesKey("manual_session_active")
    private val manualExpectedKey = booleanPreferencesKey("manual_session_expected")
    private val manualExpectedUntilKey = longPreferencesKey("manual_session_expected_until")
    private val json = Json { ignoreUnknownKeys = true }

    private fun decodeSchedule(raw: String?): Schedule =
        raw?.let {
            try {
                json.decodeFromString<Schedule>(it).also {
                    require(it.validationError() == null) { requireNotNull(it.validationError()) }
                }
            } catch (e: IllegalArgumentException) {
                Log.e("WorkPauseStore", "Invalid saved schedule; automation disabled", e)
                Schedule.default().copy(enabled = false)
            }
        } ?: Schedule.default()
    val scheduleFlow: Flow<Schedule> = context.dataStore.data.map { decodeSchedule(it[scheduleKey]) }

    suspend fun saveScheduleEdits(schedule: Schedule): Schedule {
        require(schedule.validationError() == null) { requireNotNull(schedule.validationError()) }
        val saved = context.dataStore.edit { prefs ->
            val enabled = decodeSchedule(prefs[scheduleKey]).enabled
            prefs[scheduleKey] = json.encodeToString(schedule.copy(enabled = enabled))
        }
        return decodeSchedule(saved[scheduleKey])
    }

    suspend fun setEnabled(enabled: Boolean): Schedule {
        val saved = context.dataStore.edit { prefs ->
            val schedule = decodeSchedule(prefs[scheduleKey]).copy(enabled = enabled)
            prefs[scheduleKey] = json.encodeToString(schedule)
            prefs[pendingResumeKey] = false
            prefs[pendingResumeDeadlineKey] = 0L
            if (!enabled) prefs[unlockReminderKey] = false
            val manual = manualSettings(prefs)
            writeManualState(prefs, if (manual.enabled)
                ManualSessionPolicy.requestFailed(manual.state).copy(active = false)
                else ManualSessionState())
        }
        return decodeSchedule(saved[scheduleKey])
    }

    private fun manualSettings(prefs: Preferences) = ManualSessionSettings(
        enabled = prefs[manualEnabledKey] ?: false,
        state = ManualSessionState(
            profileSerial = prefs[manualProfileKey], quiet = prefs[manualQuietKey],
            active = prefs[manualActiveKey] ?: false,
            expectedQuiet = prefs[manualExpectedKey],
            expectedUntil = prefs[manualExpectedUntilKey] ?: 0L
        )
    )

    val manualSessionFlow: Flow<ManualSessionSettings> = context.dataStore.data.map(::manualSettings)

    suspend fun automationSnapshot(): AutomationSnapshot {
        val prefs = context.dataStore.data.first()
        return AutomationSnapshot(decodeSchedule(prefs[scheduleKey]), manualSettings(prefs))
    }

    suspend fun setManualSessionsEnabled(enabled: Boolean) {
        context.dataStore.edit {
            it[manualEnabledKey] = enabled
            writeManualState(it, ManualSessionState())
        }
    }

    suspend fun saveManualState(state: ManualSessionState): ManualSessionSettings {
        val saved = context.dataStore.edit {
            writeManualState(it, if (it[manualEnabledKey] == true) state else ManualSessionState())
        }
        return manualSettings(saved)
    }

    suspend fun saveManualObservation(observation: ManualSessionObservation): AutomationSnapshot {
        val saved = context.dataStore.edit { prefs ->
            if (prefs[manualEnabledKey] == true) {
                writeManualState(prefs, observation.state)
                observation.schedulingEnabled?.let { enabled ->
                    prefs[scheduleKey] = json.encodeToString(decodeSchedule(prefs[scheduleKey]).copy(enabled = enabled))
                    prefs[pendingResumeKey] = false
                    prefs[pendingResumeDeadlineKey] = 0L
                    if (!enabled) prefs[unlockReminderKey] = false
                }
            } else {
                writeManualState(prefs, ManualSessionState())
            }
        }
        return AutomationSnapshot(decodeSchedule(saved[scheduleKey]), manualSettings(saved))
    }

    private fun writeManualState(prefs: androidx.datastore.preferences.core.MutablePreferences, state: ManualSessionState) {
        state.profileSerial?.let { prefs[manualProfileKey] = it } ?: prefs.remove(manualProfileKey)
        state.quiet?.let { prefs[manualQuietKey] = it } ?: prefs.remove(manualQuietKey)
        prefs[manualActiveKey] = state.active
        state.expectedQuiet?.let { prefs[manualExpectedKey] = it } ?: prefs.remove(manualExpectedKey)
        prefs[manualExpectedUntilKey] = state.expectedUntil
    }

    // ---- pending-resume state (deferred resume while device locked) ----

    suspend fun unlockReminderRequested(): Boolean =
        context.dataStore.data.first()[unlockReminderKey] ?: false

    suspend fun setUnlockReminderRequested(requested: Boolean) {
        context.dataStore.edit { it[unlockReminderKey] = requested }
    }

    data class AutomationSnapshot(val schedule: Schedule, val manual: ManualSessionSettings)

    val pendingResumeFlow: Flow<Boolean> = context.dataStore.data.map { it[pendingResumeKey] ?: false }

    suspend fun pendingResume(): Boolean =
        context.dataStore.data.first()[pendingResumeKey] ?: false

    suspend fun pendingResumeDeadline(): Long =
        context.dataStore.data.first()[pendingResumeDeadlineKey] ?: 0L

    suspend fun setPendingResume(active: Boolean, deadlineMs: Long): Boolean {
        val saved = context.dataStore.edit {
            val manual = manualSettings(it)
            val authorized = active && decodeSchedule(it[scheduleKey]).isActiveAt() &&
                !(manual.enabled && manual.state.active)
            it[pendingResumeKey] = authorized
            it[pendingResumeDeadlineKey] = if (authorized) deadlineMs else 0L
        }
        return saved[pendingResumeKey] == true
    }

    // ---- geofence-armed state (an arrival-resume proximity alert is outstanding) ----

    suspend fun geofenceArmed(): Boolean =
        context.dataStore.data.first()[geofenceArmedKey] ?: false

    suspend fun setGeofenceArmed(armed: Boolean) {
        context.dataStore.edit { it[geofenceArmedKey] = armed }
    }
}
