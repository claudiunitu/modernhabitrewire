# Voward end-to-end test — findings

Run: 2026-09-12. Devices: Pixel 10 emulator (Android 17, clean provision) and
Samsung SM-A405FN (Android 11, already device owner).
Build: debug, versionCode 11007, commit 6787b8d.

## Status
- Unit tests: 72 pass, 0 fail.
- Lint (debug): clean.

## Bugs found
_(filled in as the run proceeds)_

### 1. Decision gate shows a nested sentence where a duration belongs
**Severity: medium (user-visible copy, every gate opening).**
Screen: the pause screen, summary chip. Reads
`30:00 remaining today · next pause Quoted re-entry pause: 30 seconds`.
Cause: `DecisionGateActivity.java:173-177` passes the whole
`R.plurals.quoted_reentry_pause` sentence ("Quoted re-entry pause: %d seconds")
into `%2$s` of `gate_summary_friendly` ("%1$s remaining today · next pause %2$s").
Fix: feed a bare duration plural (e.g. `seconds_compact`) into `%2$s`, or drop
`gate_summary_friendly`'s "next pause" prefix. `strings.xml:42-43`, `:253`.

### 2. "More details" on the paused-app dialog cannot reach the decision gate
**Severity: high (breaks the app's main journey on any OEM that shows the button).**
Screen: tap a paused app -> system "Blocked by work policy" dialog -> More details.
`SuspendedAppDetailsActivity`'s intent-filter (`AndroidManifest.xml:133-135`) has no
`<category android:name="android.intent.category.DEFAULT" />`. The platform sends an
implicit, package-targeted intent, which requires DEFAULT to resolve.
Verified on Pixel 10 / Android 17:
`am start -a android.intent.action.SHOW_SUSPENDED_APP_DETAILS -p com.example.voward`
-> `Error: Activity not started, unable to resolve Intent`.
The same intent with an explicit component starts the gate fine, so the activity
itself works — only the filter is unmatchable.
Fix: add the DEFAULT category to that intent-filter.

### 3. Android 12+ never offers the "More details" button at all
**Severity: medium (misleading copy; the workaround exists but is not signposted).**
On Pixel 10 / Android 17 the paused-app dialog has only a **Close** button, yet the
support message (`suspended_support_message`) says "Tap for details to request a
session." `dumpsys package` shows `suspendingPackage=<0>android`, so the platform,
not Voward, owns the dialog and offers no details route.
The in-app fallback exists (Rules -> tap an app -> request a session) and the code
comments already acknowledge OEM variance, but the message should not promise a
button that is not there on modern Android.
Fix: reword the support message to point at Voward's own Rules screen.

### 4. Cooldown label "Cooldown: 0"
**Severity: low (cosmetic).**
Setup step 5 and Settings, deactivation-cooldown dropdown. The zero entry reads
`Cooldown: 0` with no unit. `strings.xml:315` `cooldown_zero_choice`.
It behaves correctly (0 means deactivate immediately, handled at
`ModernMainActivity.java:341,759,833`), only the label is wrong.
Fix: "No cooldown - deactivate immediately".

### 5. "Confirm deactivation within: 1 hours"
**Severity: low (cosmetic).**
Setup step 5 and Settings. `strings.xml:320` `window_hours_choice` is a plain
string with `%1$d hours`, so the 1-hour choice is ungrammatical.
Fix: make it a plural resource.

### 6. Sessions overrun their deadline; nothing ever asks for "Alarms & reminders"
**Severity: medium (a 5-minute session ran 5 min 43 s).**
`AndroidManifest.xml:15` declares `SCHEDULE_EXACT_ALARM`, which Android 12+ does not
grant automatically, and no screen sends the user to
`Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM` (grepped: `canScheduleExactAlarms` is
the only reference in the codebase). `EnforcementCoordinator.java:214-227` falls back
to an inexact alarm and logs
`Exact alarms unavailable; session deadline may run late`.
Measured on Pixel 10: session started 09:44:08, quoted 300 s, actually ended 09:49:51.
The 2-minute reconcile poll bounds the overrun, but it is still free time.
Fix: either add an "Alarms & reminders" row to setup step 4 / Settings next to the
notifications row, or switch to `USE_EXACT_ALARM` (allowed for this app category and
granted at install).

### 7. A rule naming an app that is not installed re-applies every policy every 2 minutes
**Severity: medium (battery / wakeups, permanent).**
`com.instagram.android` was added as a rule before installing it — a documented
feature. `setPackagesSuspended` refuses an absent package, so
`PolicyReconciler.applySuspension` (`:171-183`) correctly leaves it out of the mirror,
which means `reconcile`'s `desired.equals(previous)` short-circuit (`:59`) can never
hit again. Logcat shows the full apply repeating on every fast-reconcile tick:
```
09:52:27 Reconciling ...suspended=1 -> ...suspended=2
09:52:27 Suspension refused for [com.instagram.android]
09:55:10 Reconciling ...suspended=1 -> ...suspended=2
09:57:21 ...
```
Each pass rewrites user restrictions, suspension, hiding, the uninstall block and the
`URLBlocklist` bundle for every managed browser, forever.
Fix: track "desired but not installable" packages separately in the mirror so the
comparison can converge, and re-attempt only when the package set changes.

### 8. No way to end a session early
**Severity: low-medium.**
`endSession` is only reachable from the deadline alarm and the reconcile poll
(`EnforcementCoordinator.java:57`, `SessionDeadlineReceiver.java:25`) — no button in
the app, no notification action (`StatusNotifier.java` posts a content intent only).
With usage access granted the charge is measured, so leaving the app is enough; without
it the full quoted duration is charged with no way to stop the clock.
Fix: an "End session now" action on the ongoing notification.

### 9. A hidden (strict) app loses its name in the rules list
**Severity: low (cosmetic).**
Before activation the strict Maps rule reads "Maps"; once protection hides the package
it reads `com.google.android.apps.maps` with a placeholder icon, because hiding removes
it from `PackageManager` so the label cannot be resolved.
Fix: cache the label when the rule is created and fall back to it.

### 10. The URL editor still advertises `keyword:` rules that are no longer enforced
**Severity: low.**
The "Add a website rule" helper text reads
`Examples: example.com · example.com/news · keyword:shorts`, the editor accepts a
`keyword:` rule, and then the Rules screen shows a banner saying it is not enforced
(`strings.xml:397`, correct and clearly worded). The app teaches a rule shape it
immediately disowns.
Fix: drop `keyword:` from the helper text and reject the prefix at input with the
banner's explanation.

### 11. Rebooting during a session keeps the session open for as long as the device had been up
**Severity: high (reliable bypass, no tools needed).**
Reproduced on the Pixel 10 emulator:
1. Start a 5-minute session for `youtube.com` (uptime was 2 758 s).
   Stored `managed_session_deadline_elapsed_ms = 3054247`.
2. Reboot.
3. After boot: `managed_session_package` is still `com.android.chrome`,
   `managed_session_url_pattern` is still `youtube.com`, and the reconcile logs
   `urlRules=1` — youtube.com is still dropped from `URLBlocklist`.
   No `SessionDeadlineReceiver` alarm exists any more (0 in `dumpsys alarm`).
Cause: the deadline is stored as a raw `SystemClock.elapsedRealtime()` value
(`EnforcementCoordinator.java:204-208` `hasExpiredSession`), which resets to zero on
boot, and nothing re-arms the alarm — `BootCompletedReceiver` only calls
`schedulePeriodicReconcile` and `reconcileNow`. So the session runs until uptime passes
the stale value: here ~51 minutes of free access from a 5-minute session, and it scales
with whatever the uptime was when the session started.
Everything else recovered correctly across the reboot (suspensions, hidden packages,
user restrictions, uninstall block).
Fix: store the deadline the way the deactivation request and the dead man's switch
already do — wall clock + elapsed + `Settings.Global.BOOT_COUNT` — and treat a boot
count change as "session over" (see `DeactivationPolicyEngine.evaluateRequest` and
`DeadMansSwitchPolicy.elapsedSinceCheckMs` for the pattern already in the codebase).
Re-arm the alarm from `BootCompletedReceiver` for the remaining time.

### 12. "SETUP NEEDED" is shown whenever protection is off, even when setup is complete
**Severity: low-medium (contradicts the card right below it).**
`ModernMainActivity.java:473-488`: the badge is driven by
`operational = active && essentialsReady`, so a fully configured but deactivated app
shows the badge **SETUP NEEDED**, the title "Ready when you are" and the detail
"Finish the essentials, then activate protection." — while the readiness card on the
same screen says "Required setup complete."
Seen on both devices.
Fix: three states, not two — protected / paused (essentials ready) / setup needed.

### 13. Dead man's switch is the least-verified code in the app
**Severity: medium (it is the last-resort escape hatch).**
`DeadMansSwitchPolicy.elapsedSinceCheckMs` and `isExpired` have no unit test — the only
test references are to the day *setting* (`AppPreferencesManagerTest.java:294,326,354`).
It cannot be exercised on a device inside a test run either (30-day threshold; the AVD is
a Play image so `adb root` is refused and the clock cannot be moved with the clock guard
on). So the one mechanism that frees the user if the app stops working has never actually
been observed working.
Fix: unit-test `elapsedSinceCheckMs` (same boot / new boot / clock moved back / overflow)
and `isExpired`, and add a debug-only way to force expiry on a test build.

### 14. New-app quarantine could not be verified
**Severity: unknown — not a finding, a gap in this run.**
`NewAppQuarantine.sweep` has no unit test and could not be exercised on either device:
the emulator has no installable third-party APK (system apps cannot be uninstalled from
`shell`, and `pm install-existing` does not look like a new install to the sweep).
Worth covering before release.

### 15. Exported JSON leaks float noise
**Severity: cosmetic.**
`voward-config.json` contains `"reentryGrowth": 0.3499999940395355` — the float widened
to a double on the way into JSON (`AppPreferencesManagerSingleton.java:1345`). It
round-trips correctly, it just looks broken in a file the user is meant to keep.
Fix: round to the stored precision when exporting.

### 16. Progress shows "0m used this week" next to "3 sessions this week"
**Severity: cosmetic.**
Minute rounding: the three sessions charged 30 s, 0 s and 0 s, and the budget did move
(30:00 -> 29:30 on the Today screen), but both usage figures round to `0m`.
Fix: show seconds below a minute, or "<1m".

---

## What was verified working

Pixel 10 emulator (Android 17, provisioned from clean during this run) unless noted.

- Provisioning: `dpm set-device-owner` accepted; setup step 4 correctly reads
  "Device owner - Active".
- Six-step setup wizard, all steps, including the notification permission prompt.
- Rule editors: add by package name, add by picker, strict flag, delete, and refusal of
  a critical package (`com.android.settings` -> "This app is required for emergency
  access or device recovery"). A rule for a not-yet-installed app is accepted, as designed.
- URL editor: domain, path and keyword rules accepted; a bare label rejected.
- Activation applies everything: YouTube `suspended=true`, strict Maps hidden from
  `pm list packages`, `no_safe_boot` / `no_config_date_time` / `no_add_user` /
  `no_add_managed_profile` / `no_add_private_profile` set, `auto_time=1`, `URLBlocklist`
  pushed to the filterable browser.
- Defaults match the code: debugging guard and settings-reset guard off, the rest on.
- Decision gate: purpose required, three stages (PLAN / PAUSE / CONFIRM), countdown,
  and the app really unsuspends on confirm.
- Session accounting with usage access granted: a 5-minute session where YouTube was
  foreground for ~30 s charged exactly 30 s.
- Pause growth: 30 s -> 40 s after one session at the 35 % default.
- Session end re-suspends the app.
- Ratchet while active: delete buttons hidden, a strict rule cannot be downgraded,
  "Strict rules cannot be opened while protection is active", and a rule naming an
  uninstalled app reports "not installed" instead of opening a gate.
- Retired-keyword banner appears on the Rules screen with the exact rule named.
- Deactivation ladder: wrong key refused; empty key refused (no confirmation dialog);
  correct key starts the cooldown and stores `bootCount`; protection stays fully enforced
  through the cooldown; after the cooldown the window opens; the second key plus a
  confirmation dialog releases.
- Release is complete: suspensions lifted, hidden packages restored, every Voward user
  restriction cleared (the `no_add_*_profile` entries that remain are the platform's own
  device-owner defaults, present before activation too), `Release complete` logged.
- Uninstall guard on: `pm uninstall` -> `DELETE_FAILED_DEVICE_POLICY_MANAGER`,
  `am force-stop` leaves the process alive, `pm clear` -> `SecurityException`.
- Reboot: every restriction, suspension and hidden package restored automatically
  (the session leak in finding 11 is the one exception).
- Export/import: export writes schema 8 including `deadMansDays`; import of an unknown
  schema version (99) changes nothing; import naming `com.android.settings` changes
  nothing; a valid file applies every field.
- Import is blocked while protection is active.

Samsung SM-A405FN (Android 11, already device owner):

- Brave really enforces the rules: `youtube.com` -> "This page is blocked / Your
  organisation doesn't allow you to view this site"; `example.com` loads normally.
- Firefox (cannot take `URLBlocklist`) is suspended while website rules exist; Brave is
  left alone. This is the intended filterable/unfilterable split.
- Website session end-to-end: session opened -> youtube.com loads in Brave -> at the
  deadline the browser is evicted, the rule goes back, and youtube.com is blocked again.
- Exact alarms work on API 30: a 300 s session ended at 300 s exactly, charged 291 s.
- The paused-app dialog on Samsung has a "Learn more" button (Android 17 has none), but
  it goes to Settings > Device administrator, not to Voward — see finding 2.

## Test-build caveat (not a product bug)

The debug build sets `android:testOnly="true"` (`app/src/debug/AndroidManifest.xml`),
which is what lets `dpm set-device-owner` work after setup. On the Samsung this also
makes the Settings > Device administrator screen offer a working **Deactivate** button,
reachable in three taps from any paused app. A release build does not carry the flag.
Not tapped — clearing device ownership on a phone that has accounts cannot be undone
without a factory reset.
