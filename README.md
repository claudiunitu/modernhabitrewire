# Modern Habit Rewire: Deep Dive Analysis

## 1. Project Overview
**Modern Habit Rewire** is a sophisticated Android-based behavioral intervention tool designed to combat digital compulsion—specifically targeting "extractive" apps and mindless browsing. Unlike simple site blockers, it implements a dynamic **"Economic Physics"** model that treats attention and dopamine as finite resources governed by mathematical decay and escalation.

---

## 2. User Guide: How to Rewire Your Habits

### Initial Setup
1.  **Permissions:** Upon first launch, the app will request **Accessibility Service** (to monitor apps/URLs), **Device Admin** (to prevent uninstallation), and **Notification** permissions. All are required for the "Physics" to work.
2.  **The Lists:** Go to the "Edit Lists" section to define your **"Extractive Apps"** (e.g., Social Media, Video Apps) and **"Forbidden URLs"** (e.g., news sites, infinite-scroll domains).
3.  **Set Your Budget:** Define your **Daily Allowance Units (DU)**. Think of these as seconds of "high-intensity" attention. A good starting point is 1800 (30 minutes).
4.  **Set the Key:** Create a **Deactivation Key**. This is your "Self-Binding Contract." Once you activate the blocker, you cannot change settings or deactivate without this key. The key is stored as a SHA-256 hash—it cannot be read back, only verified.
5.  **Tune the Physics (Optional):** The main screen exposes controls for **Base Wait**, **Cost Factor**, **Recovery Speed**, and **Grace Window**. These let you dial in how aggressive the friction is without touching the source code.

### Daily Interaction
*   **The Decision Gate:** When you open a forbidden app, you'll see the **Awareness Mirror**. It shows your current daily session count, the current cost multiplier, and your remaining DU. You must wait out the countdown before you can proceed. Once granted access, the screen switches to **grayscale** for the entire session as a passive reminder that you are in a forbidden zone.
*   **Budget Exhaustion:** When your DU balance reaches zero or below, the blocker prevents new sessions from starting entirely. The countdown gate is not shown; you are immediately redirected home. Budget is restored the next day via the carry-over reset.
*   **Live Stats Notification:** While the blocker is active, a persistent notification shows remaining DU, the current session's running cost, and the active multiplier in real time.

### Advanced Concepts
*   **Charging Bypass:** If enabled, you can deactivate the blocker without the key if the phone is plugged into a charger. This allows for "safe" maintenance while physically tethered.
*   **Cumulative Budgeting:** The system uses a carry-over model. If you use less than your allowance, you save units for the next day. If you overdraw within a session, you start the next day with a reduced balance.
*   **In-App Help:** Tap the Help menu item from the main screen for a guided explanation of all settings and concepts.

---

## 3. Psychological Framework

### The Dopamine Economy
The app treats digital consumption as a transaction. By assigning a cost to "extractive" apps, it shifts the behavior from an **automatic/impulsive** system (System 1) to a **deliberative/rational** system (System 2).

### Agency Preservation
The system avoids instant punishment. Early interaction remains affordable, allowing the user to notice, reflect, and disengage without panic. This preserves a sense of control, which is essential for users with compulsive or ADHD-driven behavior.

### Recovery Decay
After clean periods (no forbidden app usage), the compulsion index decays automatically, reducing future multipliers. This rewards streaks of self-control with lighter friction going forward.

---

## 4. Technical Architecture

### Core Components
*   **`AttentionFirewallService` (Accessibility):** The central enforcement engine. It monitors window state changes to intercept extractive app launches and inspects browser address bars to catch forbidden URLs. It tracks active sessions with a sticky timer, meters budget depletion against elapsed session time, updates the live stats notification, applies a full-screen grayscale overlay for the duration of each approved session, and terminates a session when the running cost reaches the opening balance.
*   **`UninstallerForbidderAccessibilityService`:** A dedicated security layer that scans Settings screens for this app's info page and backs the user out if the settings-lock switch is on, preventing uninstallation or force-stop while the blocker is active.
*   **`DopamineBudgetEngine`:** The mathematical core. It calculates costs, multipliers, and remaining "Dopamine Units" (DU) using adaptive depletion, carry-over resets, and recovery decay logic.
*   **`DecisionGateActivity`:** The friction UI. Intercepts app launches and forbidden URL navigations, displays the Awareness Mirror (session count, multiplier, remaining DU), and enforces the Interaction Latency countdown before granting access.
*   **`AppPreferencesManagerSingleton`:** Centralized SharedPreferences wrapper for all persistent state: blocker status, forbidden URLs, extractive app packages, budget values, compulsion metrics, decay parameters, and friction counters.
*   **`HelpActivity`:** An in-app help screen explaining all settings and concepts, accessible from the main screen menu.

### Security Mechanisms
1.  **Settings Lock:** A dedicated toggle prevents opening this app's system settings page or the package installer while the blocker is active. Both `AttentionFirewallService` and `UninstallerForbidderAccessibilityService` enforce this independently.
2.  **Uninstaller Protection:** Uses Device Admin (`MyDeviceAdminReceiver`) alongside the `UninstallerForbidder` service to make removal significantly harder during low-impulse moments.
3.  **Hashed Key Storage:** The deactivation key is hashed with SHA-256 before being written to SharedPreferences. The plaintext key is never persisted.
4.  **Live Budget Enforcement:** The session timer is continuously compared against the opening DU balance. When running cost equals the opening balance, the session is terminated and the user is redirected home.

### Dormant / In-Progress Components
The following classes exist in the source but are not wired up in the current build:

*   **`SystemPhysicsController`:** Manages the full-screen grayscale overlay applied during every approved forbidden session. The overlay is activated when a session starts (DU > 0) and removed automatically when the session ends or the service stops.
*   **`ScreenReaderAccessibilityService`:** A legacy browser URL blocker that predates the URL interception logic now built into `AttentionFirewallService`. Not declared in the manifest.
*   **`ChargingState`:** A `BroadcastReceiver` for tracking power connection state. Not declared in the manifest; charging detection is currently handled inline in `MainActivity`.
*   **`friction_overlay.xml`:** Layout asset for a full-screen friction message overlay. Planned for a future stochastic in-session interruption feature.

---

## 5. Mathematical Appendix: The "Economic Physics"

### Core Variables
*   **$DU_{rem}$ (Remaining Dopamine Units):** Your daily attention budget. Cannot go below zero for starting a new session; debt can accumulate within a running session.
*   **$C$ (Compulsion Index):** A value ($0.0$ to $1.0$) representing your behavioral signature (Session Duration / Total Time). Decays after clean periods.
*   **$F_{entry}$ (Entry Multiplier):** The base cost for the start of a session.

### The Formulas

#### 1. Cumulative Reset (The Carry-over)
$$DU_{new} = DU_{remaining} + Allowance$$
*   **Logic:** Unlike a hard reset, this model creates a persistent "financial" relationship with your time. Unused units carry forward; overdrawn units reduce tomorrow's balance.

#### 2. Entry Multiplier: The "Impulse Toll"
$$F_{entry} = f_0 + (0.5 + 0.5C) \times \text{SessionCount}$$
*   **Logic:** $f_0$ is the base factor (min 1.0). Frequent re-entry drives this value up, making each successive return more expensive.

#### 3. Instantaneous Multiplier: The "Gravity of Time"
The cost per second ($M(t)$) at time $t$:
$$M(t) = F_{entry} + \alpha \times t$$
$$\alpha = (0.001 + 0.005C) \times \text{GraceMultiplier}$$
*   **Logic:** $\alpha$ escalates cost faster for users with a high compulsion index (tendency for long sessions). The Grace Window parameter controls how long $\alpha$ stays suppressed at session start.

#### 4. Recovery Decay (Clean Period Reward)
$$C_{new} = C_{current} \times (1 - \text{RecoverySpeed})$$
*   **Logic:** After a day with no forbidden app usage, the compulsion index decays by the configured recovery speed, reducing future multipliers and gate wait times.

---

## 6. Operational Context & Philosophy

### Typical Day Example
A user opens a forbidden app briefly; they incur minimal cost (low multiplier, short duration). If they stay too long, the per-second cost climbs and the session is eventually terminated automatically when the running cost equals their opening balance. Once DU hits zero, the blocker refuses entry entirely until the next daily reset. Clean days reduce the compulsion index, making subsequent days lighter.

### Summary
**Modern Habit Rewire** acts as a **Digital Environment Simulator**. It re-introduces "scarcity" into a world designed to be infinite and frictionless. By making digital dopamine "expensive" and "heavy," it trains the brain to naturally prioritize more intentional and fulfilling activities.
