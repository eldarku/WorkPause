package com.worksched.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.worksched.data.DaySchedule
import com.worksched.data.Schedule
import java.time.Instant
import java.time.ZoneId
import java.util.Calendar

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
    ) = ManualSessionPolicy.observe(state, enabled, profile, quiet, scheduled, at).state

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

    @Test fun `disabled synchronization clears state and disabled master rejects actions`() {
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

    @Test fun `external ON outside hours disables scheduling and records a manual pause`() {
        val decision = ManualSessionPolicy.observe(paused, true, 10L, false, false, now)
        assertEquals(false, decision.schedulingEnabled)
        assertTrue(decision.state.active)
    }

    @Test fun `external ON during work hours enables scheduling without an override`() {
        val decision = ManualSessionPolicy.observe(paused, true, 10L, false, true, now)
        assertEquals(true, decision.schedulingEnabled)
        assertFalse(decision.state.active)
    }

    @Test fun `external OFF during work hours disables scheduling`() {
        val decision = ManualSessionPolicy.observe(paused.copy(quiet = false), true, 10L, true, true, now)
        assertEquals(false, decision.schedulingEnabled)
        assertTrue(decision.state.active)
    }

    @Test fun `ending after hours session inside changed work hours disables scheduling`() {
        val active = observe()
        val decision = ManualSessionPolicy.observe(active, true, 10L, true, true, now)
        assertEquals(false, decision.schedulingEnabled)
        assertTrue(decision.state.active)
    }

    @Test fun `manual OFF followed by ON can reenable scheduling`() {
        val off = ManualSessionPolicy.observe(paused.copy(quiet = false), true, 10L, true, true, now)
        val on = ManualSessionPolicy.observe(off.state, true, 10L, false, true, now + 1)
        assertEquals(false, off.schedulingEnabled)
        assertEquals(true, on.schedulingEnabled)
    }

    @Test fun `scheduled OFF does not disable scheduling`() {
        val request = ManualSessionPolicy.requesting(paused.copy(quiet = false), false, now)
        val decision = ManualSessionPolicy.observe(request, true, 10L, true, false, now + 1)
        assertNull(decision.schedulingEnabled)
    }

    @Test fun `scheduled ON does not mirror a manual switch`() {
        val request = ManualSessionPolicy.requesting(paused, true, now)
        assertNull(ManualSessionPolicy.observe(request, true, 10L, false, true, now + 1).schedulingEnabled)
    }

    @Test fun `external OFF clears conflicting pending resume correlation`() {
        val request = paused.copy(quiet = false, expectedQuiet = false, expectedUntil = now + 1000)
        val decision = ManualSessionPolicy.observe(request, true, 10L, true, true, now)
        assertEquals(false, decision.schedulingEnabled)
        assertNull(decision.state.expectedQuiet)
    }

    @Test fun `initial baseline duplicate unlock and unknown events do not mirror`() {
        assertNull(ManualSessionPolicy.observe(ManualSessionState(), true, 10L, false, true, now).schedulingEnabled)
        assertNull(ManualSessionPolicy.observe(paused, true, 10L, true, true, now).schedulingEnabled)
        assertNull(ManualSessionPolicy.observe(paused, true, 10L, null, true, now).schedulingEnabled)
    }

    @Test fun `disabled synchronization and replaced or removed profiles do not mirror`() {
        assertNull(ManualSessionPolicy.observe(paused, false, 10L, false, false, now).schedulingEnabled)
        assertNull(ManualSessionPolicy.observe(paused, true, 11L, false, false, now).schedulingEnabled)
        assertNull(ManualSessionPolicy.observe(paused, true, null, null, false, now).schedulingEnabled)
    }

    @Test fun `manual OFF outside hours restores scheduling after an evening manual ON`() {
        val on = ManualSessionPolicy.observe(paused, true, 10L, false, false, now)
        val off = ManualSessionPolicy.observe(on.state, true, 10L, true, false, now + 1)
        assertEquals(false, on.schedulingEnabled)
        assertEquals(true, off.schedulingEnabled)
        assertFalse(off.state.active)
    }

    @Test fun `manual ON during hours clears a daytime manual OFF pause`() {
        val off = ManualSessionPolicy.observe(paused.copy(quiet = false), true, 10L, true, true, now)
        val on = ManualSessionPolicy.observe(off.state, true, 10L, false, true, now + 1)
        assertTrue(off.state.active)
        assertEquals(true, on.schedulingEnabled)
        assertFalse(on.state.active)
    }

    @Test fun `returning after a boundary uses the current schedule not the original pause direction`() {
        val afternoonOff = ManualSessionPolicy.observe(paused.copy(quiet = false), true, 10L, true, true, now)
        val eveningOn = ManualSessionPolicy.observe(afternoonOff.state, true, 10L, false, false, now + 1)
        val eveningOff = ManualSessionPolicy.observe(eveningOn.state, true, 10L, true, false, now + 2)
        assertEquals(false, eveningOn.schedulingEnabled)
        assertTrue(eveningOn.state.active)
        assertEquals(true, eveningOff.schedulingEnabled)
    }

    @Test fun `time passing or a schedule edit does not alone reenable a manual pause`() {
        val pausedByDaytimeOff = ManualSessionPolicy.observe(paused.copy(quiet = false), true, 10L, true, true, now)
        val later = ManualSessionPolicy.observe(pausedByDaytimeOff.state, true, 10L, true, false, now + 1)
        assertNull(later.schedulingEnabled)
        assertTrue(later.state.active)
    }

    @Test fun `both own scheduled directions leave scheduling enabled across a work boundary`() {
        val ownOff = ManualSessionPolicy.requesting(paused.copy(quiet = false), false, now)
        val off = ManualSessionPolicy.observe(ownOff, true, 10L, true, false, now + 1)
        val ownOn = ManualSessionPolicy.requesting(off.state, true, now + 2)
        val on = ManualSessionPolicy.observe(ownOn, true, 10L, false, true, now + 3)
        assertNull(off.schedulingEnabled)
        assertNull(on.schedulingEnabled)
        assertFalse(off.state.active)
        assertFalse(on.state.active)
    }

    @Test fun `nine to eighteen exact scenarios are evaluated even with the master disabled`() {
        val zone = ZoneId.of("UTC")
        val schedule = Schedule.default().copy(enabled = false)
            .withDay(Calendar.MONDAY, DaySchedule(resumeMinutes = 9 * 60, pauseMinutes = 18 * 60))
        var state = paused.copy(quiet = false)
        val cases = listOf(
            Triple("13:00", true, false),
            Triple("15:00", false, true),
            Triple("20:00", false, false),
            Triple("21:00", true, true)
        )
        cases.forEach { (time, quiet, expected) ->
            val at = Instant.parse("2026-11-09T$time:00Z").toEpochMilli()
            if (time == "20:00") {
                val end = Instant.parse("2026-11-09T18:00:00Z").toEpochMilli()
                val ownPause = ManualSessionPolicy.requesting(state, false, end)
                val scheduledPause = ManualSessionPolicy.observe(ownPause, true, 10L, true, false, end + 1)
                assertNull(scheduledPause.schedulingEnabled)
                state = scheduledPause.state
            }
            val decision = ManualSessionPolicy.observe(state, true, 10L, quiet,
                schedule.copy(enabled = true).isActiveAt(at, zone), at)
            assertEquals("manual profile switch at $time", expected, decision.schedulingEnabled)
            assertEquals(!expected, decision.state.active)
            state = decision.state
        }
    }

    @Test fun `start boundary is ON and end boundary is OFF for manual comparison`() {
        val zone = ZoneId.of("UTC")
        val schedule = Schedule.default()
            .withDay(Calendar.MONDAY, DaySchedule(resumeMinutes = 9 * 60, pauseMinutes = 18 * 60))
        listOf("08:59" to false, "09:00" to true, "17:59" to true, "18:00" to false).forEach { (time, active) ->
            val at = Instant.parse("2026-11-09T$time:00Z").toEpochMilli()
            val actualActive = schedule.isActiveAt(at, zone)
            assertEquals(active, actualActive)
            val on = ManualSessionPolicy.observe(paused, true, 10L, false, actualActive, at)
            val off = ManualSessionPolicy.observe(paused.copy(quiet = false), true, 10L, true, actualActive, at)
            assertEquals(active, on.schedulingEnabled)
            assertEquals(!active, off.schedulingEnabled)
        }
    }

    @Test fun `manual agreement respects overnight and all day rules`() {
        val zone = ZoneId.of("UTC")
        val schedule = Schedule.default()
            .withDay(Calendar.MONDAY, DaySchedule(resumeMinutes = 22 * 60, pauseMinutes = 6 * 60))
        val overnight = Instant.parse("2026-11-10T02:00:00Z").toEpochMilli()
        val on = ManualSessionPolicy.observe(paused, true, 10L, false, schedule.isActiveAt(overnight, zone), overnight)
        val off = ManualSessionPolicy.observe(paused.copy(quiet = false), true, 10L, true,
            schedule.isActiveAt(overnight, zone), overnight)
        assertEquals(true, on.schedulingEnabled)
        assertEquals(false, off.schedulingEnabled)
        listOf(com.worksched.data.DayMode.ALL_ON to true, com.worksched.data.DayMode.ALL_OFF to false).forEach { (mode, active) ->
            val allDay = schedule.withDay(Calendar.TUESDAY, DaySchedule(mode = mode))
            val scheduled = allDay.copy(enabled = true).isActiveAt(overnight, zone)
            assertEquals(active, scheduled)
            assertEquals(active, ManualSessionPolicy.observe(paused, true, 10L, false, scheduled, overnight).schedulingEnabled)
        }
    }
}
