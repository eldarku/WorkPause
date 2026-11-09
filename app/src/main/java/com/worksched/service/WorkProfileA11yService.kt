package com.worksched.service

// Modified for WorkPause: limit gestures to System UI and leave confirmations to the user.
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.worksched.profile.ManualSessions
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * FALLBACK backend. Used only when the silent MODIFY_QUIET_MODE permission has
 * not been granted. Drives the Quick Settings "Work apps" tile by expanding the
 * panel and dispatching a synthetic touch gesture at the tile (Samsung tiles
 * reject ACTION_CLICK but accept real touch coordinates).
 */
class WorkProfileA11yService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "service connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        scope.cancel()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* no-op */ }
    override fun onInterrupt() {}

    fun cancelPendingToggle() {
        main.removeCallbacksAndMessages(null)
        Log.i(TAG, "Pending tile actions cancelled because WorkPause is off")
    }

    fun executeToggle(desired: Boolean) {
        Log.i(TAG, "executeToggle(desired=$desired)")
        val opened = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        Log.i(TAG, "GLOBAL_ACTION_QUICK_SETTINGS result=$opened")
        main.postDelayed({ attemptTileClick(desired, attempt = 0, reExpanded = false) }, 600)
    }

    private fun attemptTileClick(desired: Boolean, attempt: Int, reExpanded: Boolean) {
        scope.launch {
            try {
                if (!ManualSessions.allows(this@WorkProfileA11yService, desired)) {
                    cancelPendingToggle()
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    return@launch
                }
                attemptAuthorizedTileClick(desired, attempt, reExpanded)
            } catch (e: IOException) {
                Log.e(TAG, "Cannot authorize delayed work-profile action", e)
                cancelPendingToggle()
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        }
    }

    private fun attemptAuthorizedTileClick(desired: Boolean, attempt: Int, reExpanded: Boolean) {
        val root = rootInActiveWindow
        val tile = root?.takeIf { it.packageName?.toString() == SYSTEM_UI_PACKAGE }?.let { findWorkTile(it) }
        if (tile == null) {
            val rootPkg = root?.packageName?.toString() ?: "null"
            Log.d(TAG, "tile not yet found, retry #$attempt (rootPkg=$rootPkg)")
            if (attempt < 8) {
                main.postDelayed({ attemptTileClick(desired, attempt + 1, reExpanded) }, 250)
            } else if (!reExpanded) {
                Log.w(TAG, "tile missing after 8 retries; re-expanding QS panel")
                performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
                main.postDelayed({ attemptTileClick(desired, attempt = 0, reExpanded = true) }, 700)
            } else {
                Log.w(TAG, "tile not found after re-expand; giving up")
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
            return
        }
        val before = tile.isChecked
        Log.i(TAG, "tile found (desc='${tile.contentDescription}', checked=$before); desired=$desired")
        if (before != desired) {
            val ok = tile.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.i(TAG, "tile click result=$ok")
            if (!ok) tapTileViaGesture(tile)
        } else {
            Log.i(TAG, "tile already in desired state; no click needed")
            main.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 1400)
        }
    }

    private fun tapTileViaGesture(tile: AccessibilityNodeInfo) {
        val rect = Rect()
        tile.getBoundsInScreen(rect)
        if (rect.isEmpty) {
            Log.w(TAG, "tile bounds empty; cannot gesture-tap")
            return
        }
        val cx = rect.exactCenterX()
        val cy = rect.exactCenterY()
        Log.i(TAG, "fallback gesture tap at ($cx, $cy)")
        val path = Path().apply { moveTo(cx, cy) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 80L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { Log.i(TAG, "gesture completed") }
            override fun onCancelled(d: GestureDescription?) { Log.w(TAG, "gesture cancelled") }
        }, null)
        Log.i(TAG, "dispatchGesture queued=$dispatched")
    }

    private fun findWorkTile(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findFirst(root) { n ->
            val desc = (n.contentDescription?.toString() ?: "").trim()
            val cls = n.className?.toString().orEmpty()
            desc.isNotBlank() &&
                TILE_LABELS.any { it.equals(desc, ignoreCase = true) } &&
                cls.endsWith("Button") && n.isCheckable && n.isClickable
        }

    private fun findFirst(
        node: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val hit = findFirst(node.getChild(i), predicate)
            if (hit != null) return hit
        }
        return null
    }

    companion object {
        private const val TAG = "WorkProfileA11y"
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"

        @Volatile var instance: WorkProfileA11yService? = null

        private val TILE_LABELS = listOf(
            "Work apps", "Pause apps", "Pause work apps", "Work profile", "Work mode"
        )
        fun requestToggle(@Suppress("UNUSED_PARAMETER") context: Context, enable: Boolean): Boolean {
            val svc = instance
            if (svc == null) {
                Log.w(TAG, "requestToggle: service instance null — accessibility not enabled")
                return false
            }
            svc.executeToggle(enable)
            return true
        }
    }
}
