# Modern Habit Rewire: Deep Dive Analysis

## 1. Project Overview
**Modern Habit Rewire** is a sophisticated Android-based behavioral intervention tool designed to combat digital compulsion—specifically targeting "extractive" apps and mindless browsing. Unlike simple site blockers, it implements a dynamic **"Economic Physics"** model that treats attention and dopamine as finite resources governed by mathematical decay and escalation.

---

## 2. User Guide: How to Rewire Your Habits

### Initial Setup
1.  **Permissions:** Upon first launch, the app will request **Accessibility Service** (to monitor apps/URLs), **Device Admin** (to prevent uninstallation), and **Notification** permissions. All are required for the "Physics" to work.
2.  **The Lists:** Go to the "Edit Lists" section to define your **"Extractive Apps"** (e.g., Social Media, Video Apps) and **"Forbidden URLs"** (e.g., news sites, infinite-scroll domains).
3.  **Set Your Budget:** Define your **Daily Allowance Units (DU)**. Think of these as seconds of "high-intensity" attention. A good starting point is 1800 (30 minutes).
4.  **Set the Key:** Create a **Deactivation Key**. This is your "Self-Binding Contract." Once you activate the blocker, you cannot change settings or deactivate without this key.

### Daily Interaction
*   **The Decision Gate:** When you open a forbidden app, you'll see the **Awareness Mirror**. It shows your current session count and how "expensive" your time has become. You must wait for the countdown before you can proceed.
*   **Exhaustion Mode:** If your budget is depleted, you enter "Overdraw" mode. You can still proceed, but the wait times are significantly higher, and units are deducted from tomorrow's balance.
*   **Stochastic Friction:** During a session in a forbidden app, the system may occasionally display fullscreen "Friction Overlays"—brief messages or visual breaks—to interrupt the flow. In extreme cases, it may forcibly "kick" you to the Home screen.

### Advanced Concepts
*   **Charging Bypass:** If enabled, you can deactivate the blocker without a key if the phone is plugged into a charger. This allows for "safe" maintenance while physically tethered.
*   **Cumulative Budgeting:** The system uses a carry-over model. If you use less than your allowance, you save units for the next day. If you overdraw, you start the next day with a deficit.

---

## 3. Psychological Framework

### The Dopamine Economy
The app treats digital consumption as a transaction. By assigning a cost to "extractive" apps, it shifts the behavior from an **automatic/impulsive** system (System 1) to a **deliberative/rational** system (System 2).

### Agency Preservation
The system avoids instant punishment. Early interaction remains affordable, allowing the user to notice, reflect, and disengage without panic. This preserves a sense of control, which is essential for users with compulsive or ADHD-driven behavior.

### Stochastic Interruption
By using probabilistic fullscreen overlays ("Pause", "Enough", "Breathe"), the app mimics the internal voice of mindfulness that is often drowned out during "zombie scrolling." The unpredictability of these interruptions prevents the brain from habituating to them.

---

## 4. Technical Architecture

### Core Components
*   **`AttentionFirewallService` (Accessibility):** The central nervous system. It monitors window state changes and URLs. It also manages the **Stochastic Friction** system, which applies fullscreen visual overlays and "Kick to Home" actions based on session intensity.
*   **`UninstallerForbidderAccessibilityService`:** A dedicated security layer that monitors system settings to prevent the uninstallation or forced stopping of the app while the blocker is active.
*   **`DopamineBudgetEngine`:** The mathematical core. It calculates costs, multipliers, and remaining "Dopamine Units" (DU) using adaptive depletion and carry-over logic.
*   **`DecisionGateActivity`:** The friction UI. Intercepts app launches, displays the Awareness Mirror, and enforces the Interaction Latency countdown.
*   **`AppPreferencesManagerSingleton`:** Centralized persistence for daily stats, cost factors, and compulsion tracking.

### Security Mechanisms
1.  **Hard Lock:** Prevents access to system settings and package installers when the blocker is active.
2.  **Uninstaller Protection:** Uses Device Admin and the `UninstallerForbidder` service to make removal significantly harder during low impulse moments.
3.  **Live Exhaustion Watchdog:** Continuously monitors the budget during a session. If the budget hits a critical threshold, it may trigger an immediate gate re-interception.

---

## 5. Mathematical Appendix: The "Economic Physics"

### Core Variables
*   **$DU_{rem}$ (Remaining Dopamine Units):** Your daily attention budget (supports negative values/debt).
*   **$C$ (Compulsion Index):** A value ($0.0$ to $1.0$) representing your behavioral signature (Session Duration / Total Time).
*   **$F_{entry}$ (Entry Multiplier):** The base cost for the start of a session.

### The Formulas

#### 1. Cumulative Reset (The Carry-over)
$$DU_{new} = DU_{remaining} + Allowance$$
*   **Logic:** Unlike a hard reset, this model creates a persistent "financial" relationship with your time.

#### 2. Entry Multiplier: The "Impulse Toll"
$$F_{entry} = f_0 + (0.5 + 0.5C) \times \text{SessionCount}$$
*   **Logic:** $f_0$ is the base factor (min 1.0). Frequent re-entry drives this value up.

#### 3. Instantaneous Multiplier: The "Gravity of Time"
The cost per second ($M(t)$) at time $t$:
$$M(t) = F_{entry} + \alpha \times t$$
$$\alpha = (0.001 + 0.005C) \times \text{GraceMultiplier}$$
*   **Logic:** $\alpha$ escalates cost faster for users with high compulsion (tendency for long sessions).

#### 4. Stochastic Friction Intensity
$$P(friction) = \min(1.0, \text{SessionSeconds} / 60.0)$$
*   **Logic:** As a session exceeds 60 seconds, the probability and intensity of fullscreen "Friction Overlays" and "Kick to Home" actions increase linearly.

---

## 6. Operational Context & Philosophy

### Typical Day Example
A user opens a forbidden app briefly; they incur minimal cost. If they stay too long, the cost per second begins to climb, and they might see a "Breathe" overlay. If they spend all their DU, they can still access apps for emergencies, but the "Withdraw from Tomorrow" gate enforces a long wait, making the choice a heavy one.

### Summary
**Modern Habit Rewire** acts as a **Digital Environment Simulator**. It re-introduces "scarcity" into a world designed to be infinite and frictionless. By making digital dopamine "expensive" and "heavy," it trains the brain to naturally prioritize more intentional and fulfilling activities.
