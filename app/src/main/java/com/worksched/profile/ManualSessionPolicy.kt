package com.worksched.profile

data class ManualSessionState(
    val profileSerial: Long? = null,
    val quiet: Boolean? = null,
    val active: Boolean = false,
    val expectedQuiet: Boolean? = null,
    val expectedUntil: Long = 0L
)

data class ManualSessionSettings(
    val enabled: Boolean = false,
    val state: ManualSessionState = ManualSessionState()
)

object ManualSessionPolicy {
    const val OWN_REQUEST_WINDOW_MS = 60_000L

    fun observe(
        previous: ManualSessionState,
        enabled: Boolean,
        profileSerial: Long?,
        quiet: Boolean?,
        scheduledActive: Boolean,
        now: Long
    ): ManualSessionState {
        if (!enabled) return ManualSessionState()
        if (profileSerial == null) return ManualSessionState()
        if (profileSerial != previous.profileSerial) return ManualSessionState(profileSerial, quiet)
        if (quiet == null) return previous
        val requestPending = previous.expectedUntil > now &&
            previous.expectedUntil - now <= OWN_REQUEST_WINDOW_MS
        val ownChange = previous.expectedQuiet == quiet && requestPending
        val manualResume = previous.quiet == true && !quiet && !scheduledActive && !ownChange
        val keepRequest = !ownChange && requestPending
        return previous.copy(
            quiet = quiet,
            active = !quiet && (previous.active || manualResume),
            expectedQuiet = if (keepRequest) previous.expectedQuiet else null,
            expectedUntil = if (keepRequest) previous.expectedUntil else 0L
        )
    }

    fun requesting(state: ManualSessionState, enable: Boolean, now: Long): ManualSessionState =
        if (state.quiet == !enable) state else state.copy(
            expectedQuiet = !enable,
            expectedUntil = now + OWN_REQUEST_WINDOW_MS
        )

    fun requestFailed(state: ManualSessionState): ManualSessionState =
        state.copy(expectedQuiet = null, expectedUntil = 0L)

    fun allows(enabled: Boolean, manual: ManualSessionSettings, scheduledActive: Boolean, enable: Boolean): Boolean =
        enabled && !(manual.enabled && (manual.state.active || manual.state.quiet == null)) &&
            scheduledActive == enable
}
