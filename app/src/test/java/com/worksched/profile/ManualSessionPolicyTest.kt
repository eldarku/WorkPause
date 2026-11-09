package com.worksched.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualSessionPolicyTest {
    private val now = 100_000L
    private val paused = ManualSessionState(profileSerial = 10L, quiet = true)

    private fun observe(
        state: ManualSessionState = paused,
        enabled: Boolean = true,
        profile: Long? = 10L,
        quiet: Boolean? = false,
        scheduled: Boolean = false,
        at: Long = now
    ) = ManualSessionPolicy.observe(state, enabled, profile, quiet, scheduled, at)

    @Test fun `external resume outside work hours starts a session`() {
        assertTrue(observe().active)
    }

    @Test fun `feature disabled does not override the schedule`() {
        assertEquals(ManualSessionState(), observe(enabled = false))
        assertTrue(ManualSessionPolicy.allows(true, ManualSessionSettings(), false, false))
    }

    @Test fun `resume during work hours is not a manual session`() {
        assertFalse(observe(scheduled = true).active)
    }

    @Test fun `initial running profile is only a baseline`() {
        assertFalse(observe(state = ManualSessionState()).active)
    }

    @Test fun `own scheduled resume is not manual even across a boundary`() {
        val requested = ManualSessionPolicy.requesting(paused, true, now)
        val state = observe(state = requested, at = now + 1)
        assertFalse(state.active)
        assertNull(state.expectedQuiet)
    }

    @Test fun `failed resume cannot mask a later manual resume`() {
        val requested = ManualSessionPolicy.requesting(paused, true, now)
        assertTrue(observe(state = ManualSessionPolicy.requestFailed(requested)).active)
    }

    @Test fun `expired request cannot swallow a manual resume`() {
        val requested = ManualSessionPolicy.requesting(paused, true, now)
        assertTrue(observe(state = requested, at = now + ManualSessionPolicy.OWN_REQUEST_WINDOW_MS).active)
    }

    @Test fun `no op requests do not create an expected change`() {
        assertEquals(paused, ManualSessionPolicy.requesting(paused, false, now))
    }

    @Test fun `manual pause ends a session`() {
        assertFalse(observe(state = observe(), quiet = true).active)
    }

    @Test fun `duplicate resume and unlock observations preserve a session`() {
        val active = observe()
        assertEquals(active, observe(state = active))
    }

    @Test fun `session persists across scheduled boundaries`() {
        assertTrue(observe(state = observe(), scheduled = true).active)
    }

    @Test fun `unknown quiet state does not end a session`() {
        val active = observe()
        assertEquals(active, observe(state = active, quiet = null))
    }

    @Test fun `removed profile clears the session`() {
        assertEquals(ManualSessionState(), observe(state = observe(), profile = null, quiet = null))
    }

    @Test fun `replacement profile starts with a new baseline`() {
        assertFalse(observe(state = observe(), profile = 11L).active)
    }

    @Test fun `disabled master clears sessions and rejects actions`() {
        assertEquals(ManualSessionState(), observe(state = observe(), enabled = false))
        assertFalse(ManualSessionPolicy.allows(false, ManualSessionSettings(), true, true))
    }

    @Test fun `manual session rejects both automatic directions`() {
        val manual = ManualSessionSettings(true, observe())
        assertFalse(ManualSessionPolicy.allows(true, manual, false, false))
        assertFalse(ManualSessionPolicy.allows(true, manual, true, true))
    }

    @Test fun `changed schedule rejects stale actions`() {
        assertFalse(ManualSessionPolicy.allows(true, ManualSessionSettings(), true, false))
        assertFalse(ManualSessionPolicy.allows(true, ManualSessionSettings(), false, true))
    }

    @Test fun `persisted paused baseline recovers a missed external resume`() {
        assertTrue(observe(state = paused.copy()).active)
    }

    @Test fun `clock rollback cannot extend an own request indefinitely`() {
        val requested = ManualSessionPolicy.requesting(paused, true, now)
        assertTrue(observe(state = requested, at = now - 1).active)
    }

    @Test fun `unknown profile state blocks opted in automation`() {
        val unknown = ManualSessionSettings(true, paused.copy(quiet = null))
        assertFalse(ManualSessionPolicy.allows(true, unknown, false, false))
    }

    @Test fun `unverified initial state cannot authorize a toggle`() {
        assertFalse(ManualSessionPolicy.allows(true, ManualSessionSettings(enabled = true), false, false))
    }

    @Test fun `own pause cannot start a session`() {
        val running = paused.copy(quiet = false)
        val requested = ManualSessionPolicy.requesting(running, false, now)
        assertFalse(observe(state = requested, quiet = true, at = now + 1).active)
    }

    @Test fun `duplicate paused observation preserves a pending resume`() {
        val requested = ManualSessionPolicy.requesting(paused, true, now)
        assertEquals(requested, observe(state = requested, quiet = true, at = now + 1))
    }
}
