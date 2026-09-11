# Hardening Plan: Device Owner Enforcement

Status: proposal for branch `device-owner`. Nothing here is implemented yet.

This revision replaces the earlier accessibility-centred plan. The change of direction is
deliberate: accessibility stops being an enforcement mechanism and is scheduled for deletion.

---

## 1. Objective and honest scope

Voward's adversary is its own user: motivated, with unlimited physical access, full knowledge
of the app, and the ability to install software. "Hardened" therefore means:

- Every bypass costs more than the impulse it defeats.
- No bypass is a single tap, a single ADB command, or a single reboot.
- Losing the enforcement mechanism makes protected content **less** available, not more.

Non-goals: kiosk mode, protecting against a third party, protecting a device that is not the
user's own, resisting root, and resisting a recovery-mode wipe.

### 1.1 The central change

Today, enforcement is an app reacting to UI events. Killing the accessibility service unblocks
everything. The new model moves enforcement into `PackageManager` and into the browser's own
policy engine, so the app being absent, crashed, or force-stopped changes nothing.

That is the difference between a race and a rule.

### 1.2 What this costs

**Device Owner becomes mandatory.** With accessibility deleted there is no meaningful product
for a user who will not provision. The previous plan's Tier 0 and Tier 1 stop existing.

Provisioning does **not** require a factory reset in most cases — see section 8.

---

## 2. Target architecture

Three pillars. None is a race, none needs a VPN slot, none needs external infrastructure.

| Concern | Mechanism | Enforced by |
| --- | --- | --- |
| Strict app rules | `setApplicationHidden` | PackageManager |
| Regular app rules | `setPackagesSuspended`, lifted for an approved session | PackageManager |
| URL rules | `URLBlocklist` managed configuration | The browser's network stack |
| Unfilterable browsers | `setPackagesSuspended` on detection | PackageManager |
| Uninstall, force-stop, clear data | `setUninstallBlocked`, `setUserControlDisabledPackages` | PackageManager, Settings |
| Safe mode, extra users, clock, reset | User restrictions | system_server |
| Session metering | `UsageStatsManager.queryEvents` | Pull, on demand |
| State that must survive `pm clear` | `setApplicationRestrictions(admin, self, …)` | system_server |

### 2.1 Polarity inversion

- **Baseline**: every protected package suspended at the PackageManager level.
- **Exception**: an approved session unsuspends one package for a bounded time.

Consequences:

- Force-stopping, crashing, or OOM-killing Voward is harmless. Suspension lives in
  system_server and survives reboot with no `BOOT_COMPLETED` receiver involved.
- There is no launch to intercept, so there is no race to lose.
- A bypass attempt makes the device more restricted, which removes the incentive.

### 2.2 The network layer is deliberately absent

- No `VpnService`. Android permits one active VPN; taking that slot would stop the user running
  their own provider. The user's VPN is left completely alone.
- No private DNS pinning. DNS is host-granular, overridden by any VPN, and bypassed by a
  browser's own DoH. Relying on it would create false confidence.
- No external service of any kind. Filtering happens above the network, inside the browser
  that is making the request.

A user VPN cannot bypass anything here, because nothing here is enforced at the packet level.

---

## 3. Accessibility removal schedule

The service is retired in stages. Each stage is independently shippable, and at each stage the
service carries less responsibility.

| Stage | Responsibility removed | Replaced by | Code retired |
| --- | --- | --- | --- |
| B | App interception | `setPackagesSuspended` | `handleAppInterception`, `onForegroundAppChanged`, `isTransientSystemOverlay`, `isLauncherPackage`, `refreshImeList`, `refreshLauncherList` |
| B | Session metering | `UsageStatsManager` | Metering-segment bookkeeping in `AttentionFirewallService` |
| C | Uninstall and settings guarding | `setUninstallBlocked`, `setUserControlDisabledPackages`, user restrictions | `UninstallGuardPolicy` (265 lines), the guard watchdog, `UninstallGuardPolicyTest` |
| D | URL interception | `URLBlocklist` managed config | `BrowserSupport` (167 lines), `checkBrowserUrl`, `scheduleDeferredUrlCheck`, the redirect state machine, `BrowserUrlEnforcementPolicy`, `StaticBlockPageServer` (175 lines), `StaticBlockPageServerTest`, the `INTERNET` permission |
| E | The service itself | — | `AttentionFirewallService` (1692 lines), both `attention_firewall_accessibility_service.xml` variants, the manifest service entry, the setup step |

**All five stages are done.** B, C and D gated each path behind the device owner while leaving
it compiled, so an unprovisioned install kept working throughout; E deleted all four rows at
once, after the replacement had been watched working on a provisioned phone. Doing the
deletions stage by stage would have silently unenforced every install that had not been
provisioned yet.

**What 3.1 now means.** There is no accessibility fallback any more, so an install that is not
the device owner enforces nothing at all and says so: the provisioning row states it, and
activation refuses. That is the deliberate end state, not a gap.

`UrlPatternMatcher` survives stage D. It stops being a runtime matcher and becomes the
rule-authoring validator and the rule → `URLBlocklist` translator.

### 3.1 Transition rules

- **Device Owner is authoritative from stage B onward.** Gate every remaining accessibility
  path behind a single `isEnforcedByDeviceOwner(rule)` check. Two layers acting on the same
  rule produce double gates and races that are worse than either layer alone.
- **Existing installs must not silently unenforce.** On upgrade without provisioning, keep the
  accessibility path running and tell the user what is changing. Protection that quietly stops
  working is the worst failure this app can have.

### 3.2 What is permanently given up

- In-app WebViews inside permitted apps (Reddit, Telegram, X). They have no `http` intent
  filter, so they are not browsers and nothing here sees them.
- WebView wrapper apps that do not register as browsers.
- `keyword:` rules. `URLBlocklist` matches URLs, not page text.
- Browsers with no managed-configuration support are blocked, never filtered.

---

## 4. Browser control

### 4.1 What managed configuration is

Android Enterprise has a channel called managed configuration (also "app restrictions" or
AppConfig):

- An app ships `res/xml/app_restrictions.xml` declaring the settings it accepts from an admin.
- A Device Owner writes values with `setApplicationRestrictions(admin, pkg, bundle)`.
- The app reads them through `RestrictionsManager`.

The app cannot change those values. They are stored by system_server, so `pm clear` does not
remove them. Chromium browsers wire this into their enterprise policy engine — the same
policies an IT department pushes by group policy.

`URLBlocklist` is a list of patterns enforced inside the browser's network stack: in
fullscreen, across redirects, in subframes, and in Custom Tabs.

**Encoding.** Chromium declares `URLBlocklist` as `restrictionType="string"`, not as a string
array. The value is a JSON array inside one string — `["example.com"]` — so the bundle carries
`putString`, not `putStringArray`. Verified on device; see 4.5.1.

### 4.2 Pattern grammar

`[scheme://][.]host[:port][/path][@query]`

| Rule today | `URLBlocklist` entry | Behaviour |
| --- | --- | --- |
| `example.com` | `example.com` | Domain and subdomains |
| `example.com/news` | `example.com/news` | Path and subtree |
| `example.com/search?q=focus` | `example.com/search?q=focus` | Exact query token |
| strict allow-list mode | `*` plus `URLAllowlist` | Everything blocked except exceptions |
| `keyword:shorts` | no equivalent | Convert to a path rule or retire |

Private tabs need no separate key — `URLBlocklist` already covers them (verified). Chromium
also exposes `IncognitoModeUrlBlocklist` / `IncognitoModeUrlAllowlist` for rules that differ
between normal and private browsing; not needed here. `IncognitoModeAvailability: 1` stays
optional, for removing private browsing outright. Value `2` (forced) is unsupported on Android.

### 4.3 Detection

```java
Intent probe = new Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com"))
        .addCategory(Intent.CATEGORY_BROWSABLE);
pm.queryIntentActivities(probe, PackageManager.MATCH_ALL);
```

Run on `PACKAGE_ADDED` and `PACKAGE_REPLACED`, plus the periodic reconcile in case a broadcast
is missed. Replace the hardcoded `<package>` entries at `AndroidManifest.xml:24-55` with a
`<queries><intent>` of the same shape; the current list already cannot see browsers released
after it was written.

### 4.4 Capability probe

`RestrictionsManager.getManifestRestrictions(pkg)` reports the keys a package declares.

- **Declares `URLBlocklist` → approved.**
- **Does not declare it → unknown, not unsupported.** The manifest declaration exists so MDM
  consoles can discover keys. A Chromium fork can honour a policy it never declares.

So a negative probe is not a verdict. Ship a **Test this browser** flow: push a sentinel
blocked domain, ask the user to open it in that browser, ask whether they saw
`ERR_BLOCKED_BY_ADMINISTRATOR`. One tap, no external infrastructure, and it keeps working as
browsers change.

Re-probe on `PACKAGE_REPLACED`. A browser that stops honouring policy after an update is
suspended, and the user is told why.

### 4.5 Known support

| Browser | Managed config | Notes |
| --- | --- | --- |
| Chrome (`com.android.chrome` + channels) | Yes | Reference implementation |
| Edge (`com.microsoft.emmx`) | Yes | Same keys, documented for Android |
| Brave (`com.brave.browser`) | **Verified** | Tested end to end on device. See 4.5.1. |
| Vivaldi, Kiwi, Cromite, Opera | Probe | Chromium-derived, undocumented |
| Firefox family, Tor Browser | **No** | Mozilla has no AppConfig support on Android |
| Samsung Internet | Knox only | Needs a Knox licence |
| DuckDuckGo, Opera Mini | No | — |

### 4.5.1 Brave verification record

Samsung SM-A405FN, Android 11 (API 30), Brave 1.94.121. Policy pushed by TestDPC acting as
profile owner, which exercises the same `setApplicationRestrictions` path a Device Owner uses.

Static evidence, from the installed APK:

- The manifest declares `<meta-data android:name="android.content.APP_RESTRICTIONS">` pointing
  at a restrictions resource holding **256** policies.
- That resource declares `URLBlocklist`, `URLAllowlist`, `IncognitoModeAvailability`,
  `IncognitoModeUrlBlocklist` and `IncognitoModeUrlAllowlist`.
- `URLBlocklist` and `URLAllowlist` are both `restrictionType="string"`.

Runtime results:

| Case | Configuration | Result |
| --- | --- | --- |
| Normal tab | `URLBlocklist = ["example.com"]` | Blocked — "This page is blocked / Your organisation doesn't allow you to view this site" |
| Private tab | same | Blocked. No incognito-specific key required |
| Policy plumbing | same | `brave://policy` lists `URLBlocklist`, source **Platform** |
| Survives data clear | same | `pm clear` on Brave → first-run onboarding, page still blocked |
| Default deny | `URLBlocklist = ["*"]`, `URLAllowlist = ["example.com"]` | `example.com` loads, `wikipedia.org` blocked |
| Release | rows deleted, pushed empty | `wikipedia.org` loads again |

Consequences for the design:

- Blocking takes effect without restarting the browser and without any accessibility service.
- `pm clear` is not an escape route; the values live in system_server.
- The `*` plus `URLAllowlist` shape used for strict mode works, and the allowlist wins over the
  blanket block — so strict rules and the allowance model can share one mechanism.
- This build exposes no Tor window on Android, so the desktop Tor-window bypass does not apply.
  Re-check after major Brave updates.

Still unverified: Chromium forks other than Brave, and behaviour on API 35/36 (this device is
API 30, so `DISALLOW_ADD_PRIVATE_PROFILE` and current Pixel Settings layouts were not exercised).

### 4.6 Imposition

- Approved browser: push `URLBlocklist`, leave it usable.
- Everything else: `setPackagesSuspended`, **not** `setApplicationHidden`. Suspension keeps the
  icon and shows a system dialog carrying the short support message — verified on Samsung API
  30, where "Paused by Voward. Tap for details to request a session." is what the user reads.
  Hiding makes the app vanish, which reads as a bug and breaks link handling.
- `addPersistentPreferredActivity` pins `http`/`https` to the approved browser, so links land
  in a filtered browser even in the second before an unapproved one is suspended.

There is no per-package install allow-list API. The install succeeds and is neutralised on the
broadcast. Unlike an accessibility race, losing once does not lose the block: suspension is
permanent state, not a per-launch decision.

### 4.7 Guard rails

- Refuse to suspend a browser if it would leave zero usable `http` handlers.
- Never suspend the approved browser; it is the enforcement point.
- ~~Fix the suffix matching at `SafetyPolicy.java:28-30`~~ **Done in Phase 1.** The dialer,
  emergency dialer, telecom, Settings and permission-controller packages are resolved through
  `PackageManager`; `<queries>` declares the intents so package visibility does not hide them.
  Adding the current launcher and IME to the never-suspend set is still open — it lands with
  Phase 3, where suspension is what the set guards.

---

## 5. Performance and resource model

The current build is expensive in ways that are invisible in a profiler run of Voward itself,
because most of the cost lands in system_server and in other apps.

### 5.1 What costs what today

| Source | Cost |
| --- | --- |
| `typeWindowContentChanged` with no `packageNames` filter | The framework builds and dispatches accessibility node trees for **every UI change in every app**. A scrolling feed generates hundreds of events per second. |
| `flagIncludeNotImportantViews` + `canRetrieveWindowContent` | Widens each of those trees to include views that would otherwise be skipped. |
| `checkBrowserUrl` | One `findAccessibilityNodeInfosByViewId` IPC per configured address-bar ID, per event. |
| `getRootInActiveWindow()` | Snapshots the focused window's node tree. Called from the guard watchdog. |
| Four polling loops | `GUARD_WATCHDOG_INTERVAL_MS = 400`, `FORCED_EVICTION_RECHECK_MS = 350`, `BROWSER_URL_WATCHDOG_MS = 1000`, `CHECKPOINT_INTERVAL_MS = 5000` |
| `NOTIFICATION_THROTTLE_MS = 1000` | A notification rebuild per second, for the whole session. |
| Persistent foreground service | Keeps the process resident whenever protection is active, which is always. |

An enabled accessibility client that requests window content is a whole-device tax. Removing it
speeds up every other app, not just Voward.

### 5.2 Target profile

**The process should not be running most of the time.** Enforcement is state in system_server;
nothing needs to be resident to hold a block.

| Work | Mechanism | Frequency |
| --- | --- | --- |
| Session deadline | `AlarmManager.setExactAndAllowWhileIdle` | Once per session |
| Policy reconcile | `JobScheduler` periodic, persisted | 15 min |
| New-package handling | `PACKAGE_ADDED` / `PACKAGE_REPLACED` receiver | On install |
| Metering | `UsageStatsManager.queryEvents(start, end)` | Once at session end |
| Foreground service | Only while a session is running | Per session |

Outside an approved session there is no foreground service, no persistent notification, no
wakelock, and no polling loop. That also ends the standing fight with OEM battery managers.

### 5.3 Optimisation rules

- **No `Handler.postDelayed` loops.** All four intervals above are deleted, not ported. A
  deadline is an alarm; a periodic check is a job.
- **`JobScheduler`, not `WorkManager`.** WorkManager would be a new dependency for one
  periodic job. `JobScheduler.setPersisted(true)` is in the framework and survives reboot
  on its own.
- **Batch every DPM call.** `setPackagesSuspended` takes a `String[]`. The reconciler computes a
  diff and issues at most one suspend call and one unsuspend call per tick, never one per
  package.
- **Hash before you push.** `setApplicationRestrictions` is an IPC plus a disk write in
  system_server, and Chrome reloads its policy on change. Re-push only when the rule-set hash
  changes, not on every reconcile.
- **Cache the browser enumeration.** `queryIntentActivities` is not cheap. Invalidate on
  `PACKAGE_ADDED`, `PACKAGE_REMOVED`, `PACKAGE_REPLACED` only.
- **Query usage stats once per session end**, over the session's own time range. Never poll for
  a foreground package.
- **Write preferences on transition only.** The 5-second checkpoint exists because the process
  could die mid-session; with the deadline in an alarm and the block in system_server, there is
  nothing left to checkpoint.
- **Notification updates on state change**, not on a timer.
- **Info sheets render on demand.** Do not inflate a hidden info view per setting; keep the copy
  in strings and build one reusable sheet.

### 5.4 Acceptance criteria

Measured on a real device, protection active, no session running:

| Check | Command | Expected |
| --- | --- | --- |
| Process not resident | `adb shell dumpsys activity processes \| grep voward` | No entry, or cached only |
| No wakelocks | `adb shell dumpsys power \| grep -i voward` | No entry |
| No accessibility client | `adb shell settings get secure enabled_accessibility_services` | Voward absent |
| Battery attribution | `adb shell dumpsys batterystats --charged com.example.voward` | Negligible over 24 h |
| Reconcile cost | Trace one `WorkManager` tick | ≤ 50 ms CPU |
| Suspend IPC count | Log per reconcile | ≤ 2 |

---

## 6. Reversibility and the escape ladder

Device Owner without a reliable exit turns a self-management tool into a trap. This section is
a requirement, not a closing note.

### 6.1 One reconciler owns every policy call

- A single `EnforcementState` object describes the desired state.
- A single `PolicyReconciler.reconcile(desired)` owns **every** `DevicePolicyManager` call.
- No ad-hoc `dpm.addUserRestriction()` anywhere else in the codebase, or release will leak a
  restriction that nothing knows how to clear.
- `desired` is mirrored into `setApplicationRestrictions(admin, self, …)`, which lives outside
  the app data directory and survives `pm clear`.
- The reconciler runs on boot and on the periodic job, so a crash mid-apply or mid-release
  self-heals instead of wedging.

### 6.2 Two levels of release

| Action | Effect | Requires |
| --- | --- | --- |
| **Deactivate protection** | Clears all restrictions, unsuspends and unhides every package, clears browser managed config. Device Owner stays; reactivating later needs no ADB. | Recovery key, cooldown, confirmation window — the existing flow, unchanged. See `DEACTIVATION_FLOW_PLAN.md`. |
| **Remove Voward** | The above, then `clearDeviceOwnerApp()`, then a normal uninstall. | Deactivation first |

Release order is fixed and logged per step: FRP policy → user restrictions → unsuspend →
unhide → clear browser config → clear Device Owner. Clearing `DISALLOW_FACTORY_RESET` before
clearing Device Owner is mandatory, or the user is left with a restriction and no admin able to
remove it.

### 6.3 The escape ladder

Cheapest first. Every rung must work with the one above it broken.

1. **Key and cooldown** in the app, exactly as today.
2. **Standalone release activity** — ~~its own process~~, its own task affinity, no shared
   layout, theme or view models, views built in code, a platform theme. It must work when
   `ModernMainActivity` crashes on launch. **Built in Phase 2**, with two deliberate changes:

   - **Same process, not its own.** `SharedPreferences` is not multi-process safe. A release
     written from a second process would be invisible to a live main process, which would then
     write its cached `is_blocker_active = true` back over it — protection silently returning
     after the user released it. A separate task affinity and no shared UI already survive the
     failure this rung exists for.
   - **It enforces the recovery key, the cooldown and the confirmation window**, using the same
     `DeactivationPolicyEngine` the main screen uses. Skipping them would make the cooldown
     decorative for anyone holding a USB cable. Being a separate screen is about the app being
     broken, not the policy being optional.
3. **Dead-man's switch** — no successful health check in N days (monotonic time plus
   `BOOT_COUNT`, the same cross-check `DeactivationPolicyEngine` already uses) and enforcement
   auto-releases. Without this, a crash loop costs the user a factory reset. Default 14 days,
   configurable at setup.
4. **Recovery-mode factory reset.** A Device Owner cannot block this. `DISALLOW_FACTORY_RESET`
   only removes the Settings path; the hardware key combination always works.

### 6.4 The only real trap

`setFactoryResetProtectionPolicy` makes rung 4 demand a specific account afterwards. Keep it
**opt-in, default off**, and only ever anchored to an account the user has just proved they can
sign into during setup.

Note also that `DISALLOW_FACTORY_RESET` disables the OEM-unlock toggle, so bootloader unlocking
is unavailable until deactivation. Say so in that setting's info sheet.

---

## 7. Settings reference and info buttons

### 7.1 Requirement

**Every setting gets its own info affordance.** No exceptions, including read-only status rows.

- A 24 dp `ⓘ` button at the end of each setting row, minimum 48 dp touch target.
- Tapping opens one reusable `InfoSheet` bottom sheet. One instance, content bound at show
  time — do not inflate a hidden view per setting.
- `android:contentDescription` is `"About <setting name>"`.
- Copy lives in `strings.xml` as `info_<key>_title` and `info_<key>_body`.
- **Every body states the consequence, not just the definition.** If the setting is locked while
  protection is active, if it cannot be undone without the recovery key, or if it removes an
  escape route, that sentence comes first.

### 7.2 Copy

**Intention and alternatives**

| Setting | Info body |
| --- | --- |
| Goal (`functionalGoalInput`) | The real-life reason you are doing this. Shown on the Today screen and at the gate, so you see it at the moment you are deciding. It is never sent anywhere. |
| Alternative next steps (`replacementOneInput`–`Three`) | Three things you can do instead. They appear as buttons at the gate; choosing one returns you to the home screen. Leave them blank to hide the buttons. |

**Allowance and pauses**

| Setting | Info body |
| --- | --- |
| Daily allowance (`dailyBudgetInput`) | Total minutes of approved use per day, across all regular rules. One allowance second buys one second of use. Unused time carries over up to one full day's worth. Strict rules ignore it entirely. Locked while protection is active. |
| Default session (`defaultSessionInput`) | The session length pre-selected at the gate. You can still choose 1–60 minutes each time. A session ends at whichever is smaller: your chosen length, or the allowance you have left. Locked while protection is active. |
| Base pause (`baseWaitInput`) | How long you wait at the gate before the Open button appears, on your first entry of the day. The app never opens anything automatically when the timer reaches zero — you still have to choose. Locked while protection is active. |
| Repeat-entry growth (`reentryGrowthInput`) | How much the pause grows each time you come back the same day, as a percentage. At 0% the pause never grows. The pause is capped at one hour regardless. Locked while protection is active. |

**Deactivation**

| Setting | Info body |
| --- | --- |
| Recovery key (`deactivationKeySetterInputText`) | The only in-app way to turn protection off. Stored as a salted PBKDF2-HMAC-SHA256 hash — it cannot be displayed or recovered, by you or by anyone else. Write it down somewhere outside this phone before you activate. Losing it means waiting out the dead-man's switch or wiping the device. |
| Deactivation cooldown (`deactivationCooldownSpinner`) | How long you must wait after requesting deactivation before you can confirm it. Protection stays fully active throughout, and you can cancel at any point without the key. Set it to 0 to deactivate immediately after confirming. Locked while protection is active. |
| Confirmation window (`deactivationWindowSpinner`) | How long you have to finish deactivating once the cooldown ends. The window opens and closes silently — no notification, no reminder. Miss it and the request is deleted and you start over. A shorter window is stronger protection. Locked while protection is active. |
| Dead-man's switch | If the app fails a health check for this many days in a row, every restriction releases itself automatically. This is the safety net for a crash loop or a bad update. Shortening it weakens protection; lengthening it increases the risk of being stuck. Default 14 days. |

**Device Owner enforcement**

| Setting | Info body |
| --- | --- |
| Provisioning status | Shows whether Voward is the device owner. Without it, nothing on this screen can be enforced — apps can be uninstalled and website rules do nothing. Setting it up needs a computer, once; see the installation guide. |
| Block uninstall | Stops Voward being uninstalled, force-stopped, or having its data cleared from Settings. Enforced by Android, not by Voward, so it holds even when the app is not running. Released by the normal key-and-cooldown flow. |
| Block safe mode | Safe mode starts Android with third-party apps disabled, which would otherwise switch protection off with a single reboot. This closes that. Normal reboots are unaffected. |
| Block extra users and profiles | A second user, a guest session, a work profile, or a private space each get their own copy of your apps, outside these rules. This blocks creating them. Existing profiles must be removed before you activate. |
| Lock date and time | Rolling the clock forward would refill your daily allowance. This forces automatic network time and blocks manual changes. Time-zone changes are unaffected, so travelling is fine. |
| Disable USB debugging | **Removes one of your escape routes.** ADB is how you would recover this phone from a computer if something went wrong. Turning it off closes the last manual bypass; leaving it on means anyone with a cable and your unlocked phone has more options. Off by default. |
| Block factory reset in Settings | Removes the reset option from Settings. **It does not block the recovery-mode reset** — the hardware key combination always works, so you are never locked out of your own phone. It also disables the OEM-unlocking toggle, so you cannot unlock the bootloader until you deactivate. |
| Factory Reset Protection | **The one setting that can genuinely lock you out.** After a wipe, the phone will demand this Google account before it can be used again. Only turn it on with an account you are certain you can sign into. Off by default. |

**Browsers and apps**

| Setting | Info body |
| --- | --- |
| Approved browsers | Browsers Voward can filter directly, through Android's managed-configuration channel. Website rules are enforced inside the browser itself, so they work in fullscreen, across redirects, and in links opened from other apps. |
| Test this browser | Pushes a test rule to the selected browser and asks you to open a blocked page. Use it for any browser not already approved — some browsers honour the policy without advertising it. |
| Unfilterable browsers | Browsers Voward cannot filter, including the Firefox family. They are paused rather than filtered: the icon stays, but opening one shows a message instead. You do not have to uninstall them. |
| Quarantine new apps | Any newly installed app is paused once, and you decide whether to keep it. This catches web wrappers and clones that would otherwise slip past your rules. You can still install anything you want, from Play or elsewhere. |
| App rules and strict rules | A regular rule asks for a purpose and a pause before opening. A strict rule cannot be opened at all while protection is active — no gate, no allowance, no exception. While protection is active you can add rules and make them strict, but you cannot remove them or make them regular again. |

**Optional system features**

| Setting | Info body |
| --- | --- |
| Notifications | Optional allowance reminders. Deactivation is deliberately silent — you will never be notified that your confirmation window has opened. |
| Grayscale (`grayscaleStatus`) | Turns the screen grayscale during an approved session and restores your previous setting afterwards. Needs a one-time permission granted from a computer; everything else works without it. |
| Usage access | Lets Voward measure how long you actually spent in an app, so a session ends on time. Granted from Android Settings, not from a computer. Without it, sessions end on the clock rather than on real use. |

**Actions**

| Control | Info body |
| --- | --- |
| Activate protection (`button_blocker_activate`) | Applies every rule and restriction above. Requires at least one rule, a positive allowance, a recovery key, and device-owner provisioning. From this point, timing settings are locked and rules can only be tightened. |
| Reset today's statistics (`button_reset_stats`) | Clears today's counters. It does **not** restore spent allowance. Unavailable while protection is active. |
| Export configuration | Writes your rules and timing to a JSON file. It deliberately excludes the recovery key, the active state, pending deactivation requests, your allowance balance, and usage history. |
| Import configuration | Replaces your current rules and timing from a file. Only available while protection is inactive. |

---

## 8. Installation guide

Written for someone installing on their own phone, with a computer, once.

### 8.1 What you need

- An Android 8.0 (API 26) or newer phone. Android 11+ is recommended; the private-space and
  user-control restrictions need newer releases.
- A computer with [Android SDK Platform Tools](https://developer.android.com/tools/releases/platform-tools).
- A USB cable that carries data.
- Twenty minutes, and somewhere to write down the recovery key that is not this phone.

### 8.2 Build the APK

```shell
# Windows
.\gradlew.bat assembleRelease

# macOS or Linux
./gradlew assembleRelease
```

Signing is read from `MHR_RELEASE_STORE_FILE`, `MHR_RELEASE_STORE_PASSWORD`,
`MHR_RELEASE_KEY_ALIAS`, `MHR_RELEASE_KEY_PASSWORD`. Keep the keystore outside source control
and keep it forever: the application ID and signing key must not change across upgrades.

> **Use the release APK.** A debug APK deployed from the IDE carries
> `android:testOnly="true"`, and a test-only device owner can be removed with a single
> `adb shell dpm remove-active-admin`. That would undo every protection in this document.
> Never install the app with `adb install -t`.

### 8.3 Enable USB debugging

1. Settings → About phone → tap **Build number** seven times.
2. Settings → System → Developer options → **USB debugging** on.
3. Connect the cable and approve the prompt on the phone.
4. On some OEM builds (Xiaomi, HyperOS) also enable **USB debugging (Security settings)**.

Verify:

```shell
adb devices
# expect exactly one line ending in "device", not "unauthorized"
```

### 8.4 Provision — Route A, keeps your data

This is the normal route. It does **not** wipe the phone.

**1. Remove every account.** Settings → Passwords & accounts → remove all of them. That includes
Google and any OEM account (Samsung, Xiaomi, Huawei). Check what is left:

```shell
adb shell dumpsys account | grep -i "Account {"
# expect no output
```

**2. Remove every extra user and the guest session.** Settings → System → Multiple users.

```shell
adb shell pm list users
# expect exactly one: UserInfo{0:Owner:...}
```

**3. Install and provision.**

```shell
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell dpm set-device-owner com.example.voward/.MyDeviceAdminReceiver
```

Expected output:

```text
Success: Device owner set to package ComponentInfo{com.example.voward/com.example.voward.MyDeviceAdminReceiver}
Active admin set to component {com.example.voward/com.example.voward.MyDeviceAdminReceiver}
```

**4. Verify.**

```shell
adb shell dumpsys device_policy | head -40
# expect a "Device Owner:" block naming com.example.voward
```

**5. Sign your accounts back in.** Settings → Passwords & accounts. Do this before activating
protection, because adding accounts gets harder afterwards.

### 8.5 Provision — Route B, factory reset

Use this only if Route A fails, or on a phone you are dedicating to this.

1. Back up anything you want to keep.
2. Settings → System → Reset → Erase all data.
3. On the setup wizard, **skip Wi-Fi and skip signing in**. Finish setup with no account.
4. Connect to Wi-Fi afterwards, enable USB debugging, then run the Route A commands from
   step 3.

### 8.6 Optional grants

Neither is required, and both are one-time.

```shell
# Grayscale during approved sessions
adb shell pm grant com.example.voward android.permission.WRITE_SECURE_SETTINGS

# Accurate session metering
adb shell appops set com.example.voward GET_USAGE_STATS allow
```

Usage access can also be granted without a computer:
Settings → Apps → Special app access → Usage access → Voward.

### 8.7 Set up in the app

1. Open Voward and read the disclosure.
2. Set your goal, daily allowance, default session, base pause, and growth. Tap the **ⓘ** on
   anything you are unsure about.
3. Add at least one app or website rule. Mark a rule strict only if it should stay closed for
   the whole protection period.
4. Confirm the approved-browser list. Run **Test this browser** for anything not already
   approved.
5. Choose the enforcement options in section 7.2. The defaults are deliberately conservative:
   USB debugging stays on, factory reset stays available, FRP stays off.
6. Create the recovery key. **Write it down somewhere that is not this phone.**
7. Choose the cooldown, the confirmation window, and the dead-man's switch interval.
8. Review the summary and activate.

### 8.8 Verify protection actually works

Before you rely on it:

| Test | Expected |
| --- | --- |
| Open a strict app | System dialog, not the app |
| Open a blocked site in the approved browser | `ERR_BLOCKED_BY_ADMINISTRATOR` |
| Open the same site in a private/incognito window | Same block |
| Install Firefox from Play | Installs, then becomes a paused icon |
| Settings → Apps → Voward | Uninstall and Force stop unavailable |
| Reboot | Everything still enforced, before you open the app |

### 8.9 Getting out

| Situation | Action |
| --- | --- |
| Normal | Settings → Deactivate protection. Key, cooldown, confirmation. |
| App will not open | `adb shell am start -n com.example.voward/.ReleaseActivity`, then the key |
| Key lost, app working | Wait out the dead-man's switch |
| Everything broken | Recovery-mode factory reset. Power off, then the hardware key combination for your device. |

To remove Voward completely, deactivate first, then **Remove Voward** in Settings — that calls
`clearDeviceOwnerApp()` and makes a normal uninstall possible.

### 8.10 Troubleshooting

| Message | Cause | Fix |
| --- | --- | --- |
| `...because there are already some accounts on the device` | An account is still present, often an OEM one | Remove every account, re-check with `dumpsys account` |
| `...because there are already several users on the device` | A secondary user or guest exists | Remove them, re-check with `pm list users` |
| `Trying to set the device owner, but device owner is already set` | Another DPC is provisioned | Remove it, or use Route B |
| `Unknown admin: ComponentInfo{...}` | Wrong component name, or the app is not installed | Confirm the receiver name in `AndroidManifest.xml` |
| `SecurityException: ... MANAGE_DEVICE_ADMINS` | Command not run from the ADB shell | Use `adb shell dpm ...`, not an on-device terminal |
| Command appears to succeed but `dumpsys device_policy` is empty | OEM restriction | Enable USB debugging (Security settings), or use Route B |
| Website rules do nothing in one browser | That browser ignores managed configuration | Run **Test this browser**; if it fails, the browser gets paused instead |

---

## 9. Implementation phases

Each phase is independently shippable and independently valuable.

**Phase 1 — correctness fixes, no new permissions. Done.**

- ~~`SafetyPolicy.isCriticalPackage`~~ Resolves the dialer, emergency dialer, telecom, Settings
  and permission-controller packages through `PackageManager` and matches them exactly. The
  `.dialer` / `.telecom` / `contains("emergency")` matching is gone. Verified on the Samsung
  test device: `com.samsung.android.dialer`, `com.android.server.telecom`, `com.android.phone`
  and `com.google.android.permissioncontroller` all resolve and are all visible to the app.
- ~~`isTransientSystemOverlay` and `isLauncherPackage`~~ Now match the resolved IME, launcher
  and system-overlay sets. `isSystemUiOverlay` is deleted. The sets refresh on
  `PACKAGE_ADDED` / `REMOVED` / `REPLACED` / `CHANGED`, so exact matching cannot go stale when
  a keyboard or launcher is installed.
- ~~`isKnownSafeNewTab`~~ Exact match against a set of scheme-qualified addresses.
- ~~`AttentionBudgetEngine`~~ A wall-clock day change is only paid for when
  `elapsedRealtime` accounts for it, within `DeactivationPolicyEngine.CLOCK_SHIFT_TOLERANCE_MS`.
  A reboot makes the gap unmeasurable, so the grant is capped at one day rather than refused.
- `PolicyTest`, `ExampleUnitTest` and `AttentionBudgetEngineTest` updated. 102 tests pass.
  `UrlPatternMatcherTest` needed no change; the matcher was not touched.

Residual: with a reboot in between, a forward clock change still buys one day's allowance.
`DISALLOW_CONFIG_DATE_TIME` in Phase 4 closes it; nothing in Phase 1 can.

**Phase 2 — Device Owner core, no behaviour change. Done.**

- ~~`PolicyReconciler` and `EnforcementState`~~ `PolicyReconciler` is the only class that calls
  `DevicePolicyManager` to set anything. `EnforcementState` is the single immutable description
  of the desired device state, and reconciling is idempotent, so a pass interrupted by a crash
  heals on the next boot or tick.
- ~~Provisioning detection~~ `isDeviceOwnerApp`, checked before anything is applied. Not
  provisioned means the reconciler reports that it applied nothing; it never half-applies.
- ~~The release path~~ Release runs in the fixed section 6.2 order and logs each step. Factory
  reset protection is cleared first even though nothing sets it yet, because forgetting that
  step later is the one mistake that cannot be undone from the device.
- ~~The standalone release activity~~ `ReleaseActivity`, reachable with
  `adb shell am start -n com.example.voward/.ReleaseActivity`. See the two changes in 6.3.
- ~~The dead-man's switch~~ `DeadMansSwitchPolicy`, default 14 days. It counts by whichever
  clock has moved further, so neither a clock rollback nor a reboot holds it open. Its
  threshold has storage and a setter; the setting UI lands with Phase 6.
- ~~State mirrored into `setApplicationRestrictions(admin, self, …)`~~ The mirror is what
  reconcile diffs against, so it is the record that survives `pm clear`.
- ~~Boot receiver and the periodic reconcile job~~ `BootCompletedReceiver` plus a persisted
  15-minute `JobScheduler` job.
- `RecoveryKeyHash` was extracted from `AppPreferencesManagerSingleton` unchanged so the
  release screen can check a key without loading the preferences singleton. Behaviour is
  identical and the existing PBKDF2 and legacy-upgrade tests still cover it.

Verified on device: the release screen launches from the adb shell UID, reads protection state
correctly, and rejects a wrong key off the main thread. Not verified: the boot receiver and the
job, because `BOOT_COMPLETED` cannot be sent from the shell and the job needs an activated
install. Four read-only `isAdminActive` calls remain outside the reconciler; they set no policy
and they go with the Device Admin guard in Phase 4.

Not yet in the reconciler: browser managed configuration. It arrives with its release step in
Phase 5.

**Phase 3 — app rules move to suspension. Done.** Accessibility stage B.

- Strict app rules are hidden, regular app rules suspended, computed by
  `EnforcementState.desiredFor` and applied only through the reconciler. Critical packages are
  filtered again on the way out: rules are screened when added, but suspending the dialer is
  not a mistake worth risking twice.
- An approved session lifts the suspension on exactly one package. The deadline is an exact
  alarm (`SessionDeadlineReceiver`), so a session ends on time even if Voward was killed the
  moment after it started. Where exact alarms are unavailable the alarm degrades to inexact and
  the 15-minute reconcile catches an overrun.
- Metering is `UsageMeter`, pulled once at session end over the session's own window. The user
  is charged for foreground time actually spent in the app, not for wall time. Without usage
  access there is nothing to measure, so the quoted duration is charged in full; a **Usage
  access** row in Settings → Permissions opens the system screen.
- `SuspendedAppDetailsActivity` handles `SHOW_SUSPENDED_APP_DETAILS`, so the system's
  "paused by your admin" dialog leads back to the decision gate instead of a dead end. Verified
  registered and resolvable on device; whether the OEM dialog surfaces the button needs a
  provisioned test.
- Accessibility app interception is gated behind `isEnforcedByDeviceOwner()`, re-read at most
  once a minute. Unprovisioned installs keep the existing path exactly, per 3.1.

**Two corrections to this phase as written:**

- **The four polling loops cannot go yet.** 3.1 requires unprovisioned installs to keep working,
  and two of the loops serve the uninstall guard and browser URL enforcement, which are still
  accessibility-based until stages C and D. They go with the service in Phase 7.
- **A browser that is also an app rule loses URL interception while provisioned.** Otherwise the
  same approved session is gated twice and charged twice — once by the managed session, once by
  the accessibility URL path. Its URL rules return in Phase 5 as `URLBlocklist`, which needs no
  session of its own.

**Phase 4 — tamper restrictions. Done.** Accessibility stage C.

- ~~`TamperPolicy`~~ The closed catalogue of every `DISALLOW_*` key Voward can set, and the only
  place one is named. Five toggles: block safe mode, block extra users and profiles, lock date
  and time, block factory reset in Settings, disable USB debugging. The first three default on,
  the last two default off.
- ~~Uninstall, force-stop and clear data~~ `setUninstallBlocked` plus
  `setUserControlDisabledPackages` on Voward's own package, both driven by the existing
  uninstall-guard toggle.
- ~~The clock~~ `DISALLOW_CONFIG_DATE_TIME` plus `setAutoTimeEnabled`. This is what closes the
  Phase 1 residual: a forward clock jump can no longer buy an allowance day. Release lifts the
  requirement but does not switch network time back off — leaving the clock correct costs the
  user nothing, and taking it away again is a change they never asked for.
- ~~Stage C gating~~ `UninstallGuardPolicy` and its watchdog no longer run when provisioned.
  PackageManager refuses the uninstall and Settings refuses the force-stop outright, and two
  layers racing on the same Settings screen is worse than either one alone.
- ~~The four read-only `isAdminActive` calls~~ `isGuardReady` reports ready on provisioning
  alone, and `configureGuard` stops asking for Device Admin when the device owner already
  covers it. The Device Admin path stays for unprovisioned installs, per 3.1.
- Portable configuration is schema 7. The five toggles export and import; a file from an older
  schema takes the defaults above rather than arriving unset.

**Three decisions taken here that the section 7.2 copy does not settle:**

- **`DISALLOW_UNINSTALL_APPS` is not used.** It would stop the user removing *any* app.
  Protecting Voward is `setUninstallBlocked` on one package, which is what the toggle promises.
- **Factory Reset Protection is not implemented.** 6.4 requires it be anchored to an account the
  user has just proved they can sign into, and that proof is a setup flow that does not exist
  yet. A half-safe FRP is precisely the trap 6.4 is about, so the reconciler still only ever
  clears it. It lands with the setup rewrite in Phase 7.
- **Turning USB debugging off asks first.** It is the only toggle here that closes an escape
  route rather than a bypass, and Phase 6 is where its info sheet arrives. A bare switch in the
  meantime would be a trap, so it carries a confirmation dialog of its own.

Residual: on API 26 and 27 `DISALLOW_CONFIG_DATE_TIME` is not a key the platform knows, so the
clock guard there rests on automatic time alone. Nothing crashes and the toggle still does most
of its job; the test device is API 30.

**Phase 5 — browser control. Done.** Accessibility stage D.

- ~~Detection~~ `BrowserPolicy.installedBrowsers` resolves browsable `http`, and the 33
  hard-coded `<package>` entries in the manifest are replaced by two `<queries><intent>`
  declarations of the same shape. The old list could not see a browser released after it was
  written, which is the whole population that matters.
- ~~Probe~~ `getManifestRestrictions` reports whether a browser declares `URLBlocklist`.
- ~~Test flow~~ `BrowserCheckActivity` lists every installed browser with its status, pushes one
  sentinel rule to the browser under test, opens it at that address and asks what happened. A
  browser that declares nothing is unknown, not unsupported, so the verdict is the user's.
  A verdict is forgotten when that browser is updated, unless it declares the key.
- ~~Rule translation~~ `BrowserPolicy.translate`. The grammars overlap almost exactly, so the
  translation is a pass-through with one exception.
- ~~`keyword:` migration~~ The exception. `URLBlocklist` matches URLs and `keyword:` matches page
  text, so those rules stop being enforced. Every reconcile records which ones, and a banner on
  the rules screen names them and says what to replace them with.
- ~~Imposition~~ Filterable browsers get the policy; unfilterable ones are suspended, not hidden.
- ~~Session model~~ A website session lifts one rule out of `URLBlocklist` for its duration,
  which is the URL analogue of unsuspending a package. Strict rules are never lifted. The
  browser package is still what the meter watches.
- ~~Stage D gating~~ The accessibility URL path is skipped for any browser the mirror says is
  being filtered. Read from the mirror and not from provisioning, because a phone with no
  filterable browser gets no policy at all and must keep the old path.

**Three corrections to this phase as written:**

- **`addPersistentPreferredActivity` is not used.** 4.6 wants `http` pinned to the approved
  browser to close the gap before an unapproved one is suspended. Suspension is permanent state,
  so there is no gap to close, and pinning the default browser contradicts the standing
  requirement that the user can use any browser they like.
- **Unfilterable browsers are only suspended when website rules exist.** With no website rules
  there is nothing to escape to, so pausing a browser would be a restriction nobody asked for.
- **The guard rail is checked after the app rules, not before.** 4.7 says never suspend the
  approved browser, but an explicit app rule on a browser is the decision of the person who
  wrote it. The sweep is skipped instead when no filtering browser would survive those rules,
  which is the outcome 4.7 actually protects.

Not done here: the gate for a blocked website. Chromium shows its own block page, which cannot
link back to Voward, so a user who wants a session for a website has no in-the-moment way to ask
for one. The mechanism is in place and `DecisionGateActivity` handles it; what is missing is an
entry point that starts from the rules list. It belongs with the Phase 6 settings work.

**Phase 6 — settings info buttons. Done.**

- ~~`InfoSheet`~~ One `BottomSheetDialog` per screen, content bound at show time.
- ~~28 info affordances~~ Every setting row and every read-only status row, including the ones
  that only report. 24 dp glyph in a 48 dp touch target, content description "About <setting>"
  built from the info title so the two can never disagree.
- ~~The 7.2 copy~~ In `strings.xml` as `info_<key>_title` and `info_<key>_body`, each body
  leading with the consequence. Where a setting is locked while protection is active, that is
  the first thing its body says.
- ~~Two settings that had copy but no row~~ The dead man switch now has a field in the
  deactivation card, validated against `DeadMansSwitchPolicy` bounds, and provisioning status
  has a permanent row at the head of the device protection card. `setDeadMansSwitchDays` came
  back for it; the earlier audit had deleted it as unreachable, which it was until now.
- ~~Grouping~~ Rows follow 7.2 rather than the order they happened to be written in: block
  uninstall and provisioning status moved out of deactivation timing and into device
  protection, next to the rest of the enforcement toggles.
- `permissions_body` no longer claims the accessibility service is the only enforcement path.

Three settings named in 7.2 still have no info affordance, each for a structural reason:

- **Export and import configuration** are toolbar menu items. A menu item has nowhere to put a
  second control; the copy exists and lands wherever those move if they ever leave the menu.
- **Factory Reset Protection** has no row because Phase 4 did not build it. See there.
**Two gaps closed after Phase 6, both of them things the plan assumed rather than assigned.**

**The new-app quarantine now exists.** 7.2 described it and 11 counted it as the mitigation that
makes an unnamed replacement app cost one prompt rather than nothing, but no phase had ever been
asked to build it. `NewAppQuarantine` sweeps on every reconcile, suspends anything launchable
that appeared since the last look, and the paused-app dialog asks once: keep it, or make it a
rule. Critical packages, existing rules and browsers are all skipped; browsers are the browser
landscape's business. It only acts while protection is active, and it never stops an install.

It is a snapshot diff, not a broadcast. `ACTION_PACKAGE_ADDED` cannot be registered from the
manifest, and the runtime receiver that exists today lives inside the accessibility service,
which Phase 7 removes. So a new app is usable until the next reconcile — boot, a session
boundary, or fifteen minutes. That is a real weakening against 4.6's "neutralised on the
broadcast", and it buys an app that has no rule yet one tick of use.

**Website rules have a way back to the gate.** Phase 5 left the session lift with no entry
point: once the browser enforces the rule it shows its own block page, and that page cannot link
anywhere, so a website rule would quietly have become a hard block. Tapping a rule in the
website list now asks for a session against it, the banner on that screen says so, and strict
rules refuse. The session is metered against the phone default browser when that browser is one
of the filtered ones.

**Phase 7 — the accessibility service is deleted. Done.** Stage E.

Provisioned the Samsung test device first, then verified stages B, C and D on it, then deleted.
The order was the whole point: the old path was only given up once the new one had been watched
working.

- ~~Deleted~~ `AttentionFirewallService` (1,757 lines), `BrowserSupport`,
  `BrowserUrlEnforcementPolicy`, `StaticBlockPageServer`, `UninstallGuardPolicy`,
  `InterceptionPolicy`, both accessibility service XML variants, the manifest service entry,
  the `INTERNET` permission, and 15 test methods across five test classes. **2,797 lines.**
- ~~Setup and settings rewritten around provisioning~~ Activation now requires device owner and
  nothing else. The accessibility permission row is gone; the setup permissions step reports
  provisioning and points at the installation guide. The Device Admin prompt is gone too —
  `setUninstallBlocked` needs no separate admin grant.
- ~~Copy~~ Every string describing what the accessibility service observes is rewritten. Ten
  strings went with the code.
- `UrlPatternMatcher` survives as the rule validator and the `URLBlocklist` translator, as 3
  said it would.

**A third casualty, found only because it was asked about.** The ongoing allowance notification
was posted by the service, so deleting the service silently took it with it — while the app went
on asking for `POST_NOTIFICATIONS` and reporting "Allowance notifications - Ready" for a feature
that no longer existed. `StatusNotifier` restores it without anything resident: it refreshes on
activation, release, both ends of a session, boot and each reconcile tick, which are exactly the
moments the numbers change, because time is only ever spent inside an approved session.

**Two regressions this phase nearly introduced, both caught by the unreferenced-member scan
before the build was called done.** Each had its only caller inside the deleted service:

- **Browser verdicts stopped going stale.** 4.4 requires a re-probe when a browser updates, and
  that was a `PACKAGE_REPLACED` receiver in the service. Replaced by comparing
  `PackageInfo.lastUpdateTime` against the time the verdict was given, checked on every survey.
  Better than the broadcast, which could be missed while the app was not running.
- **Pending deactivation requests stopped being revalidated against the clock.** That was a
  `TIME_CHANGED` receiver in the service. `DeactivationRequestValidator.validate` now runs from
  the reconcile, which fires on boot and every fifteen minutes — well inside any confirmation
  window.

**Phase 8 — performance verification. Measured, two rows outstanding.**

| Check | Result |
| --- | --- |
| Process not resident | **Pass.** `dumpsys activity services com.example.voward` reports nothing. No service component of any kind is left. |
| No wakelocks | **Pass.** Every `voward` hit in `dumpsys power` is a screen on/off history row attributed to the last-used package, not a wakelock. |
| No accessibility client | **Pass.** `enabled_accessibility_services` lists only Bitwarden. |
| Suspend IPC count | **Pass, by construction.** `applySuspension` batches one `setPackagesSuspended` per direction, so two at most, and an idle reconcile makes none — it short-circuits on an unchanged mirror. |
| Reconcile CPU | **Not measured.** A forced job run completes without logging, because a no-op reconcile does no work, but no CPU trace was taken. |
| Battery over 24 h | **Not measured.** Needs a day of ordinary use. |

---

---

## 10. Verify on a real device before committing to the design

Assume none of these.

1. ~~**Brave honours a Device-Owner-set `URLBlocklist`.**~~ **Done** — Brave 1.94.121 on API 30.
   Normal tab, private tab, `pm clear`, and `*` plus `URLAllowlist` all behave. See 4.5.1.
   Repeat on API 35/36 before shipping.
2. ~~**Whether the admin-suspended dialog exposes a button.**~~ **Answered, and the answer is
   no.** Samsung API 30 shows "Can't open this app" with the `setShortSupportMessage` text —
   which does reach that dialog, contrary to the note in 4.6 — but its "Learn more" opens the
   Device Administrator settings page, not `ACTION_SHOW_SUSPENDED_APP_DETAILS`. So the gate is
   not in-the-moment for app rules on this OEM: the user reads why it is paused and goes to
   Voward. `SuspendedAppDetailsActivity` stays for the OEMs that do fire it.
3. `setPackagesSuspended` on a browser mid-session: clean exit or force-close.
4. `DISALLOW_SAFE_BOOT` against the hardware key path, not just the power menu.
5. ~~`dpm set-device-owner` after setup completes~~ **Works on the Samsung A40 (API 30)**, with
   `android:testOnly="true"` on the debug build, zero accounts and one user. Removing the
   leftover TestDPC managed profile was the only blocker. Untested on other OEMs.
6. `setApplicationRestrictions` on Voward's own package surviving `pm clear` — untestable now
   that `pm clear` is refused outright, which is a stronger guarantee than the one it was
   standing in for.
7. ~~`adb shell pm clear com.example.voward`~~ **Refused**, before any policy is applied:
   `SecurityException: Cannot clear data for a protected package`. `am force-stop` is refused
   too. Being the device owner is enough on its own, so the old plan's K2 is closed.
8. `setUserControlDisabledPackages` on OEM skins with aggressive battery managers.
9. ~~`RestrictionsManager.getManifestRestrictions()` returning `URLBlocklist`~~ **Works from
   inside Voward**, not only from TestDPC. On the Samsung test device the browsable-`http`
   query resolves Brave and the probe reads `URLBlocklist` out of its manifest, so detection
   and the capability probe are both verified unprovisioned. Vivaldi, Kiwi, Cromite and Opera
   are still unknown; the "test this browser" flow covers them without a code change.

---

## 11. Residual risk

Documented, not fixed:

- Recovery-mode factory reset and bootloader flash. Unclosable, and deliberately so — it is the
  last rung of the escape ladder.
- Root. Detect and record; do not try to win.
- WebViews inside permitted apps, and web-wrapper apps that do not register as browsers.
- A second device.
- A new app serving the same content under a package no rule names. The new-app quarantine makes
  this cost one prompt, not zero — but it is a snapshot diff on a fifteen-minute reconcile, not
  an install broadcast, so there is a window in which the new app runs unpaused.

The plan reduces the cheapest bypass from *one ADB command or one reboot* to *a full recovery
wipe*. That is the achievable ceiling for an app the user installs on their own unrooted phone.
