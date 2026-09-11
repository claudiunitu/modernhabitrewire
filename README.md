# Voward

Voward is a private Android self-management app that adds an intentional pause before selected apps and websites. It asks the user to state a purpose, choose a session length, wait, and then explicitly decide whether to continue.

Voward is not medical treatment, a diagnostic device, or a validated measure of addiction or any biological or psychological state. If digital use is causing significant distress, sleep loss, unsafe behavior, or loss of control, consider seeking help from a qualified mental-health professional.

## Screenshots

<p align="center">
  <img src="docs/screenshots/voward-today.png" alt="Voward Today dashboard showing active protection and the remaining daily allowance" width="23%" />
  <img src="docs/screenshots/voward-rules.png" alt="Voward Rules screen showing additive editing while protection is active" width="23%" />
  <img src="docs/screenshots/voward-progress.png" alt="Voward Progress screen showing weekly metrics and its empty state" width="23%" />
  <img src="docs/screenshots/voward-gate.png" alt="Voward decision gate asking for an intention and planned session length" width="23%" />
</p>

<p align="center"><sub>Daily status &middot; Protected rules &middot; Local progress &middot; Intentional-use decision gate</sub></p>

## How enforcement works

Voward runs as the Android **Device Owner**. Rules are not enforced by watching the screen; they are written into Android itself and into the browser's own enterprise-policy engine.

| Concern | Mechanism | Enforced by |
| --- | --- | --- |
| Strict app rules | `setApplicationHidden` | PackageManager |
| Regular app rules | `setPackagesSuspended`, lifted for an approved session | PackageManager |
| Website rules | `URLBlocklist` managed configuration | The browser's network stack |
| Unfilterable browsers | `setPackagesSuspended` when website rules exist | PackageManager |
| Uninstall, force-stop, clear data | Active device admin, `setUninstallBlocked`, `setUserControlDisabledPackages` | PackageManager, Settings |
| Safe mode, extra users, clock, reset | User restrictions | system_server |
| Session metering | `UsageStatsManager.queryEvents`, pulled once per session | On demand |
| State that must survive a data wipe | `setApplicationRestrictions` on Voward's own package | system_server |

Consequences worth knowing before you install:

- **Nothing stays resident.** There is no foreground service, no accessibility service, no wakelock, and no polling loop. Killing Voward, letting it crash, or OOM-killing it changes nothing — the blocks live in `system_server`. A persisted 15-minute job and a boot receiver reconcile state back to what your settings ask for.
- **There is no network layer.** No VPN, no DNS pinning, no external service. Your own VPN is never touched and cannot bypass anything here, because nothing is enforced at the packet level.
- **You can still install anything.** Play or unknown sources, either way. New apps are paused once and you decide whether to keep them.
- **You can use any browser.** Browsers Voward can filter get the policy; browsers it cannot filter are paused rather than uninstalled, and only when you actually have website rules.
- **Accessibility services are untouched.** Voward no longer uses accessibility at all, so password managers and other accessibility clients keep working.

## Current features

- Protect launchable apps selected from an installed-app picker.
- Protect website domains, paths, and exact queries in browsers that honour managed configuration.
- Require a purpose and a planned session of 1–60 minutes before regular protected content opens.
- Apply a configurable entry pause. Repeat entries increase the pause according to the configured growth percentage.
- Require a second **Open intentionally** choice after the pause; the app never opens protected content automatically when the timer reaches zero.
- Offer three configurable alternative next steps at the gate; selecting one returns to Android Home.
- Spend one allowance second for each second of approved protected use and end the session at the smaller of the planned duration or remaining allowance.
- Mark individual app or website rules as strict. Strict rules cannot be opened while protection is active.
- Test any browser against a real blocked page and record the verdict; a browser that stops honouring policy after an update is re-probed.
- Quarantine newly installed apps: each is paused once and you choose to keep it or make it a rule.
- Optional tamper restrictions: block safe mode, block extra users and profiles, lock date and time, block factory reset in Settings, disable USB debugging.
- Dead man's switch: if Voward fails its health check for N days, every restriction releases itself.
- Show the remaining allowance, next pause, protected-rule count, sessions, sessions ended early, and limits reached.
- Keep up to 14 completed daily summaries on-device and show current-week protected-use time, sessions, outcomes, and common session start time.
- While protection is active, allow only additive rule changes: add new rules or make existing rules strict; removal and strict-to-regular changes stay locked.
- Import and export portable configuration as JSON.
- Optional allowance notifications and system grayscale during approved sessions.
- Every setting row, including read-only status rows, carries an **ⓘ** button that states the consequence of the setting, not just its definition.

## Requirements

- Android 8.0 (API 26) or newer. **Android 11 (API 30) or newer is strongly recommended** — `setUserControlDisabledPackages` is API 30+, `DISALLOW_CONFIG_DATE_TIME` is unknown to API 26–27, and the private-space restriction needs API 35.
- **Device Owner provisioning is mandatory.** Without it Voward enforces nothing at all, activation refuses, and the app says so on its provisioning row. There is no fallback path.
- A computer with Android SDK Platform Tools, once, to provision.
- Notification permission is optional.
- Usage access is optional; without it a session is charged its quoted duration rather than measured foreground time.
- Grayscale is optional and requires `WRITE_SECURE_SETTINGS`, granted once over ADB.

The app is intended for direct, private installation on your own phone. It is not prepared for Google Play distribution.

---

# How to install

Written for someone installing on their own phone, with a computer, once. Budget about twenty minutes.

## 1. What you need

- An Android phone you own, running Android 8.0 or newer (11+ recommended).
- A computer with [Android SDK Platform Tools](https://developer.android.com/tools/releases/platform-tools).
- A USB cable that carries data, not power only.
- Somewhere to write down the recovery key **that is not this phone**.

## 2. Build the APK

```shell
# Windows
.\gradlew.bat assembleRelease

# macOS or Linux
./gradlew assembleRelease
```

Release signing is read from these environment variables:

- `MHR_RELEASE_STORE_FILE` (path relative to the repository root)
- `MHR_RELEASE_STORE_PASSWORD`
- `MHR_RELEASE_KEY_ALIAS`
- `MHR_RELEASE_KEY_PASSWORD`

If they are not set the build still succeeds, but the output is `app-release-unsigned.apk`, which Android will not install — set them before you build. Keep the keystore outside source control and keep it forever: the application ID and the signing key must not change across upgrades.

> **Install the release APK, not a debug one.** Debug builds carry `android:testOnly="true"`, which is what makes `adb shell dpm set-device-owner` work on a phone that has already finished setup — and also makes `adb shell dpm remove-active-admin` work, which tears down every protection in a single command. A debug build is the right choice for trying Voward out. It is the wrong choice for relying on it.

## 3. Enable USB debugging

1. Settings → About phone → tap **Build number** seven times.
2. Settings → System → Developer options → **USB debugging** on.
3. Connect the cable and approve the prompt on the phone.
4. On some OEM builds (Xiaomi, HyperOS) also enable **USB debugging (Security settings)**.

Verify:

```shell
adb devices
# expect exactly one line ending in "device", not "unauthorized"
```

## 4. Clear accounts and extra users

Android refuses to set a device owner while either exists. This step does **not** wipe the phone.

**Remove every account.** Settings → Passwords & accounts → remove all of them, including Google and any OEM account (Samsung, Xiaomi, Huawei).

```shell
adb shell dumpsys account | grep -i "Account {"
# expect no output
```

**Remove every extra user, work profile, and the guest session.** Settings → System → Multiple users.

```shell
adb shell pm list users
# expect exactly one: UserInfo{0:Owner:...}
```

## 5. Install and provision

```shell
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell dpm set-device-owner com.example.voward/.MyDeviceAdminReceiver
```

A debug build needs `-t` on the install:

```shell
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
```

Expected output from `dpm set-device-owner`:

```text
Success: Device owner set to package ComponentInfo{com.example.voward/com.example.voward.MyDeviceAdminReceiver}
Active admin set to component {com.example.voward/com.example.voward.MyDeviceAdminReceiver}
```

Verify:

```shell
adb shell dumpsys device_policy | head -40
# expect a "Device Owner:" block naming com.example.voward
```

Then **sign your accounts back in** (Settings → Passwords & accounts). Do it now: adding accounts is harder once protection is active.

## 6. If provisioning fails: the factory-reset route

Use this only if step 5 will not succeed, or on a phone you are dedicating to this.

1. Back up anything you want to keep.
2. Settings → System → Reset → **Erase all data**.
3. In the setup wizard, **skip Wi-Fi and skip signing in**. Finish setup with no account at all.
4. Connect to Wi-Fi afterwards, enable USB debugging (step 3), then run the step 5 commands.

## 7. Optional one-time grants

Neither is required.

```shell
# Grayscale during approved sessions
adb shell pm grant com.example.voward android.permission.WRITE_SECURE_SETTINGS

# Accurate session metering
adb shell appops set com.example.voward GET_USAGE_STATS allow
```

Usage access can also be granted without a computer: Settings → Apps → Special app access → Usage access → Voward.

## 8. Set up in the app

1. Open Voward and read the disclosure.
2. Set your goal, daily allowance, default session length, base pause, and repeat-entry growth. Tap the **ⓘ** on anything you are unsure about.
3. Add at least one app or website rule. Mark a rule strict only if it should stay closed for the whole protection period.
4. Check the approved-browser list. Run **Test this browser** for anything not already approved — some browsers honour the policy without declaring it.
5. Choose the enforcement toggles. Block safe mode, block extra users, and lock date and time default **on**; disable USB debugging and block factory reset in Settings default **off**, because those two close an escape route rather than a bypass.
6. Create the recovery key. **Write it down somewhere that is not this phone.** It is stored as a salted PBKDF2-HMAC-SHA256 hash and cannot be displayed or recovered by anyone, including you.
7. Choose the deactivation cooldown, the confirmation window, and the dead man's switch interval (1–90 days, default 14).
8. Review the summary and activate.

Activation requires device-owner provisioning, at least one rule, a positive daily allowance, and a recovery key.

## 9. Verify protection actually works

Do this before you rely on it.

| Test | Expected |
| --- | --- |
| Open a strict app | A system "paused" dialog, not the app |
| Open a blocked site in an approved browser | `ERR_BLOCKED_BY_ADMINISTRATOR` or the browser's own block page |
| Open the same site in a private/incognito window | The same block |
| Install Firefox from Play | Installs, then becomes a paused icon |
| Settings → Apps → Voward | **Uninstall** and **Force stop** unavailable |
| `adb shell pm clear com.example.voward` | `SecurityException: Cannot clear data for a protected package` |
| Reboot | Everything still enforced, before you open the app |

To force a reconcile instead of waiting fifteen minutes:

```shell
adb shell cmd jobscheduler run -f com.example.voward 4411
```

## Troubleshooting provisioning

| Message | Cause | Fix |
| --- | --- | --- |
| `...because there are already some accounts on the device` | An account is still present, often an OEM one | Remove every account, re-check with `dumpsys account` |
| `...because there are already several users on the device` | A secondary user, work profile, or guest exists | Remove them, re-check with `pm list users` |
| `Trying to set the device owner, but device owner is already set` | Another DPC is provisioned | Remove it, or use the factory-reset route |
| `Unknown admin: ComponentInfo{...}` | Wrong component name, or the app is not installed | Use `com.example.voward/.MyDeviceAdminReceiver` exactly |
| `SecurityException: ... MANAGE_DEVICE_ADMINS` | Command not run from the ADB shell | Use `adb shell dpm ...`, not an on-device terminal |
| Refused because the device is already provisioned | Release build on a phone past setup | Use the factory-reset route; only test-only builds can provision post-setup |
| Command appears to succeed but `dumpsys device_policy` is empty | OEM restriction | Enable USB debugging (Security settings), or use the factory-reset route |
| Website rules do nothing in one browser | That browser ignores managed configuration | Run **Test this browser**; if it fails, the browser is paused instead |

---

# How to uninstall, reset, or factory reset

Device Owner without a reliable exit is a trap, so there are four independent ways out. Each one works with the one above it broken.

| Rung | Situation | Cost |
| --- | --- | --- |
| 1 | Normal | Recovery key + cooldown + confirmation |
| 2 | App will not open | A computer, plus the same key and cooldown |
| 3 | Recovery key lost | Waiting out the dead man's switch |
| 4 | Everything broken | Recovery-mode factory reset |

## Rung 1 — deactivate protection normally

1. Voward → Settings → enter the correct recovery key → request deactivation.
2. Protection, strict rules, and uninstall blocking stay fully active during the cooldown. You can cancel at any time without entering the key again.
3. When the cooldown ends the confirmation window opens **silently** — no notification, no badge, no sound, no reminder, no automatic action.
4. Inside that window, enter the recovery key again, press **Deactivate protection**, and accept the final confirmation.

With cooldown **0**, no request is created: a correct key, an explicit press, and the final confirmation deactivate immediately.

Deactivation clears every restriction in a fixed order — factory-reset protection, then user restrictions, then unsuspend, then unhide, then browser policy — and leaves Voward as device owner, so reactivating later needs no computer.

### Deactivation policy

The cooldown can be **0**, **1 minute**, **6 hours**, **12 hours**, **24 hours**, **48 hours**, or **72 hours**. The confirmation window can be **1**, **2**, **3**, **6**, **12**, or **24 hours**. The defaults are 24 hours and 1 hour. Both are selectable during setup and in Settings, and changeable only while protection is inactive. A shorter confirmation window is stronger protection against an impulsive exit.

Incorrect key attempts do not restart or extend a request. Missing the confirmation window deletes the request and you start over. Eligibility uses Android monotonic time cross-checked against `BOOT_COUNT`: a wall-clock shift beyond two minutes or a reboot invalidates a pending request, so moving the clock forward cannot buy an early exit. Time-zone changes do not affect it, so travelling is fine.

## Rung 2 — release from a computer when the app will not open

`ReleaseActivity` is a standalone screen with its own task affinity, a platform theme, and views built in code, so a crash in the main UI cannot take it down with it.

```shell
adb shell am start -n com.example.voward/.ReleaseActivity
```

It does **not** skip the recovery key, the cooldown, or the confirmation window — being a separate screen is about the app being broken, not about the policy being optional. It runs the same policy engine the main screen uses.

## Rung 3 — the dead man's switch

If Voward fails to record a successful health check for the configured number of days (1–90, default 14), every restriction releases itself automatically. This is the safety net for a crash loop or a bad update. It counts by whichever clock has moved further, so neither a clock rollback nor a reboot can hold it open.

If you have lost the recovery key and the app still runs, this is your exit — you wait.

## Rung 4 — recovery-mode factory reset

A device owner cannot block this. `DISALLOW_FACTORY_RESET` only removes the reset option from Settings; the hardware key combination for your device always works. Power the phone off and use your model's recovery-mode combination.

Note that `DISALLOW_FACTORY_RESET` also disables the OEM-unlocking toggle, so the bootloader cannot be unlocked until you deactivate.

## Uninstalling Voward completely

Android will not let you uninstall an active device owner at all, so the order matters:

1. **Deactivate protection** (rung 1 or rung 2). This has to complete first.
2. Voward → Settings → **Remove Voward** → **Give up control**. This calls `clearDeviceOwnerApp()` and releases device ownership permanently.
3. Uninstall normally, from Settings or:

```shell
adb uninstall com.example.voward
```

Setting Voward up again after step 2 needs a computer and, on a release build, a factory reset. There is no way back from the phone alone.

If **Remove Voward** fails, the app says so. On a debug build only, the ADB fallback is:

```shell
adb shell dpm remove-active-admin com.example.voward/.MyDeviceAdminReceiver
```

This does nothing on a release build — that is the point of not shipping `testOnly`. For a release build the fallback is a recovery-mode factory reset.

## What each kind of reset actually clears

| Action | Clears | Leaves |
| --- | --- | --- |
| **Reset today's statistics** (in-app) | Today's counters | Spent allowance, rules, protection state. Unavailable while protection is active |
| **Import configuration** (in-app) | Rules and timing, replaced from a file | Recovery key, allowance balance, usage history. Unavailable while protection is active |
| **Deactivate protection** | Every suspension, hidden app, user restriction, and browser policy | Device ownership, your rules, your recovery key |
| **Remove Voward** | The above, plus device ownership | The app itself, until you uninstall it |
| **`pm clear` / Settings → Clear data** | Nothing. Refused while Voward is an active device admin | — |
| **Factory reset** | Everything, device ownership included | Nothing. Enforcement state lives in `system_server` and goes with it |

`pm clear`, `pm uninstall`, and `am force-stop` are all refused by Android while Voward is an active device admin, independently of the "block uninstall" toggle. Verified on device: `SecurityException: Cannot clear data for a protected package`, `Failure [DELETE_FAILED_INTERNAL_ERROR]`, and `Ignoring request to force stop protected package`.

---

## Allowance and sessions

All allowance values are stored in seconds:

```text
session_cost = elapsed approved seconds
session_limit = min(remaining allowance at entry, planned duration)
next_pause = clamp(base pause × (1 + growth × ln(1 + sessions today)), 1, 3600)
```

One allowance second always buys one second of approved protected use. The session limit shown at the gate stays fixed for that session. The remaining balance cannot fall below zero, and carried allowance is capped at one daily allowance.

An approved session unsuspends exactly one package, or lifts exactly one rule out of the browser blocklist, for a bounded time. The deadline is an exact alarm, so a session ends on time even if Voward was killed the moment after it started; where exact alarms are unavailable the alarm degrades to inexact and the 15-minute reconcile catches the overrun.

**How to reach the gate.** A protected app is paused at the PackageManager level, so there is no launch to intercept and the gate has to be pulled rather than pushed. **Tap the rule** — in Voward's app list or website list — to ask for a session. Some OEMs also offer a details button on the system's "paused by your admin" dialog, which opens the same gate in the moment; Samsung on API 30 does not, so the rules list is the reliable route on any device.

Session time is measured with `UsageStatsManager`, pulled once at session end over that session's own window, so you are charged for foreground time actually spent in the app rather than for wall time. Without usage access there is nothing to measure and the quoted duration is charged in full.

Choosing **Not now** or leaving during the pause returns to the Android Home screen. When no allowance remains, a new regular session cannot start. Strict rules ignore allowance and stay blocked until protection is deactivated.

## Website rules and browsers

- `example.com` matches the domain and its subdomains, but not `notexample.com`.
- `example.com/news` matches `/news` and its subtree, but not `/newspaper`.
- `example.com/search?q=focus` requires that exact query string.

Ambiguous single-word and malformed rules are rejected. Rules are translated into a `URLBlocklist` managed configuration and enforced inside the browser's own network stack: in fullscreen, across redirects, in subframes, in Custom Tabs, and in private windows. Clearing the browser's data does not remove them, because the policy lives in `system_server`.

Tap a website rule to request a session against it. The browser shows its own block page and that page cannot link back to Voward, so the rules list is the entry point.

| Browser | Managed config |
| --- | --- |
| Chrome and its channels | Yes |
| Edge | Yes |
| Brave | Verified end to end on device |
| Vivaldi, Kiwi, Cromite, Opera | Unknown — use **Test this browser** |
| Firefox family, Tor Browser | No |
| Samsung Internet | Knox licence only |
| DuckDuckGo, Opera Mini | No |

A browser that declares no policy support is *unknown*, not unsupported — some Chromium forks honour a policy they never declare, which is what the test flow is for. Browsers that genuinely cannot filter are paused rather than uninstalled, and only when website rules exist.

**Permanently out of scope:** in-app WebViews inside permitted apps (Reddit, Telegram, X), web-wrapper apps that do not register as browsers, and `keyword:` rules. `URLBlocklist` matches URLs, not page text, so any existing `keyword:` rule stops being enforced; the rules screen names each retired rule and says what to replace it with.

## Progress

The Progress tab reports only activity measured by Voward: protected-use time, session count, sessions ended early, limits reached, and the most common session start hour for the current week. Completed daily summaries are retained locally for up to 14 days. Voward does not estimate "time saved" or infer urges, wellbeing, or health outcomes.

Resetting today's statistics clears the current day's counters without restoring allowance. That action and the other timing controls are unavailable while protection is active.

## Privacy and safety

- Voward reads which apps are installed and, with usage access, how long one was in the foreground during an approved session. **It never reads your screen or what you type** — there is no accessibility service.
- Rules, preferences, and usage counters stay on the device. There is no analytics SDK, no network client, and no `INTERNET` permission.
- Website rules are handed to your browser as enterprise policy and enforced there.
- Configuration export writes only the selected JSON file. It includes the deactivation cooldown and confirmation-window durations and the enforcement toggles, but excludes the recovery key, active-protection state, pending deactivation requests and timestamps, current allowance balance, usage counters, and temporary approvals.
- Purpose text entered at the gate is not stored.
- Android backup and data extraction are disabled.
- Emergency, dialer, telecom, Settings, System UI, permission-controller, and Voward packages are resolved through `PackageManager` and can never be restricted.
- `DISALLOW_INSTALL_UNKNOWN_SOURCES`, `DISALLOW_UNINSTALL_APPS`, and `DISALLOW_CONFIG_VPN` are deliberately never set: installing what you like, removing other apps, and running your own VPN all stay your decision.
- **Factory Reset Protection is not implemented.** It is the one setting that could genuinely lock you out of your own phone, and a half-safe version is worse than none. The reconciler only ever clears it.

### Residual risk, documented rather than fixed

- Recovery-mode factory reset and bootloader flash. Unclosable, and deliberately so — it is the last rung of the escape ladder.
- Root. Not resisted.
- WebViews inside permitted apps, and web-wrapper apps that do not register as browsers.
- A second device.
- A new app serving the same content under a package no rule names. The quarantine makes this cost one prompt rather than nothing, but it is a snapshot diff on a fifteen-minute reconcile, not an install broadcast, so there is a window in which the new app runs unpaused.

## Optional grayscale

Grayscale is automatic during approved sessions in regular restricted apps and websites once its system permission is available; there is no separate in-app switch. Strict rules never open, so grayscale does not apply to them. Everything else works without it.

```shell
adb shell pm grant com.example.voward android.permission.WRITE_SECURE_SETTINGS
```

Then check **Settings → Advanced and data**; it should read **Grayscale reminder available**. Voward restores the previous Android color-correction state when the session ends. To revoke:

```shell
adb shell pm revoke com.example.voward android.permission.WRITE_SECURE_SETTINGS
```

## Build and test

The project uses the checked-in Gradle wrapper, Java 17, compile/target SDK 36, min SDK 26, application ID `com.example.voward`, and Java namespace `com.example.voward`.

```powershell
# Windows
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

```shell
# macOS or Linux
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The local suite combines pure Java policy tests with Robolectric tests for preferences, receivers, activities, adapters, and Android settings behavior. Generate the JaCoCo report with:

```powershell
.\gradlew.bat createDebugUnitTestCoverageReport
```

The HTML report is written to `app/build/reports/coverage/test/debug/index.html`.

Device-owner behaviour, browser compatibility, the suspended-app dialog on each OEM skin, notifications, grayscale restoration, rotation, process death, and battery use all require testing on a real provisioned device — none of it is reachable from the local suite.
