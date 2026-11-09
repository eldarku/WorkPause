<!-- Modified for WorkPause: replace inherited claims with this fork's actual verification status. -->
# WorkPause verification

## Schedule-aware manual control (local WorkPause 1.0.4, build 9)

The opt-in **Respect manual Work Profile changes** compares each external manual
profile transition with the saved schedule for the current local time:
disagreement turns scheduling OFF, agreement turns it ON. The schedule is
evaluated independently of the master enabled flag. Own scheduled requests,
unlock-only events, and initial profile baselines do not change the master switch.
Observation remains active while the option is enabled, including scheduling OFF.

Regression coverage includes the exact 09:00-18:00 examples: manual OFF at 13:00
disables scheduling, ON at 15:00 restores it, ON at 20:00 disables scheduling, and
OFF at 21:00 restores it. Start/end boundaries, overnight/all-day rules, a manual
return after a boundary or saved-rule edit, own request correlation, stale/failed
requests, duplicate events, missing state, and profile replacement are covered.
A manual pause is not automatically released by time passing or a saved-rule edit.

The local artifact keeps the WorkPause name/package, displayed version **1.0.4**,
internal version code **9**, and the independent local signing certificate.
The increased internal code allows an in-place update with the same key without
losing settings. The schedule-aware implementation is unchanged.

### Validation results

- **84 unit tests passed:** 41 manual-control policy tests, 24 weekly-schedule,
  7 location, and 12 unlock-reminder tests.
- Debug lint passed with 12 warnings and 1 informational finding.
- The signed, optimized release build passed. APK inspection verified version
  1.0.4 / code 9, the original package, plain WorkPause name, non-debuggable state,
  and APK signature.
- The version-only build 9 was installed in place on the S23. Its About screen
  displays **Version 1.0.4**. Saved 09:00-18:00 hours, scheduling ON, the paused
  evening profile, and foreground observation were retained without uninstalling.

### Samsung Galaxy S23 acceptance

The schedule-aware signed implementation was installed fresh on the connected Galaxy S23 running
Android 17 / API 37. Silent control, notifications, exact alarms, and the battery
exemption were configured; the optional manual-control setting was enabled.
Tests used the real Android **Work apps** Quick Settings tile, with WorkPause's
activity in the background during each profile toggle. No broadcasts were forged
and no separate test-controller application was installed on the phone.

The actual evening clock was left unchanged. Two saved work windows exercised
both schedule states on the device:

- With **09:00-18:00**, manual Android ON set scheduling OFF while the profile
  remained ON. Manual Android OFF restored scheduling ON while the profile
  correctly stayed OFF.
- With **09:00-23:00**, manual Android OFF set scheduling OFF and left the profile
  paused without a stale unlock reminder. Manual Android ON restored scheduling
  ON. Manual pauses remained stable through 10-second dwell checks.
- An unsaved 23:00 end-time edit did not affect comparison with the saved
  18:00 rule, and external transitions did not overwrite that unfinished edit.
- Saving an active rule resumed the profile through WorkPause without disabling
  scheduling. Restoring the OFF rule paused the profile with scheduling still ON.
  An ordinary scheduled resume briefly showed its existing conditional unlock
  reminder while Android settled, then reached the normal ON status; this was not
  a manual-OFF override failure.
- Editing the saved window during a manual pause did not release it. Explicitly
  reopening after force-stop restored the persisted pause and observation.
  Another manual switch that still disagreed with the edited window kept
  scheduling OFF; a later switch into agreement restored it.
- The final saved configuration is **Monday-Friday 09:00-18:00**, Saturday/Sunday
  **All day OFF**, scheduling **ON**, and the work profile correctly **OFF** in
  the evening. Foreground monitoring remained active, including during pauses.

The exact 13:00, 15:00, 20:00, and 21:00 timestamps are covered by policy tests;
the real phone was not time-shifted. No work PIN was read or entered by the test.
These results do not certify every employer policy, real location arrival,
future timed-alarm delivery, OEM configuration, or battery-consumption percentage.
Force-stop and explicit service stopping still interrupt background observation.

## Historical validation records

The following records describe earlier local artifacts, not the current
1.0.4 / build 9 behavior or download.

### Android Work Profile synchronization (local build 7)

The opt-in synchronization maps external Android profile ON to scheduling ON and
external OFF to scheduling OFF, during and outside configured work hours.
Off-hours manual ON remains protected until manual OFF. Own scheduled requests
are correlated so they do not disable the scheduler. Observation stays active
while scheduling is OFF; disabling the opt-in stops the monitor.

Policy tests cover both mirror directions, OFF followed by ON, an after-hours
session ending inside an edited work window, stale requests, duplicate/unlock
observations, unknown state, and profile removal/replacement. Persistence writes
the switch and observation in one DataStore transaction. UI observation updates
the live switch and pending/unlock state without replacing unsaved daily edits.

The local APK uses the original WorkPause name/package, version **1.0.5**, code
**7**, and the existing independent local signing key. It updates the locally
signed 1.0.4 installation without uninstalling or losing configuration.

### Validation results

- **76 unit tests passed:** 33 synchronization-policy, 24 weekly-schedule,
  7 location, and 12 unlock-reminder tests.
- Debug lint passed with 12 warnings and 1 informational finding. The signed,
  optimized, non-debuggable release build passed.
- APK inspection confirmed version 1.0.5 / code 7, the original package ID and
  WorkPause label, and the same independent RSA-4096 signing certificate.

### Samsung Galaxy S23 acceptance

The optimized signed APK was updated in place on a connected Galaxy S23 running
Android 17 / API 37. The existing configuration, grants, and enabled opt-in were
retained. Tests used the phone's real Android **Work apps** Quick Settings tile,
not forged broadcasts or a separate controller app.

- Applying an OFF schedule paused the profile while scheduling remained ON,
  verifying that an own request does not mirror OFF to the scheduler.
- With scheduling OFF, foreground observation remained active.
- Android ON outside the work window enabled scheduling and retained the manual
  ON state without an immediate scheduled pause.
- Restoring the saved active work window retained the manual ON protection.
- Android OFF with WorkPause in the background disabled scheduling and paused
  the profile. Both remained OFF through a 15-second dwell; the UI displayed
  neither a pending unlock request nor a work-PIN reminder.
- Android ON with WorkPause in the background enabled scheduling again and
  resumed normal in-hours scheduling status.
- The original saved daily rule and times were restored. The profile was left
  ON with scheduling ON, matching the final Android ON action.
- App-only logs confirmed the mirrored ON, OFF, ON decisions. The foreground
  service remained active with `specialUse` type `0x40000000`.

No work PIN was read or entered by the test. These checks do not certify every
Samsung configuration, employer policy, real location arrival, future timed alarm
delivery, or battery-consumption percentage. Force-stop and explicit service
stopping still interrupt observation.

### WorkPause local build 6 verification record

That local APK uses the original application ID
`io.github.marinfrankovic.workpause`, the **WorkPause** launcher label, version
**1.0.4**, and version code **6**. It is independently signed, not an official
author release. Installing it over the author's differently signed APK requires
uninstalling that APK first and repeating setup.

The feature is off by default. It temporarily suspends automation after an
external work-profile resume outside the current schedule and restores the
schedule after the user pauses the profile. It does not change the saved master
switch or add a daytime manual-OFF override.

The monitor is a non-exported, event-driven foreground service with a silent,
low-importance notification channel. It has no polling loop or wake lock.
Android 14+ uses the declared `specialUse` type and subtype explanation. Private
session state uses DataStore and the public user-serial API rather than hidden
UserHandle APIs. Own requests are tracked before dispatch and bounded, so failed,
expired, duplicate, or unrelated transitions are not treated as manual sessions.

Local unit validation passes **66 tests**, including **23 new manual-session
policy tests**, the existing 24 schedule tests, 7 location tests, and 12
unlock-reminder tests. Debug lint passes with 12 warnings and 1 informational
finding; the new monitor/coordinator/policy files have no lint findings. The
signed, R8-optimized build passes.

### Isolated runtime acceptance

An Android 15 / API 35 emulator used a disposable managed profile. A separate
test-only controller requested real quiet-mode transitions through the public
Android API; no profile broadcasts were forged. The controller is not part of
the APK or repository.

These functional checks used the original-package debug build and an earlier
separately packaged candidate. Repackaging as local 1.0.4 changes the version and
distribution identity, not the manual-session implementation.

- An external after-hours ON while WorkPause's activity was closed started a
  manual session in the background. The profile remained ON through subsequent
  reconciliation, and reopening the app showed the temporary-pause status.
- External OFF ended the session and restored normal scheduling automatically.
- Master OFF preserved the running profile, stopped observation, and remained
  OFF through subsequent external OFF/ON changes. Turning the master back ON
  applied the saved OFF rule without classifying that own action as manual.
- Turning the opt-in setting OFF during a session reapplied the saved OFF rule
  and stopped the monitoring service.
- Force-stop followed by explicit reopening restored a persisted running manual
  session without pausing the profile. A profile paused while WorkPause was
  stopped ended the persisted session on reopening.
- Changing the saved rule from All day OFF to All day ON retained an existing
  manual session. Ending that session applied the new ON rule; returning to an
  OFF rule did not create an accidental manual session.
- Removing the managed profile cleared the session and displayed the existing
  no-profile status without changing the saved master switch.
- The independently installed, non-debuggable candidate also passed the real
  external ON/OFF workflow. Its monitor was confirmed foreground with
  `specialUse` type `0x40000000`, a silent channel, and an ongoing notification.
- No WorkPause crash was observed. An unrelated Google Play services work-user
  storage-unlock crash occurred in the disposable image and is not a WorkPause
  compatibility result.

An Android 10 / API 29 emulator installed and launched the same optimized candidate.
The opt-in monitor successfully entered foreground using the pre-14 API branch,
remained active with the activity closed, and stopped when the master was OFF.
This stock image does **not** make `MODIFY_QUIET_MODE` ADB-grantable; Android
explicitly rejected the grant as a non-changeable permission. No platform
permission workaround was used. Full Android 10 quiet-mode automation was
therefore **not** verified; the existing visible fallback remains the alternative.

These results do not verify Samsung notification behavior, actual future alarm
delivery, battery percentages, every employer policy, real locked/PIN sessions,
or real-device location arrivals. Force-stop and explicit service stopping can
interrupt monitoring, and missed event history cannot always be reconstructed.

### Artifact inspection

The APK signature verifies with APK Signature Scheme v2 and an independently
generated RSA-4096 key. The local APK has minimum API 29, target API 35, the
**WorkPause** launcher label, version **1.0.4** / code **6**, and no debuggable flag. Its monitor is
non-exported. Its certificate SHA-256 is:

```text
c747bd5b5c5baf559aaba6adaf69b6c5eb95d038fb166071ab48e19918187c8f
```

Use the local release's attached `SHA256SUMS.txt` for the APK file checksum.

The local APK uses the original package ID but an independent signing key.
Uninstalling the author's APK clears WorkPause's settings and permissions; repeat
setup after installing the local build. Switching back to an author-signed APK
also requires uninstalling the local APK. No personal phone was modified during
verification.

## WorkPause 1.0.3 Preview: unlock reminders

The release uses version name **1.0.3**, version code **5**, and the existing
release-signing identity. The GitHub release title retains **Preview** and is marked
Latest for visibility. It updates over 1.0.2 and the local 1.0.3-preview build without
uninstalling or clearing app data.

The new reminder policy tests cover disabled schedules, ended work hours, removed
profiles, absence of a resume request, waiting for the phone to unlock, resumed but
credential-storage-locked profiles, still-paused profiles, unknown state, and the
distinction between unlocked storage and a separate work-app credential challenge.
An already running, storage-unlocked profile does not create a new reminder.

Notifications use a separate high-importance channel, an immutable explicit activity
PendingIntent, and a non-exported work-app picker. No hidden credential API, PIN
collection, full-screen intent, or lock-screen wake is added. Public `LauncherApps`
launches the user-selected work app so Android can enforce its work challenge.
`UserManager.isUserUnlocked(profile)` reports credential-storage state, not every
possible separate challenge state; notification wording reflects that limitation.
On Android 11+, the credential-not-required flag is used for all scheduled resume
attempts, including while the phone is unlocked. A foreground, user-initiated action
uses the prompting overload. This avoids recurring system PIN popups on automatic
retries after cancellation. Android 10 retains its existing older API behavior.

Android sends `MANAGED_PROFILE_UNLOCKED` only to runtime-registered receivers. The
application registers a non-exported receiver for the lifetime of its process;
no background service is added. If Android stops that process before a work-app
unlock outside WorkPause, immediate reminder cleanup cannot be guaranteed.

### Local validation

The managed-profile runtime checks below were performed on the signed
**1.0.3-preview (code 4)** candidate. The **1.0.3 (code 5)** release retains that
implementation, updates the version and setup/help wording, and has its own build,
test, lint, package, and signature checks. They are not Samsung functional tests.

- **43 unit tests passed:** 24 weekly-schedule, 7 location, and 12 reminder-policy tests.
- Debug lint passed with 12 warnings and 1 informational finding; the new profile
  reminder classes have no lint findings. The signed, optimized release build passed.
- An isolated Android 15 / API 35 emulator used a disposable managed profile and
  work PIN. Updating over 1.0.2 retained saved app state and the silent-control grant.
- Saving today's OFF/ON rules exercised real schedule reconciliation: an actionable,
  high-importance reminder appeared without an automatic credential popup.
- Tapping the reminder opened Android Settings' work-PIN UI. Canceling returned to
  the work-app picker, retained the reminder, and did not immediately prompt again.
  Explicit retry reopened Android's credential UI; successful PIN entry cleared the
  reminder through the runtime profile-unlock receiver.
- Returning to the main screen after unlock did not mark an already-running profile
  as a deferred resume; a no-op quiet-mode request is not a credential failure.
- The public `LauncherApps` route opened the selected Files app in managed user 10.
- Turning WorkPause OFF cleared an active reminder without changing the managed
  profile's state. No WorkPause crash was found in the emulator crash buffer.
- The reminder has its own explicit notification group so Android does not replace
  its tap action with the main-screen action of an auto-grouped status summary.

These checks do not establish Samsung heads-up behavior, delivery of an actual
future timed alarm, or every locked-phone, location, and employer-policy scenario.

The release APK is non-debuggable. Its application ID remains
`io.github.marinfrankovic.workpause`; the work-app picker is non-exported. The release
certificate SHA-256 remains:

```text
b57687219a28dd705da54e5d804e1b556d5bb4ff3f43fe35b389be016bddbefe
```

For the published APK checksum, use
[`SHA256SUMS.txt`](https://github.com/marinfrankovic/WorkPause/releases/download/v1.0.3/SHA256SUMS.txt)
from the matching release. The earlier **local 1.0.3-preview (code 4)** APK has SHA-256
below; it is not the checksum of the version-code-5 release:

```text
6e69248989b41bd860e9af88802051658d878d0dd9da16c57b513f7654f3a44e
```

### Required Samsung acceptance test

1. Update over 1.0.2 without uninstalling. Confirm saved rules and the silent-control
   grant are retained. Allow notifications under Settings > Unlock reminders and
   check Samsung's banner settings for the reminder channel.
2. Keep the phone unlocked with a personal app open. Schedule pause, then resume a
   few minutes later. Confirm a reminder appears in the notification shade, without
   manually opening a work app first. A heads-up banner depends on Android settings.
3. Tap Open work apps and select a work app. Verify that **Android**, not WorkPause,
   asks for the required work PIN and opens the selected app after successful entry.
4. Cancel PIN entry and confirm work apps remain protected and the reminder/picker
   can be used again. If Android reports a profile unlock, the reminder is cleared;
   this OEM-specific behavior needs observation.
5. Repeat with an already unlocked work profile; do not expect an unnecessary new
   reminder when no pause/resume transition occurred.
6. Confirm OFF and the next scheduled pause clear the reminder. A previously opened
   picker must not resume work apps after WorkPause is OFF or outside work hours.
7. Disable notifications and verify the app shows the blocked reminder status while
   scheduling still works. Do Not Disturb may suppress only the heads-up banner.

### Galaxy S24 Ultra: preview update installed

On **2026-10-20**, the signed **1.0.3-preview (code 4)** candidate was installed over
1.0.2 on the personal side of the owner's **Galaxy S24 Ultra (SM-S928B)**.
Installation returned success and foreground launch returned `Status: ok`.
`MODIFY_QUIET_MODE` and `POST_NOTIFICATIONS` remained granted. The APK matched its
recorded checksum; no uninstall or app-data reset was used.

This confirms installation and retained permissions, not notification arrival,
Samsung heads-up settings, or the new credential flow. No schedules were changed
and no manual pause/resume test was initiated on the phone during deployment.
**Notification setup remains necessary:** allow Unlock reminders in WorkPause and
review the dedicated channel's banner/pop-up settings in Android. An allowed runtime
permission does not prove those Samsung settings are enabled.

The existing owner-confirmed functional results below apply to **1.0.2**, not the
new notification or picker behavior.

## CI environment

The Android build workflow uses JDK 17 and explicitly installs `platform-tools` and
`platforms;android-35`. This overrides the pinned SDK setup action's default package
list, which includes the obsolete `tools` package and fails during SDK setup.
The workflow builds the debug APK, runs unit tests and lint, and uploads a debug
artifact; it does not publish or replace the signed release APK.

## Physical phone: user-confirmed operation

On **2026-10-16**, the owner reported personally testing WorkPause 1.0.2 on a
**Samsung Galaxy S24 Ultra running One UI 9**, and confirmed that it
**works as expected**.

The owner also reported needing to enter the **work-profile PIN when the profile
turns back on**. This is an expected Android or employer-policy credential
requirement. Silent control requests a profile state change; it does not unlock
the profile or bypass its PIN.

This is a user-confirmed functional result, not an independently observed test or
a general Samsung compatibility certification. Individual test scenarios were not
recorded, so the report does not separately establish scheduled alarm delivery,
other locked-device or credential scenarios, accessibility fallback behavior, or
enterprise-policy compatibility.

### Installation and readiness checks

On **2026-10-16**, WorkPause 1.0.2 (code 3) was installed on the personal side of a
**Samsung Galaxy S24 Ultra (SM-S928B)**. The phone reported Android SDK **37**.
The One UI version was supplied by the owner, not independently checked.

| Phone check | Result |
|---|---|
| Published APK checksum | Matched the verified 1.0.2 release asset |
| Installation and foreground launch | Passed |
| Existing managed work profile | Detected by Android and by WorkPause |
| Silent-control permission | `MODIFY_QUIET_MODE` granted to the personal-side app |
| App's Silent control status | Active |
| Exact alarms | Allowed, confirmed in WorkPause Settings |
| Battery optimization exemption | Not enabled; unrestricted battery use recommended |
| Main screen status during readiness check | Waiting for unlock |
| Non-disruptive check | Work-profile flags unchanged before and after inspecting Settings |
| Actual pause/resume and scheduled switching | Not attempted during the assistant's readiness checks |
| Locked-device or separate work-profile credential handling | Not tested during the assistant's readiness checks |

No schedule was saved and the WorkPause switch was not changed during the readiness
check. Permission and readiness checks do **not** prove that silent switching works.
The assistant did not initiate switching, because it would interrupt work apps.
The owner's subsequent functional test report is recorded above.

There is no independent WorkPause-specific verification of Intune, Knox, employer
compliance, other work-profile credential configurations, or Samsung background restrictions.
Testing performed on the original application must not be presented as testing of WorkPause.

## WorkPause 1.0.2 preview

| Check | Status |
|---|---|
| Debug APK compilation | Passed |
| Weekly schedule and location regression tests | 31 passed, no failures/errors/skips |
| Android debug lint | Passed, with warnings |
| Protected release signing | Passed: account-restricted PKCS12 key, DPAPI-protected password |
| Release APK signature and packaged identity | Passed: RSA-4096 certificate, own application ID, version 1.0.2 |
| Packaged legal notices | LICENSE and NOTICE match the root originals exactly |
| Removed testing entry point | TEST_FIRE absent from compiled code; invoking old action creates no test PendingIntent |
| Internal retry receiver | Non-exported in the packaged manifest |
| Pixel Android 15 emulator installation and foreground launch | Passed with the signed release APK |
| Galaxy S24 Ultra installation and setup readiness | Passed with limitations recorded above |
| Owner's physical-device functional test | User-confirmed: works as expected on Galaxy S24 Ultra / One UI 9 |
| Work-profile PIN on resume | Owner reports PIN entry is required when the profile turns back on; expected credential protection, not bypassed by WorkPause |
| Compact scheduler UI | Passed: single-row header; no repeated weekday/weekly heading |
| Single WorkPause ON/OFF switch | Passed: Auto, Resume now, and Pause now absent |
| Immediate ON/OFF persistence | Passed: both states survive process restart without Save |
| Unsaved daily edits | Passed: switch preserves the visible draft without saving it |
| Save schedule while OFF | Passed: daily edits persist after restart without enabling WorkPause |
| OFF alarm cancellation | Passed: no WorkPause alarms in the active portion of the Android alarm dump |
| Daily modes and copying | Unit regression tests passed; existing controls retained |
| Settings ADB instructions | Previously smoke-tested in 1.0.0; controls unchanged |
| Location controls | Hidden for configurations with location gating disabled; existing enabled settings remain manageable |
| Queued accessibility fallback cancellation | Callbacks cancelled on OFF; delayed wake activity revalidates saved state; fallback switching not independently tested |
| Work-profile pause/resume on a physical device | Not separately documented; covered only by the owner's general functional report above |
| Locked-device resume on a physical device | Not tested |
| Location-triggered resume on a physical device | Not tested |
| Samsung S24 Ultra / One UI 9 functional compatibility | Owner reports expected operation; not independently certified or established for other devices |

The single-switch UI checks were performed on the signed 1.0.2 APK. Original daily
rules and switch state were restored after testing. The README screenshot was
refreshed from this build.

The emulator had no work profile: installation, rendering, alarm cancellation, and
scheduler editing were tested, not actual profile switching. The earlier Copy command
check verified its UI response, not an independent read of Android's clipboard contents.

The 24 weekly-schedule tests cover independent daily hours, daily modes, copying,
legacy migration, validation, overnight boundaries and OFF-day priority, overlaps,
week rollover, disabled automation, and daylight-saving changes. New regressions
ensure OFF takes priority over an all-day ON rule and day edits/copying cannot
enable WorkPause. The 7 location
tests cover fix age, invalid accuracy, saved coordinates/radius, and active-window
boundaries. They do not simulate Android's location providers or enterprise management.

Release identity: `io.github.marinfrankovic.workpause`, version name `1.0.2`, version
code `3`, non-debuggable. The release certificate SHA-256 fingerprint is:

```text
b57687219a28dd705da54e5d804e1b556d5bb4ff3f43fe35b389be016bddbefe
```

Inherited Git history and old upstream tags can mention versions such as v1.4.0 or
v1.7.0. Those are not WorkPause APK versions. WorkPause uses its own application ID,
signing certificate, and release numbering.

The release title retains "Preview" and the release is marked Latest on GitHub.
Emulator and automated checks do not establish real-device
compatibility, reliable delivery of every scheduled alarm, or enterprise compliance.
