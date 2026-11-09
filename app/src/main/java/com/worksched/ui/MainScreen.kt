package com.worksched.ui

// Modified for WorkPause: shared branding, attribution, and app-specific setup command.
import android.app.AlarmManager
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.worksched.profile.ManualSessionSettings
import com.worksched.profile.ManualSessions
import com.worksched.service.ManualSessionService
import com.worksched.R
import com.worksched.alarm.AlarmScheduler
import com.worksched.data.Schedule
import com.worksched.data.ScheduleStore
import com.worksched.profile.QuietModeBackend
import com.worksched.profile.ResumeReconcileReceiver
import com.worksched.profile.WorkProfileDetector
import com.worksched.service.WorkProfileA11yService
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import android.text.format.DateFormat
import android.util.Log
import java.io.IOException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.worksched.alarm.ResumeRetryScheduler
import com.worksched.location.GeofenceManager
import com.worksched.data.DaySchedule
import com.worksched.data.DayMode
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.input.KeyboardType
import androidx.core.content.ContextCompat
import com.worksched.location.LocationGate
import com.worksched.profile.WorkProfileUnlockNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val ctx = LocalContext.current
    val store = remember { ScheduleStore(ctx) }
    val manualSession by store.manualSessionFlow.collectAsStateWithLifecycle(initialValue = ManualSessionSettings())
    val monitor by ManualSessionService.status.collectAsStateWithLifecycle()
    val detector = remember { WorkProfileDetector(ctx) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val scheduleSaver = remember {
        Saver<Schedule, String>(save = { Json.encodeToString(it) }, restore = { Json.decodeFromString<Schedule>(it) })
    }
    var schedule by rememberSaveable(stateSaver = scheduleSaver) { mutableStateOf(Schedule.default()) }
    var loaded by rememberSaveable { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var workPauseEnabled by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var quiet by remember { mutableStateOf<Boolean?>(null) }   // true = paused, false = on
    var present by remember { mutableStateOf(true) }
    var silent by remember { mutableStateOf(QuietModeBackend.isAvailable(ctx)) }
    var a11yOn by remember { mutableStateOf(isA11yEnabled(ctx)) }
    var canExact by remember { mutableStateOf(canScheduleExact(ctx)) }
    var batteryOk by remember { mutableStateOf(isBatteryUnrestricted(ctx)) }
    var notificationsOk by remember { mutableStateOf(WorkProfileUnlockNotifier.notificationsEnabled(ctx)) }
    var pendingResume by remember { mutableStateOf(false) }
    var unlockReminder by remember { mutableStateOf(false) }
    var savedFlash by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var showSetup by remember { mutableStateOf(false) }
    var showLocationPicker by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var captureMsg by remember { mutableStateOf<String?>(null) }
    var locationFgOk by remember { mutableStateOf(LocationGate.hasForegroundLocation(ctx)) }
    var locationBgOk by remember { mutableStateOf(LocationGate.hasBackgroundLocation(ctx)) }
    val locationPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        locationFgOk = LocationGate.hasForegroundLocation(ctx)
        locationBgOk = LocationGate.hasBackgroundLocation(ctx)
    }
    val notificationPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsOk = WorkProfileUnlockNotifier.notificationsEnabled(ctx)
        scope.launch { WorkProfileUnlockNotifier.refresh(ctx) }
    }

    fun refresh() {
        present = detector.isWorkProfilePresent()
        quiet = detector.isWorkProfileQuiet()
        silent = QuietModeBackend.isAvailable(ctx)
        a11yOn = isA11yEnabled(ctx)
        canExact = canScheduleExact(ctx)
        batteryOk = isBatteryUnrestricted(ctx)
        notificationsOk = WorkProfileUnlockNotifier.notificationsEnabled(ctx)
        locationFgOk = LocationGate.hasForegroundLocation(ctx)
        locationBgOk = LocationGate.hasBackgroundLocation(ctx)
    }

    LaunchedEffect(manualSession) {
        if (manualSession.enabled) {
            refresh()
            pendingResume = store.pendingResume()
            unlockReminder = store.unlockReminderRequested()
        }
    }
    LaunchedEffect(store) {
        store.scheduleFlow.collect { saved ->
            workPauseEnabled = saved.enabled
            if (loaded) schedule = schedule.copy(enabled = saved.enabled)
        }
    }
    LaunchedEffect(Unit) {
        val saved = store.scheduleFlow.first()
        workPauseEnabled = saved.enabled
        if (!loaded) {
            schedule = saved
            loaded = true
        } else {
            schedule = schedule.copy(enabled = saved.enabled)
        }
        ready = true
        ManualSessions.refresh(ctx)
        ManualSessionService.update(ctx)
        refresh()
        ResumeReconcileReceiver.reconcile(ctx)
        pendingResume = store.pendingResume()
        unlockReminder = store.unlockReminderRequested()
        while (true) {
            delay(30_000); refresh()
            pendingResume = store.pendingResume()
            unlockReminder = store.unlockReminderRequested()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        refresh(); scope.launch {
            ManualSessions.refresh(ctx)
            ManualSessionService.update(ctx)
            WorkProfileUnlockNotifier.refresh(ctx)
            pendingResume = store.pendingResume()
            unlockReminder = store.unlockReminderRequested()
        }
    }

    suspend fun applySchedule(saved: Schedule) {
        WorkProfileUnlockNotifier.cancel(ctx)
        unlockReminder = false
        if (!saved.enabled) {
            WorkProfileA11yService.instance?.cancelPendingToggle()
            AlarmScheduler.cancelAll(ctx)
        }
        store.setPendingResume(false, 0L)
        pendingResume = false
        ResumeRetryScheduler.cancel(ctx)
        GeofenceManager.disarm(ctx)
        store.setGeofenceArmed(false)
        val current = ManualSessions.refresh(ctx).schedule
        ManualSessionService.update(ctx)
        AlarmScheduler.scheduleAll(ctx, current)
        if (current.enabled) AlarmScheduler.requestReconcile(ctx)
    }

    val fgLocationPerms = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            locationPermLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
        } else {
            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    fun openLocationSettings() {
        ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun captureCurrentLocation() {
        if (!locationFgOk) { locationPermLauncher.launch(fgLocationPerms); return }
        if (!LocationGate.locationServicesOn(ctx)) {
            // Permission granted but device Location is off — open the system Location screen
            // so the user can switch it on (GMS-free; no Play Services one-tap dialog).
            captureMsg = "Device Location is off — turn it on, then tap again."
            openLocationSettings()
            return
        }
        if (capturing) return
        capturing = true; captureMsg = "Getting your location…"
        scope.launch {
            val loc = withContext(Dispatchers.Default) { LocationGate.currentLocation(ctx) }
            if (loc != null) {
                schedule = schedule.copy(latitude = loc.latitude, longitude = loc.longitude, locationLabel = null)
                captureMsg = "Locating address…"
                val label = withContext(Dispatchers.IO) { LocationGate.describe(ctx, loc.latitude, loc.longitude) }
                schedule = schedule.copy(locationLabel = label)
                // The SELECTED PLACE card already shows the address/coordinates — clear the status
                // line so the location isn't shown twice.
                captureMsg = null
            } else {
                captureMsg = "Couldn't get a fix — move to open sky, or enter coordinates below."
            }
            capturing = false
        }
    }

    // Resolve a readable address for the current coords (used when closing the picker after manual
    // entry); runs in the background and updates the label when it lands.
    fun resolveLabelAndClose() {
        showLocationPicker = false
        val lat = schedule.latitude
        val lng = schedule.longitude
        if (lat != null && lng != null && schedule.locationLabel == null) {
            scope.launch {
                val label = withContext(Dispatchers.IO) { LocationGate.describe(ctx, lat, lng) }
                schedule = schedule.copy(locationLabel = label)
            }
        }
    }

    val locationNeedsSetup = schedule.locationEnabled && (!locationFgOk || !locationBgOk)
    val needsSetup = !silent || !canExact || !batteryOk || !notificationsOk || locationNeedsSetup

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AppHeader(
                scheduleEnabled = workPauseEnabled,
                canEdit = ready && !saving,
                onScheduleChange = { enabled ->
                    if (ready && !saving) {
                        saving = true
                        scope.launch {
                            try {
                                val saved = ManualSessions.setEnabled(ctx, enabled)
                                workPauseEnabled = saved.enabled
                                schedule = schedule.copy(enabled = saved.enabled)
                                applySchedule(saved)
                            } catch (e: IOException) {
                                Log.e("WorkPauseUI", "Could not change WorkPause state", e)
                                snackbar.showSnackbar("Could not change WorkPause. Please try again.")
                            } catch (e: SecurityException) {
                                Log.e("WorkPauseUI", "Android blocked WorkPause setup", e)
                                snackbar.showSnackbar("Android blocked setup. Review permissions in Settings.")
                            } finally {
                                saving = false
                            }
                        }
                    }
                },
                onSetup = { showSetup = true }
            )
            if (ready) Text(
                when {
                    !workPauseEnabled && manualSession.enabled && manualSession.state.active -> stringResource(R.string.manual_paused_status)
                    !workPauseEnabled && manualSession.enabled -> stringResource(R.string.manual_off_status)
                    !workPauseEnabled -> "OFF - work apps stay as set on your phone."
                    else -> "ON - following your saved schedule."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (ready && manualSession.enabled && !monitor.running) Text(
                monitor.error ?: stringResource(R.string.manual_monitor_stopped),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )

            ScheduleCard(
                schedule = schedule,
                savedFlash = savedFlash,
                saving = saving || !ready,
                onChange = { schedule = it; savedFlash = false },
                onSave = {
                    val toSave = schedule
                    saving = true
                    scope.launch {
                        try {
                            val saved = store.saveScheduleEdits(toSave)
                            workPauseEnabled = saved.enabled
                            schedule = schedule.copy(enabled = saved.enabled)
                            applySchedule(saved)
                            savedFlash = true
                        } catch (e: IOException) {
                            Log.e("WorkPauseUI", "Could not save schedule", e)
                            snackbar.showSnackbar("Could not save. Please try again.")
                        } catch (e: SecurityException) {
                            Log.e("WorkPauseUI", "Android blocked schedule setup", e)
                            snackbar.showSnackbar("Android blocked the schedule. Review permissions in Settings.")
                        } finally {
                            saving = false
                        }
                    }
                }
            )

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    when {
                        !present -> "No work profile found"
                        unlockReminder && workPauseEnabled -> "Work profile may need your work PIN"
                        pendingResume && workPauseEnabled -> "Waiting for unlock"
                        quiet == true -> "Work apps are paused"
                        quiet == false -> "Work apps are on"
                        else -> "Work profile status unavailable"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }
            if (unlockReminder && workPauseEnabled) TextButton(onClick = {
                ctx.startActivity(Intent(ctx, com.worksched.profile.UnlockWorkProfileActivity::class.java))
            }) { Text("Open work apps") }
            if (needsSetup) TextButton(onClick = { showSetup = true }) { Text("Finish setup in Settings") }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showSetup) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showSetup = false }, sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.background) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
            SetupSheet(
                profilePresent = present,
                silent = silent, a11yOn = a11yOn, canExact = canExact, batteryOk = batteryOk,
                notificationsOk = notificationsOk,
                manualEnabled = manualSession.enabled,
                manualStatus = when {
                    monitor.error != null -> requireNotNull(monitor.error)
                    !monitor.running -> ctx.getString(R.string.manual_monitor_stopped)
                    manualSession.state.active -> ctx.getString(R.string.manual_monitor_paused)
                    !workPauseEnabled -> ctx.getString(R.string.manual_monitor_off)
                    else -> ctx.getString(R.string.manual_monitor_watching)
                },
                manualEditable = ready && !saving,
                onManualChange = { enabled ->
                    saving = true
                    scope.launch {
                        try {
                            store.setManualSessionsEnabled(enabled)
                            ManualSessions.refresh(ctx)
                            ManualSessionService.update(ctx)
                            AlarmScheduler.requestReconcile(ctx)
                        } catch (e: IOException) {
                            Log.e("WorkPauseUI", "Could not save manual-session preference", e)
                            snackbar.showSnackbar("Could not save manual-session settings. Please try again.")
                        } catch (e: SecurityException) {
                            Log.e("WorkPauseUI", "Android denied monitoring setup", e)
                            snackbar.showSnackbar("Android blocked monitoring. Review permissions in Settings.")
                        } finally {
                            saving = false
                        }
                    }
                },
                onAllowNotifications = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        ctx.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                            .putExtra(Settings.EXTRA_CHANNEL_ID, WorkProfileUnlockNotifier.CHANNEL_ID))
                    }
                },
                onOpenA11y = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                onOpenExact = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                        ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                            .setData(Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                onAllowBattery = { requestIgnoreBattery(ctx) },
                locationEnabled = schedule.locationEnabled,
                locationFgOk = locationFgOk, locationBgOk = locationBgOk,
                locationServicesOn = LocationGate.locationServicesOn(ctx),
                onRequestLocation = { locationPermLauncher.launch(fgLocationPerms) },
                onAllowBackground = { requestBackgroundLocation() },
                onOpenLocationServices = { ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                onRecheck = {
                    refresh()
                    scope.launch {
                        ManualSessionService.update(ctx)
                        snackbar.currentSnackbarData?.dismiss()
                        snackbar.showSnackbar(if (QuietModeBackend.isAvailable(ctx)) "Silent mode active" else "Grant not found — using the visible fallback")
                    }
                }
            )
            if (schedule.locationEnabled) Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface
            ) {
                Column(Modifier.padding(16.dp)) {
                    LocationSection(
                        schedule = schedule,
                        locationReady = locationFgOk && locationBgOk && LocationGate.locationServicesOn(ctx),
                        onToggle = { schedule = schedule.copy(locationEnabled = it); savedFlash = false },
                        onSet = { showSetup = false; showLocationPicker = true; savedFlash = false }
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { showSetup = false; showHelp = true }) { Text("How it works") }
                TextButton(onClick = { showSetup = false; showAbout = true }) { Text("About WorkPause") }
            }
            Spacer(Modifier.height(24.dp))
            }
        }
    }
    if (showLocationPicker) {
        val pickerState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showLocationPicker = false }, sheetState = pickerState) {
            LocationPickerSheet(
                latitude = schedule.latitude, longitude = schedule.longitude,
                radiusMeters = schedule.radiusMeters, locationLabel = schedule.locationLabel,
                permissionOk = locationFgOk, locationServicesOn = LocationGate.locationServicesOn(ctx),
                capturing = capturing, captureMsg = captureMsg,
                onRequestPermission = { locationPermLauncher.launch(fgLocationPerms) },
                onUseCurrent = { captureCurrentLocation() },
                onOpenLocationServices = { openLocationSettings() },
                onManual = { lat, lng -> schedule = schedule.copy(latitude = lat, longitude = lng, locationLabel = null) },
                onRadius = { r -> schedule = schedule.copy(radiusMeters = r) },
                onClear = { schedule = schedule.copy(latitude = null, longitude = null, locationLabel = null); captureMsg = null },
                onDone = { resolveLabelAndClose() }
            )
        }
    }
    if (showHelp) HelpDialog(onDismiss = { showHelp = false })
    if (showAbout) AboutDialog(onDismiss = { showAbout = false })
}

// ---------------- Header ----------------

@Composable
private fun AppHeader(
    scheduleEnabled: Boolean, canEdit: Boolean,
    onScheduleChange: (Boolean) -> Unit, onSetup: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Surface(color = Color(0xFF13243A), shape = RoundedCornerShape(10.dp), modifier = Modifier.size(32.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(32.dp))
            }
        }
        Spacer(Modifier.size(12.dp))
        Text(stringResource(R.string.app_name), modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(
            modifier = Modifier.toggleable(
                value = scheduleEnabled, enabled = canEdit, role = Role.Switch,
                onValueChange = onScheduleChange
            ).semantics { contentDescription = "WorkPause" }.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(if (scheduleEnabled) "ON" else "OFF", style = MaterialTheme.typography.labelMedium)
            Switch(checked = scheduleEnabled, enabled = canEdit, onCheckedChange = null)
        }
        Box {
            IconButton(onClick = onSetup) { Icon(Icons.Filled.Settings, "Settings") }
        }
    }
}

// ---------------- Schedule ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleCard(
    schedule: Schedule, savedFlash: Boolean, saving: Boolean,
    onChange: (Schedule) -> Unit, onSave: () -> Unit
) {
    val sc = MaterialTheme.colorScheme
    var selectedDay by rememberSaveable { mutableStateOf(Calendar.MONDAY) }
    var showCopy by remember { mutableStateOf(false) }
    var copyTargets by remember { mutableStateOf(emptySet<Int>()) }
    var editingResume by remember { mutableStateOf<Boolean?>(null) }
    val day = schedule.forDay(selectedDay)
    val error = schedule.validationError()
    val context = LocalContext.current
    val is24Hour = DateFormat.is24HourFormat(context)
    val formatTime: (Int) -> String = { minutes ->
        val clock = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, minutes / 60)
            set(Calendar.MINUTE, minutes % 60)
        }
        DateFormat.getTimeFormat(context).format(clock.time)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = sc.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Schedule.ORDER.forEach { weekday ->
                    val selected = weekday == selectedDay
                    Surface(
                        onClick = { selectedDay = weekday },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected) sc.primary else sc.surface,
                        contentColor = if (selected) sc.onPrimary else sc.onSurface
                    ) {
                        Column(verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(requireNotNull(Schedule.SHORT[weekday]), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            Box(Modifier.size(4.dp).background(
                                if (selected) sc.onPrimary else when (schedule.forDay(weekday).effectiveMode()) {
                                    DayMode.SCHEDULE -> Color(0xFF359571)
                                    DayMode.ALL_ON -> sc.primary
                                    DayMode.ALL_OFF -> sc.outlineVariant
                                },
                                CircleShape))
                        }
                    }
                }
            }
            val modes = listOf(DayMode.SCHEDULE to "Schedule", DayMode.ALL_ON to "All day ON", DayMode.ALL_OFF to "All day OFF")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                modes.forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = day.effectiveMode() == mode,
                        onClick = { onChange(schedule.withDay(selectedDay, day.copy(enabled = mode != DayMode.ALL_OFF, mode = mode))) },
                        shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                        enabled = !saving,
                        icon = {},
                        modifier = Modifier.weight(1f)
                    ) { Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
                }
            }
            if (day.effectiveMode() == DayMode.SCHEDULE) {
                Surface(shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, sc.outlineVariant), color = sc.surface) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        ScheduleTimeRow(true, formatTime(day.resumeMinutes), !saving) { editingResume = true }
                        HorizontalDivider(color = sc.outlineVariant.copy(alpha = 0.5f))
                        ScheduleTimeRow(false, formatTime(day.pauseMinutes), !saving) { editingResume = false }
                    }
                }
                if (day.isOvernight()) {
                    Text("ON overnight until the next day's To time, unless that day is OFF all day.",
                        style = MaterialTheme.typography.bodySmall, color = sc.onSurfaceVariant)
                }
            } else {
                Text(if (day.effectiveMode() == DayMode.ALL_ON)
                    "Work profile ON all day, from midnight to midnight."
                else "Work profile OFF all day, from midnight to midnight.",
                    style = MaterialTheme.typography.bodyMedium, color = sc.onSurfaceVariant)
            }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth().height(48.dp), enabled = !saving,
                onClick = { copyTargets = emptySet(); showCopy = true }
            ) { Text("Copy to other days") }
            if (error != null) Text(error, color = sc.error, style = MaterialTheme.typography.bodySmall)
            Button(
                modifier = Modifier.fillMaxWidth().height(50.dp), onClick = onSave, enabled = !saving && error == null
            ) { Text(if (saving) "Saving..." else if (savedFlash) "Schedule saved" else "Save schedule") }
            Text("Daily changes apply when you save the schedule.", style = MaterialTheme.typography.bodySmall, color = sc.onSurfaceVariant)
        }
    }
    editingResume?.let { resume ->
        val minutes = if (resume) day.resumeMinutes else day.pauseMinutes
        val tps = rememberTimePickerState(initialHour = minutes / 60, initialMinute = minutes % 60, is24Hour = is24Hour)
        AlertDialog(
            onDismissRequest = { editingResume = null },
            confirmButton = { TextButton(onClick = {
                val selected = tps.hour * 60 + tps.minute
                onChange(schedule.withDay(selectedDay, if (resume) day.copy(resumeMinutes = selected) else day.copy(pauseMinutes = selected)))
                editingResume = null
            }) { Text("Done") } },
            dismissButton = { TextButton(onClick = { editingResume = null }) { Text("Cancel") } },
            title = { Text(if (resume) "ON from" else "ON until") },
            text = { TimePicker(state = tps) }
        )
    }
    if (showCopy) {
        AlertDialog(
            onDismissRequest = { showCopy = false },
            title = { Text("Copy ${Schedule.LONG[selectedDay]}") },
            text = {
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    Text("Replace behavior and times on selected days.", style = MaterialTheme.typography.bodySmall)
                    Schedule.ORDER.filter { it != selectedDay }.forEach { target ->
                        val checked = target in copyTargets
                        Row(
                            modifier = Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox) {
                                copyTargets = if (it) copyTargets + target else copyTargets - target
                            }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Spacer(Modifier.width(12.dp))
                            Text(requireNotNull(Schedule.LONG[target]), modifier = Modifier.padding(vertical = 12.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(enabled = copyTargets.isNotEmpty(), onClick = {
                onChange(schedule.copyDayTo(selectedDay, copyTargets))
                showCopy = false
            }) { Text("Copy") } },
            dismissButton = { TextButton(onClick = { showCopy = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ScheduleTimeRow(resume: Boolean, time: String, enabled: Boolean, onClick: () -> Unit) {
    val sc = MaterialTheme.colorScheme
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (resume) SunGlyph(sc.onSurface, Modifier.size(22.dp))
        else MoonGlyph(sc.onSurface, sc.surface, Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(if (resume) "From" else "To", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(if (resume) "Work profile turns ON" else "Work profile turns OFF",
                style = MaterialTheme.typography.bodySmall, color = sc.onSurfaceVariant)
        }
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.width(112.dp).height(48.dp)) {
            Text(time, maxLines = 1, style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ---------------- Location ----------------

@Composable
private fun LocationSection(schedule: Schedule, locationReady: Boolean, onToggle: (Boolean) -> Unit, onSet: () -> Unit) {
    val sc = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        LocationGlyph(sc.onSurfaceVariant, Modifier.size(16.dp))
        Spacer(Modifier.size(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("RESUME AT A PLACE", style = MaterialTheme.typography.labelMedium, color = sc.onSurfaceVariant, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            Text("Turn on work apps only when you arrive", style = MaterialTheme.typography.bodySmall, color = sc.onSurfaceVariant)
        }
        Switch(checked = schedule.locationEnabled, onCheckedChange = onToggle)
    }
    if (schedule.locationEnabled) {
        Spacer(Modifier.height(10.dp))
        Surface(color = sc.surfaceContainerHighest, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onSet() }) {
            Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    if (schedule.hasValidLocation()) {
                        Text("Within ${schedule.radiusMeters} m of", style = MaterialTheme.typography.labelSmall, color = sc.onSurfaceVariant)
                        Text(
                            schedule.locationLabel ?: "%.5f, %.5f".format(schedule.latitude ?: 0.0, schedule.longitude ?: 0.0),
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold
                        )
                    } else {
                        Text("Set location", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text("Pick a place for this to take effect", style = MaterialTheme.typography.bodySmall, color = sc.error)
                    }
                }
                Icon(Icons.Filled.KeyboardArrowRight, null, tint = sc.onSurfaceVariant)
            }
        }
        if (schedule.hasValidLocation() && !locationReady) {
            Spacer(Modifier.height(6.dp))
            Text("Grant location access in ⋮ → Setup & permissions so this works in the background.",
                style = MaterialTheme.typography.bodySmall, color = sc.error)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationPickerSheet(
    latitude: Double?, longitude: Double?, radiusMeters: Int, locationLabel: String?,
    permissionOk: Boolean, locationServicesOn: Boolean, capturing: Boolean, captureMsg: String?,
    onRequestPermission: () -> Unit, onUseCurrent: () -> Unit, onOpenLocationServices: () -> Unit,
    onManual: (Double, Double) -> Unit, onRadius: (Int) -> Unit, onClear: () -> Unit, onDone: () -> Unit
) {
    val sc = MaterialTheme.colorScheme
    var latText by remember { mutableStateOf(latitude?.let { "%.6f".format(it) } ?: "") }
    var lngText by remember { mutableStateOf(longitude?.let { "%.6f".format(it) } ?: "") }
    // Reflect a captured / changed location back into the text fields so it's always visible.
    LaunchedEffect(latitude, longitude) {
        if (latitude != null) latText = "%.6f".format(latitude)
        if (longitude != null) lngText = "%.6f".format(longitude)
    }
    val hasSel = latitude != null && longitude != null
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
        Text("Resume location", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("Work apps turn on when you reach this place during your schedule.", style = MaterialTheme.typography.bodySmall, color = sc.onSurfaceVariant)

        Spacer(Modifier.height(14.dp))
        Surface(color = sc.surfaceContainerHighest, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(if (hasSel) "SELECTED PLACE" else "NO PLACE SELECTED YET",
                    style = MaterialTheme.typography.labelSmall, color = sc.onSurfaceVariant, letterSpacing = 1.sp)
                if (hasSel) {
                    Spacer(Modifier.height(2.dp))
                    if (locationLabel != null) {
                        Text(locationLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("%.5f, %.5f".format(latitude ?: 0.0, longitude ?: 0.0),
                            style = MaterialTheme.typography.labelSmall, color = sc.onSurfaceVariant)
                    } else {
                        Text("%.5f, %.5f".format(latitude ?: 0.0, longitude ?: 0.0),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Text("Within $radiusMeters m", style = MaterialTheme.typography.bodySmall, color = sc.onSurfaceVariant)
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Button(
            onClick = { if (permissionOk) onUseCurrent() else onRequestPermission() },
            enabled = !capturing, modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            LocationGlyph(sc.onPrimary, Modifier.size(16.dp)); Spacer(Modifier.size(8.dp))
            Text(if (capturing) "Getting location…" else if (permissionOk) "Use my current location" else "Grant location & use current")
        }
        captureMsg?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = sc.onSurfaceVariant)
        }
        if (permissionOk && !locationServicesOn) {
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onOpenLocationServices) { Text("Turn on device Location") }
        }

        Spacer(Modifier.height(16.dp))
        Text("OR ENTER COORDINATES", style = MaterialTheme.typography.labelMedium, color = sc.onSurfaceVariant, letterSpacing = 1.sp)
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = latText, onValueChange = { latText = it; pushManual(latText, lngText, onManual) },
                label = { Text("Latitude") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = lngText, onValueChange = { lngText = it; pushManual(latText, lngText, onManual) },
                label = { Text("Longitude") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(16.dp))
        Text("RADIUS · $radiusMeters m", style = MaterialTheme.typography.labelMedium, color = sc.onSurfaceVariant, letterSpacing = 1.sp)
        Slider(
            value = radiusMeters.toFloat().coerceIn(50f, 1000f),
            onValueChange = { onRadius((it / 50f).toInt() * 50) },
            valueRange = 50f..1000f
        )

        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { latText = ""; lngText = ""; onClear() }, modifier = Modifier.weight(1f)) { Text("Clear") }
            Button(onClick = onDone, modifier = Modifier.weight(1f)) { Text("Done") }
        }
    }
}

private fun pushManual(latText: String, lngText: String, onManual: (Double, Double) -> Unit) {
    val lat = latText.trim().toDoubleOrNull()
    val lng = lngText.trim().toDoubleOrNull()
    if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) onManual(lat, lng)
}

// ---------------- Setup banner + sheet ----------------

@Composable
private fun SetupBanner(silent: Boolean, canExact: Boolean, batteryOk: Boolean, locationNeeded: Boolean, onClick: () -> Unit) {
    val sc = MaterialTheme.colorScheme
    val msg = when {
        !silent -> "Finish silent-mode setup for instant, screen-free toggles"
        !canExact -> "Allow exact timing so the schedule fires on time"
        !batteryOk -> "Exempt from battery optimization for reliable scheduling"
        locationNeeded -> "Grant location access for the resume-at-a-place feature"
        else -> ""
    }
    Card(modifier = Modifier.fillMaxWidth().clickable { onClick() }, colors = CardDefaults.cardColors(containerColor = sc.tertiaryContainer)) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, null, tint = sc.onTertiaryContainer, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(10.dp))
            Text(msg, style = MaterialTheme.typography.bodyMedium, color = sc.onTertiaryContainer, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.KeyboardArrowRight, null, tint = sc.onTertiaryContainer)
        }
    }
}

@Composable
private fun SetupSheet(
    profilePresent: Boolean,
    silent: Boolean, a11yOn: Boolean, canExact: Boolean, batteryOk: Boolean,
    notificationsOk: Boolean, onAllowNotifications: () -> Unit,
    manualEnabled: Boolean, manualStatus: String, manualEditable: Boolean, onManualChange: (Boolean) -> Unit,
    onOpenA11y: () -> Unit, onOpenExact: () -> Unit, onAllowBattery: () -> Unit,
    locationEnabled: Boolean, locationFgOk: Boolean, locationBgOk: Boolean, locationServicesOn: Boolean,
    onRequestLocation: () -> Unit, onAllowBackground: () -> Unit, onOpenLocationServices: () -> Unit,
    onRecheck: () -> Unit
) {
    val ctx = LocalContext.current
    var showCommand by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val command = "adb shell pm grant ${com.worksched.BuildConfig.APPLICATION_ID} android.permission.MODIFY_QUIET_MODE"
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Set up once. Let your schedule handle the rest.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SetupItem(
            title = "Work profile", ok = profilePresent,
            okText = "An existing work profile was found on this device.",
            notText = "No work profile found. Set one up before using automation.",
            stateLabel = if (profilePresent) "Detected" else "Not found"
        ) {}
        Text("Scheduling", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp))
        SetupItem(
            title = "Silent control", ok = silent,
            okText = "Work apps change state without opening Quick Settings.",
            notText = "Recommended: a one-time ADB grant from your computer. No root needed.",
            required = !a11yOn,
            stateLabel = if (silent) "Active" else "Set up"
        ) {
            if (!silent) {
                TextButton(onClick = { showCommand = !showCommand }) {
                    Text(if (showCommand) "Hide instructions" else "Set up with ADB")
                }
                if (showCommand) {
                    Text("1. Connect to a trusted computer and authorize USB debugging.\n2. Run this command from that computer, then tap Refresh status.",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(12.dp)) {
                        Text(command, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 20.sp,
                            modifier = Modifier.fillMaxWidth().padding(12.dp))
                    }
                    OutlinedButton(onClick = {
                        ctx.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("WorkPause ADB setup", command))
                        copied = true
                    }) {
                        Text(if (copied) "Command copied" else "Copy command")
                    }
                }
            }
        }
        SetupItem(
            title = "Exact alarms", ok = canExact,
            okText = "Android allows precise schedule alarms.",
            notText = "Allow precise alarms. Otherwise changes may be delayed.",
            stateLabel = if (canExact) "Allowed" else "Allow"
        ) {
            if (!canExact) TextButton(onClick = onOpenExact) { Text("Allow exact alarms") }
        }
        SetupItem(
            title = "Battery", ok = batteryOk, required = false,
            okText = "Battery optimization is disabled for WorkPause.",
            notText = "Recommended: allow unrestricted battery use to reduce delays.",
            stateLabel = if (batteryOk) "Unrestricted" else "Optimized"
        ) {
            if (!batteryOk) TextButton(onClick = onAllowBattery) { Text("Review battery settings") }
        }
        SetupItem(
            title = "Unlock reminders", ok = notificationsOk, required = false,
            okText = "Notifications are allowed. Review the Work profile unlock reminders channel to enable banners/pop-ups if desired. Tap the reminder for Android's PIN screen or a work-app picker; WorkPause never reads your PIN.",
            notText = "Notifications are blocked. Scheduling still works, but the work-profile unlock reminder cannot appear.",
            stateLabel = if (notificationsOk) "Allowed" else "Blocked"
        ) {
            TextButton(onClick = onAllowNotifications) {
                Text(if (notificationsOk) "Review reminder notifications" else "Allow reminder notifications")
            }
        }
        Text("Optional", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp))
        SetupItem(
            title = stringResource(R.string.manual_setting_title), ok = manualEnabled, required = false,
            okText = stringResource(R.string.manual_setting_description),
            notText = stringResource(R.string.manual_setting_description),
            stateLabel = if (manualEnabled) "Enabled" else "Off"
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Enable monitoring", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = manualEnabled, onCheckedChange = onManualChange, enabled = manualEditable,
                    modifier = Modifier.semantics { contentDescription = ctx.getString(R.string.manual_setting_title) })
            }
            if (manualEnabled) Text(manualStatus, style = MaterialTheme.typography.bodySmall)
        }
        SetupItem(
            title = "Visible fallback", ok = a11yOn, required = false,
            okText = if (silent) "Enabled, but silent control takes priority." else "Accessibility can operate the Work apps tile.",
            notText = if (silent) "Not needed while silent control is active." else "Only if you skip ADB. Opens Quick Settings visibly; confirmations stay manual.",
            stateLabel = if (a11yOn) "Enabled" else if (silent) "Not needed" else "Optional"
        ) {
            if (!silent) TextButton(onClick = onOpenA11y) { Text("Accessibility settings") }
        }
        if (locationEnabled) {
            SetupItem(
                title = "Location access", ok = locationFgOk && locationBgOk && locationServicesOn,
                okText = "Background permission and device location are available.",
                notText = "Needed only because Resume at a place is enabled.",
                stateLabel = if (locationFgOk && locationBgOk && locationServicesOn) "Allowed" else "Review"
            ) {
                when {
                    !locationFgOk -> TextButton(onClick = onRequestLocation) { Text("Grant location") }
                    !locationBgOk -> TextButton(onClick = onAllowBackground) { Text("Allow all the time") }
                    !locationServicesOn -> TextButton(onClick = onOpenLocationServices) { Text("Turn on Location") }
                }
            }
        }
        OutlinedButton(onClick = onRecheck, modifier = Modifier.fillMaxWidth()) { Text("Refresh status") }
    }
}

@Composable
private fun SetupItem(
    title: String, ok: Boolean, okText: String, notText: String,
    required: Boolean = true, stateLabel: String = if (ok) "Ready" else "Needs setup",
    extra: @Composable () -> Unit
) {
    val sc = MaterialTheme.colorScheme
    val statusColor = if (ok) sc.primary else if (required) sc.error else sc.onSurfaceVariant
    Surface(color = sc.surface, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.Info, contentDescription = null,
                    tint = statusColor, modifier = Modifier.size(20.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                Text(stateLabel, style = MaterialTheme.typography.labelSmall, color = statusColor)
            }
            Text(if (ok) okText else notText, style = MaterialTheme.typography.bodySmall, color = sc.onSurfaceVariant)
            extra()
        }
    }
}

@Composable
private fun HelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
        title = { Text("How it works") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                HelpPara("Daily behavior", "Select a weekday and choose Schedule, All day ON, or All day OFF. Schedule keeps work apps ON from the From time until the To time; an earlier To time means the next day. All day OFF takes priority at midnight over previous overnight hours.")
                HelpPara("WorkPause ON or OFF", "ON follows your saved weekly schedule and applies the current scheduled state. OFF stops WorkPause's automatic changes and leaves work apps as set on your phone. The switch applies immediately, without saving. It does not change your phone's own settings or employer policies.")
                HelpPara("Manual profile changes (optional)", "Enable Respect manual Work Profile changes in Settings. After each manual Android profile switch, WorkPause compares the new profile state with your saved schedule for the current time, even while scheduling is OFF. A mismatch turns scheduling OFF so WorkPause leaves your choice alone; a match turns scheduling ON again. For 09:00-18:00 hours: manual OFF at 13:00 pauses scheduling and ON at 15:00 restores it; manual ON at 20:00 pauses scheduling and OFF at 21:00 restores it. A time boundary or a schedule edit alone does not end a manual pause. Own scheduled changes do not count as manual switches. Monitoring stays active while the option is enabled; turn the option OFF to stop it. Force-stop interrupts observation.")
                HelpPara("Copy and save", "Copy to other days replaces the selected days' behavior and times. Save schedule applies daily edits only. The WorkPause switch uses the saved schedule, not unsaved edits. Saving while WorkPause is OFF does not turn it on.")
                HelpPara("Silent mode", "After a one-time ADB grant (MODIFY_QUIET_MODE), the app pauses/resumes your work profile via a direct system call — instant, no screen, no Quick Settings panel. No root.")
                HelpPara("Visible fallback", "Without the grant, it taps the Quick Settings ‘Work apps’ tile via an Accessibility service, which briefly shows the panel.")
                HelpPara("Resume and work PIN", "Notification setup is required: open Settings > Unlock reminders, allow notifications, and review the Work profile unlock reminders channel for banner/pop-up settings. After resume, tap the reminder: Android may ask for the work PIN directly, or choose a work app in the picker to trigger its challenge. If the phone is locked, WorkPause waits until you unlock it. Storage-unlocked status does not prove a separate work PIN is unlocked. Android, Samsung settings, and Do Not Disturb determine banner visibility. WorkPause never reads or bypasses your PIN.")
}
        }
    )
}

@Composable
private fun HelpPara(h: String, b: String) {
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        Text(h, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(b, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text(stringResource(R.string.app_name)) },
        text = {
            Column {
                Text("Version ${com.worksched.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text("Pauses and resumes your Android work profile on your weekly schedule. Choose custom hours, ON all day, or OFF all day for each weekday. No root. Open source on GitHub, Apache-2.0.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text("Based on Work Profile Scheduler. Copyright 2026 Ravindra. Licensed under Apache License 2.0.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    )
}

// ---------------- logic helpers ----------------

private fun heroSub(schedule: Schedule, quiet: Boolean?, pendingResume: Boolean): String {
    if (pendingResume) return "Resuming the next time you unlock"
    if (schedule.days.isEmpty()) return "Scheduling off · manual only"
    val next = nextRelevant(schedule, quiet) ?: return "Scheduling off · manual only"
    return "${if (next.second) "Resuming" else "Pausing"} ${whenPhrase(next.first)}"
}

/** Next state-changing event: if paused → next resume; if on → next pause; if unknown → soonest. */
private fun nextRelevant(s: Schedule, quiet: Boolean?): Pair<Long, Boolean>? {
    if (s.days.isEmpty()) return null
    if (quiet == null) {
        var best = Long.MAX_VALUE; var enable = true
        for (d in s.days) {
            val te = AlarmScheduler.nextOccurrenceMillis(d, s.enableMinutesOfDay()); if (te < best) { best = te; enable = true }
            val td = AlarmScheduler.nextOccurrenceMillis(d, s.disableMinutesOfDay()); if (td < best) { best = td; enable = false }
        }
        return best to enable
    }
    val wantEnable = quiet == true
    val minutes = if (wantEnable) s.enableMinutesOfDay() else s.disableMinutesOfDay()
    var best = Long.MAX_VALUE
    for (d in s.days) { val t = AlarmScheduler.nextOccurrenceMillis(d, minutes); if (t < best) best = t }
    return best to wantEnable
}

private fun whenPhrase(ms: Long): String {
    val time = SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date(ms))
    val now = Calendar.getInstance()
    val t = Calendar.getInstance().apply { timeInMillis = ms }
    return when (dayDiff(now, t)) {
        0 -> "at $time today"
        1 -> "at $time tomorrow"
        else -> "${SimpleDateFormat("EEE", Locale.ENGLISH).format(Date(ms))} at $time"
    }
}

private fun dayDiff(a: Calendar, b: Calendar): Int {
    fun midnight(c: Calendar) = (c.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    return ((midnight(b).timeInMillis - midnight(a).timeInMillis) / 86_400_000L).toInt()
}

private fun fmt12(hour: Int, minute: Int): String {
    val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0) }
    return SimpleDateFormat("h:mm a", Locale.ENGLISH).format(c.time)
}

private fun isA11yEnabled(ctx: Context): Boolean {
    val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val target = WorkProfileA11yService::class.java.name
    return am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC)
        .any {
            val service = it.resolveInfo?.serviceInfo
            service?.packageName == ctx.packageName && service.name == target
        }
}

private fun canScheduleExact(ctx: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    return am.canScheduleExactAlarms()
}

private fun isBatteryUnrestricted(ctx: Context): Boolean {
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
    return pm.isIgnoringBatteryOptimizations(ctx.packageName)
}

private fun requestIgnoreBattery(ctx: Context) {
    try {
        ctx.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Throwable) {
        ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

// ---------------- hand-drawn monochrome glyphs ----------------

@Composable
private fun SunGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val c = Offset(size.width / 2, size.height / 2); val core = size.minDimension * 0.26f
        drawCircle(tint, radius = core, center = c)
        val rayIn = size.minDimension * 0.36f; val rayOut = size.minDimension * 0.48f; val s = size.minDimension * 0.09f
        for (i in 0 until 8) {
            val a = Math.toRadians((i * 45).toDouble()); val dx = Math.cos(a).toFloat(); val dy = Math.sin(a).toFloat()
            drawLine(tint, Offset(c.x + dx * rayIn, c.y + dy * rayIn), Offset(c.x + dx * rayOut, c.y + dy * rayOut), strokeWidth = s, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun MoonGlyph(tint: Color, carveColor: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val r = size.minDimension * 0.42f; val c = Offset(size.width / 2, size.height / 2)
        drawCircle(tint, radius = r, center = c)
        drawCircle(carveColor, radius = r * 0.92f, center = Offset(c.x + r * 0.55f, c.y - r * 0.30f))
    }
}

@Composable
private fun PauseGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val barW = size.width * 0.26f; val gap = size.width * 0.20f
        val left = (size.width - (barW * 2 + gap)) / 2; val top = size.height * 0.12f; val barH = size.height * 0.76f
        val r = CornerRadius(barW * 0.4f, barW * 0.4f)
        drawRoundRect(tint, topLeft = Offset(left, top), size = Size(barW, barH), cornerRadius = r)
        drawRoundRect(tint, topLeft = Offset(left + barW + gap, top), size = Size(barW, barH), cornerRadius = r)
    }
}

@Composable
private fun LocationGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height * 0.40f
        val rOuter = size.minDimension * 0.30f
        val sw = size.minDimension * 0.10f
        drawCircle(tint, radius = rOuter, center = Offset(cx, cy), style = Stroke(width = sw))
        val path = Path().apply {
            moveTo(cx - rOuter * 0.72f, cy + rOuter * 0.62f)
            lineTo(cx, size.height * 0.92f)
            lineTo(cx + rOuter * 0.72f, cy + rOuter * 0.62f)
        }
        drawPath(path, tint, style = Stroke(width = sw, cap = StrokeCap.Round))
        drawCircle(tint, radius = size.minDimension * 0.08f, center = Offset(cx, cy))
    }
}
