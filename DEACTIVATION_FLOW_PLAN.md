# Delayed Deactivation Flow Plan

## Objective

By default, prevent an acute urge from turning possession of the recovery key into immediate blocker deactivation, while retaining an explicit disabled-cooldown option for development and debugging.

When a positive cooldown is configured, deactivation will require two deliberate key entries separated by that cooldown. The second entry will be accepted only during a limited, silent deactivation window. When the cooldown is set to `0`, the recovery key may deactivate protection without creating a delayed request.

## Agreed Scope

This plan includes:

- A configurable cooldown of 0, 1 minute, 6 hours, 12 hours, 24 hours, 48 hours, or 72 hours, where 0 disables the delayed flow.
- A configurable deactivation window of 1, 2, 3, 6, 12, or 24 hours.
- One key entry to request deactivation.
- A second key entry during the deactivation window to complete deactivation.
- Silent window activation and expiration.
- Clock-change and reboot invalidation.
- Cancellation of a pending request without deactivating protection.

This plan explicitly excludes:

- Guardian approval.
- Recurring maintenance windows.
- Notifications, sounds, vibration, badges, toasts, banners, or other proactive signals when the window opens or closes.
- Background polling or minute-by-minute wake-ups.
- Any automatic deactivation.

## Defaults

- Cooldown: 24 hours.
- Deactivation window: 1 hour.

Shorter windows should be described as providing stronger protection against impulsive deactivation. All available values remain valid explicit user choices.

When cooldown 0 is selected, the deactivation-window setting is not applicable and should be disabled or hidden in the UI.

## User Flow

```text
ACTIVE
  |\
  | \ cooldown = 0: enter correct recovery key and explicitly deactivate
  |  `-----------------------------------------------------------> INACTIVE
  |
  | cooldown > 0: enter correct recovery key and request deactivation
  v
COOLDOWN_PENDING
  |\
  | \ cancel request
  |  `------------------------------------------> ACTIVE
  |
  | cooldown completes silently
  v
DEACTIVATION_WINDOW_OPEN
  |\
  | \ window expires silently
  |  `------------------------------------------> ACTIVE
  |
  | enter correct recovery key again
  | press "Deactivate protection"
  | confirm final dialog
  v
INACTIVE
```

Protection remains fully active in both `COOLDOWN_PENDING` and `DEACTIVATION_WINDOW_OPEN`.

## Behavioral Rules

### Configuration

- Cooldown choices are 0, 1 minute, 6 hours, 12 hours, 24 hours, 48 hours, and 72 hours.
- The zero choice is displayed simply as `0`.
- Window choices are 1, 2, 3, 6, 12, and 24 hours.
- The window setting is disabled or hidden when cooldown is 0 because no pending request or completion window is used.
- Both settings are editable only while protection is inactive.
- Activating protection locks both settings.
- Activation clears any stale pending deactivation request.
- The selected values are included in the activation summary.

Suggested activation copy:

> Deactivation requires your recovery key, a 24-hour cooldown, and your recovery key again during a 1-hour window.

When cooldown is disabled, the activation summary must instead warn:

> Cooldown is disabled. Your recovery key can deactivate protection immediately.

### Immediate Debugging Mode

When the configured cooldown is 0:

- No pending deactivation request is created.
- No deactivation window is created or consulted.
- The configured window value may remain stored for later use but has no effect.
- The active Settings screen presents the recovery-key input as an immediate deactivation action.
- The user still deliberately presses the deactivation button; keyboard submission must not deactivate automatically.
- The final confirmation dialog remains required to prevent an accidental tap or password-manager submission.
- Successful key verification and final confirmation deactivate protection immediately.
- The UI must clearly state that this mode removes the anti-compulsion cooldown safeguard.

### Starting a Request

- While protection is active, the current immediate deactivation action becomes `Request deactivation`.
- The user must enter the correct recovery key and press the request button.
- A correct key creates a pending request but does not deactivate protection.
- The key input is cleared after submission.
- An incorrect key does not create or modify a request.
- Only one request may exist at a time.
- Repeated actions cannot shorten or restart a request to produce an earlier deadline.
- The request snapshots its cooldown and window durations when created.

### Cooldown

- All blocking and uninstall-friction behavior continues normally.
- The user can view the remaining cooldown only by deliberately opening Voward and navigating to the deactivation section.
- The user may cancel the request immediately without entering the key again.
- Cancellation deletes the request and leaves protection active.
- The transition into the deactivation window is silent.

### Deactivation Window

- The window is created only for the specific request that completed its cooldown.
- The user must deliberately open Voward and navigate to the deactivation section to discover that it is open.
- The user must enter the correct recovery key again.
- Key entry alone never deactivates protection.
- Keyboard submission must not trigger deactivation automatically.
- The user must press an explicit `Deactivate protection` button.
- A final confirmation dialog is required before changing the active state.
- Incorrect key attempts do not extend, restart, or otherwise modify the window.
- If the window expires, the request is deleted silently.
- After expiration, the complete process must be started again with the first key entry.

Suggested final confirmation copy:

> Deactivate protection now? Your rules and settings will become editable.

### Silence Requirements

The app must not proactively draw attention to a deactivation opportunity.

- Do not send a notification when the window opens.
- Do not send a reminder before it closes.
- Do not produce sound or vibration.
- Do not add an app badge.
- Do not show a toast, snackbar, banner, dialog, or full-screen prompt solely because the window opened or expired.
- Do not automatically navigate to the deactivation screen.
- Do not schedule a background wake-up for window activation.
- Do not poll in the background.

The app derives the request state only when it is already running for another reason or when the user deliberately opens the relevant screen.

## Time and Tamper Handling

### Authoritative Time Source

Cooldown and window eligibility must use `SystemClock.elapsedRealtime()`, not the editable wall clock.

For each request, persist:

```text
request wall-clock timestamp
request elapsedRealtime timestamp
request boot count
cooldown duration snapshot
window duration snapshot
request identifier/status
```

The wall-clock timestamp exists for display and tamper comparison. It must not decide whether the window is open.

### Clock-Shift Detection

At validation time, calculate:

```text
wall progress = current wall time - request wall time
real progress = current elapsedRealtime - request elapsedRealtime
clock shift   = wall progress - real progress
```

If the absolute clock shift exceeds a small tolerance, invalidate and delete the request while leaving protection active. An initial tolerance of two minutes is recommended to avoid false invalidation from small automatic clock corrections.

Validate the request:

- When Android reports `ACTION_TIME_CHANGED` while the app process is available.
- When the main Activity starts or resumes.
- When the deactivation section is displayed or refreshed.
- Immediately before showing the window as open.
- Immediately before accepting the second key.
- Opportunistically when the accessibility service is already processing events, without adding polling.

A time-zone change must not invalidate a request because it does not alter epoch progression.

### Reboot Handling

- Persist the Android boot count with the request.
- If the current boot count differs, invalidate the request.
- If boot-count validation is unavailable or inconsistent, fail closed and invalidate the request.
- Reboot invalidation leaves protection active and requires a completely new request.

This strict behavior avoids relying on a monotonic timestamp that resets at boot.

### Detection Limitation

If the app process is stopped, the user changes the clock and restores it before Voward runs again, the intermediate wall-clock change may not be observable. This still cannot accelerate the request because window eligibility uses `elapsedRealtime`. A reboot during the attempt invalidates the request.

## Persistence Design

Add device-local preference fields for:

- Configured cooldown duration.
- Configured deactivation-window duration.
- Pending request identifier or presence flag.
- Request wall-clock timestamp.
- Request monotonic timestamp.
- Request boot count.
- Cooldown snapshot.
- Window snapshot.

Pending request state must:

- Survive Activity recreation and ordinary process death.
- Remain local to the device.
- Be excluded from configuration export and import.
- Be cleared atomically on cancellation, expiration, invalidation, successful deactivation, and new activation.

The configured cooldown duration and configured deactivation-window duration are portable policy settings. They must be included in configuration export and restored by configuration import. Import remains unavailable while protection is active, so imported values cannot weaken an active commitment.

Cooldown value 0 is a valid portable value and must round-trip through export/import. The stored window value must also round-trip even though it is inactive while cooldown is disabled.

## Architecture

### `DeactivationPolicyEngine`

Create a pure Java component responsible for deriving the current state and deciding whether an operation is allowed.

Suggested states:

```text
NO_REQUEST
COOLDOWN_PENDING
WINDOW_OPEN
EXPIRED
INVALIDATED
```

Suggested operations:

```text
createRequest(...)
evaluateRequest(...)
canComplete(...)
cancelRequest(...)
invalidateRequest(...)
```

The engine should receive time and boot information as arguments or through injectable interfaces. It should not directly depend on an Activity, view, or Android clock in unit tests.

### Preference Layer

Extend `AppPreferencesManagerSingleton` with typed methods for the policy configuration and pending-request record. Avoid exposing individual mutable fields to Activities when an atomic record operation is possible.

### UI Layer

`ModernMainActivity` should render the engine's state and send user actions to the policy layer. It must not directly call `setIsBlockerActive(false)` until the engine has approved completion and the user has accepted the final confirmation.

The legacy `MainActivity` also contains immediate deactivation logic. Even though it is not the launcher Activity, its bypass must be removed, routed through the same policy, or the obsolete Activity must be removed safely.

## UI States

### No Pending Request

> Protection is active. Enter your recovery key to begin the deactivation cooldown.

Controls:

- Recovery-key input.
- `Request deactivation` button.

### Cooldown Pending

> Deactivation requested. Protection remains active.

The screen may show the estimated opening time and remaining duration while it is visible. These displays are informational; monotonic time remains authoritative.

Controls:

- `Cancel request` button.

Do not show the recovery-key completion input yet.

### Window Open

> The deactivation window is open. Protection remains active until you complete deactivation.

Controls:

- Recovery-key input.
- `Deactivate protection` button.
- `Cancel request` button.

### Expired or Invalidated

When the user later visits the deactivation section, explain that the prior request can no longer be used and that protection remains active.

Examples:

> The deactivation window expired. Start a new request if you still want to deactivate protection.

> The request was invalidated because the device time changed or the device restarted.

This message appears only in response to the user opening the relevant screen. It must not be pushed proactively.

## Test Plan

### Policy Unit Tests

- A correct first key creates a request but does not deactivate protection.
- With cooldown 0, no request is created and a correct key plus final confirmation can deactivate immediately.
- The window cannot open before the complete cooldown has elapsed.
- Advancing wall time alone does not open the window.
- A wall-clock shift beyond tolerance invalidates the request.
- Small clock corrections within tolerance do not invalidate the request.
- A boot-count change invalidates the request.
- The correct second key cannot complete deactivation before the window.
- The correct second key can complete deactivation during the window.
- The window's final boundary is handled consistently.
- An expired window deletes the request and leaves protection active.
- Cancellation deletes the request and leaves protection active.
- Incorrect key attempts do not alter timestamps or extend the window.
- A repeated request cannot shorten an existing cooldown.
- Snapshotted durations remain unchanged for the life of a request.

### Persistence and Migration Tests

- New installations receive the 24-hour cooldown and 1-hour window defaults.
- Existing installations migrate without losing their recovery key or active state.
- A pending request survives ordinary process recreation.
- Configuration export includes the configured cooldown and deactivation-window durations.
- Configuration import validates and restores both durations.
- Invalid or unsupported imported durations are rejected rather than silently clamped.
- Pending request state is excluded from exported configuration.
- Import cannot inject or restore a pending request.
- Activation clears stale request state.

### Activity Tests

- Active protection shows `Request deactivation`, not an immediate off action.
- With cooldown 0, active protection clearly shows the immediate deactivation action and warning instead of `Request deactivation`.
- The completion key field is hidden during cooldown.
- The completion action is available only during the window.
- Keyboard actions cannot automatically deactivate protection.
- The final confirmation is required.
- Settings for cooldown and window are disabled while active.
- No notification or proactive UI is produced when state crosses a time boundary.

### Regression Tests

- Rules remain locked throughout cooldown and the open window.
- Strict rules remain blocked.
- Uninstall friction remains active.
- Successful completion clears pending state and unlocks settings.
- Existing decision-gate, allowance, browser, and app-interception behavior remains unchanged.

## Implementation Sequence

1. Add the pure policy model and exhaustive state-transition tests.
2. Add configuration and pending-request persistence with migration tests.
3. Add cooldown and window selectors to inactive Settings and setup/activation summaries.
4. Replace direct deactivation with the first-key request flow.
5. Implement silent state derivation and the second-key completion flow.
6. Add final confirmation and prevent keyboard auto-submission.
7. Add clock-shift and reboot invalidation.
8. Remove or route all alternative direct-deactivation paths.
9. Update help text and `README.md` to describe the new recovery behavior and Android limitations.
10. Run the complete unit-test suite and release build, then perform real-device testing for process death, sleep, time changes, time-zone changes, and reboot.

## Acceptance Criteria

The work is complete when:

- With a positive cooldown, possession of the correct recovery key cannot immediately deactivate active protection.
- With a positive cooldown, the complete configured cooldown must pass between the two successful key entries.
- With a positive cooldown, the second key works only during the configured window.
- With cooldown 0, a correct key and explicit final confirmation can deactivate protection immediately without creating a request.
- Missing the window requires restarting the entire process.
- Changing the device clock cannot accelerate deactivation and invalidates the request when detected.
- Rebooting invalidates the request.
- Window activation and expiration are completely silent.
- Protection remains active until the second key, explicit button press, and final confirmation all succeed.
- No direct deactivation bypass remains in an Activity or other app component.
- Exported configuration includes the selected cooldown and deactivation-window durations, but never includes a pending request or its timestamps.
