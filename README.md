# Voward

Voward is a private Android self-management app that adds an intentional pause before selected apps and websites. It asks the user to state a purpose, choose a session length, wait, and then explicitly decide whether to continue.

Voward is not medical treatment, a diagnostic device, or a validated measure of addiction or any biological or psychological state. If digital use is causing significant distress, sleep loss, unsafe behavior, or loss of control, consider seeking help from a qualified mental-health professional.

## Current features

- Protect launchable apps selected from an installed-app picker.
- Protect website domains, paths, exact queries, or explicit `keyword:` rules in supported browsers.
- Require a purpose and a planned session of 1–60 minutes before regular protected content opens.
- Apply a configurable entry pause. Repeat entries increase the pause according to the configured growth percentage.
- Require a second **Open intentionally** choice after the pause; the app never opens protected content automatically when the timer reaches zero.
- Spend one allowance second for each second of approved protected use and end the session at the smaller of the planned duration or remaining allowance.
- Mark individual app or website rules as strict. Strict rules cannot be opened while protection is active.
- Show the remaining allowance, next pause, protected-rule count, sessions, sessions ended early, and limits reached.
- Keep rules and settings read-only while protection is active.
- Import and export portable configuration as JSON.
- Optionally show allowance notifications, enable Device Admin uninstall friction, and use system grayscale during approved sessions.

## Requirements

- Android 8.0 (API 26) or newer.
- The **Attention Firewall** accessibility service is required for protection.
- Device Admin is optional and only adds uninstall friction.
- Notification permission is optional.
- Grayscale is optional and requires `WRITE_SECURE_SETTINGS`, which normal Android installs cannot grant from the app.

The app is intended for direct/private installation. Its accessibility and uninstall-protection behavior has not been prepared for Google Play accessibility-policy review.

## Setup

1. Open Voward and review the disclosure.
2. Set a real-life goal, daily allowance, default session length, base entry pause, and repeat-entry growth.
3. Add at least one protected app or website rule. Mark a rule strict only if it should remain unavailable while protection is active.
4. Enable the **Attention Firewall** accessibility service.
5. Optionally enable Device Admin uninstall protection and notifications.
6. Create a recovery key.
7. Review the setup summary and activate protection.

Activation requires the accessibility service, at least one rule, a positive daily allowance, and a recovery key. While protection is active, rules and timing settings are locked. Enter the recovery key on the Settings tab to deactivate protection.

The recovery key is stored as a salted PBKDF2-HMAC-SHA256 hash and cannot be displayed or recovered. There is no timed or charging bypass. Device Admin and accessibility add friction, but they do not turn a normal Android installation into kiosk mode.

## Allowance and sessions

All allowance values are stored in seconds:

```text
session_cost = elapsed approved seconds
session_limit = min(remaining allowance at entry, planned duration)
next_pause = clamp(base pause × (1 + growth × ln(1 + sessions today)), 1, 3600)
```

One allowance second always buys one second of approved protected use. The session limit shown at the gate stays fixed for that session. The remaining balance cannot fall below zero, and carried allowance is capped at one daily allowance.

Choosing **Not now** or leaving during the pause returns to the Android Home screen. Leaving protected content ends its active metering segment; reaching the quoted limit sends an app session Home or replaces the restricted browser tab. When no allowance remains, a new regular session cannot start. Strict rules ignore allowance and remain blocked until protection is deactivated.

## Website rules

- `example.com` matches the domain and its subdomains, but not `notexample.com`.
- `example.com/news` matches `/news` and its subtree, but not `/newspaper`.
- `example.com/search?q=focus` requires that exact query string.
- `keyword:shorts` performs an intentionally broad text match.

Ambiguous single-word and malformed rules are rejected. URL enforcement depends on the visible address field exposed by a supported browser. Browser or OEM updates can change accessibility behavior, so website blocking should be tested on each target device.

## Privacy and safety

- The accessibility service can observe foreground apps, window content, and supported browser address text. It can show the decision gate, navigate Home, replace a blocked browser tab, and—when optional uninstall protection is enabled—guard relevant app-info and uninstall screens.
- Rules, preferences, and usage counters remain on the device. Voward has no analytics SDK or external network client.
- The `INTERNET` manifest permission is used by a loopback-only server that serves the local browser block page.
- Configuration export writes only the selected JSON file. It excludes the recovery key, active-protection state, current allowance balance, usage counters, and temporary approvals.
- Purpose text entered at the gate is not stored.
- Android backup and data extraction are disabled.
- Voward prevents known emergency, dialer, Settings, System UI, permission-controller, and Voward packages from being selected as protected apps.

## Optional grayscale

On a development or privately managed device, grant grayscale access with ADB:

```shell
adb shell pm grant com.example.voward android.permission.WRITE_SECURE_SETTINGS
```

Voward reports whether this permission is available and restores the previous Android color-correction state when an approved session ends. The rest of the app works without this permission.

## Build and test

The project uses the checked-in Gradle wrapper, Java 17, compile/target SDK 36, and application ID `com.example.voward`.

On Windows:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

On macOS or Linux:

```shell
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The local suite combines pure Java policy tests with Robolectric tests for preferences,
receivers, activities, adapters, and Android settings behavior. Generate the JaCoCo report with:

```powershell
.\gradlew.bat createDebugUnitTestCoverageReport
```

The HTML report is written to `app/build/reports/coverage/test/debug/index.html`.

Release signing is read from these environment variables:

- `MHR_RELEASE_STORE_FILE`
- `MHR_RELEASE_STORE_PASSWORD`
- `MHR_RELEASE_KEY_ALIAS`
- `MHR_RELEASE_KEY_PASSWORD`

Keep the signing material outside source control and preserve the application ID and signing key for upgrades. Accessibility interception, browser compatibility, Device Admin behavior, notifications, grayscale restoration, rotation, process death, and battery use also require testing on real target devices.
