# Modern Habit Rewire: Deep Dive Analysis

## 1. Project Overview
**Modern Habit Rewire** is a sophisticated Android-based behavioral intervention tool designed to combat digital compulsion—specifically targeting "extractive" apps and mindless browsing. Unlike simple site blockers, it implements a dynamic **"Economic Physics"** model that treats attention and dopamine as finite resources governed by mathematical decay and escalation.

---

## 2. User Guide: How to Rewire Your Habits

### Initial Setup
1.  **Permissions:** Upon first launch, the app will request **Accessibility Service** (to monitor apps/URLs), **Device Admin** (to prevent uninstallation), and **Notification** permissions. All are required for the "Physics" to work.
2.  **The Lists:** Go to the "Edit Lists" section to define your **"Extractive Apps"** (e.g., Social Media, Video Apps) and **"Forbidden URLs"** (e.g., news sites, infinite-scroll domains).
3.  **Set Your Budget:** Define your **Daily Allowance Units**. Think of these as seconds of "high-intensity" attention. A good starting point is 1800 (30 minutes).
4.  **Set the Key:** Create a **Deactivation Key**. This is your "Self-Binding Contract." Once you activate the blocker, you cannot change settings or deactivate without this key.

### Daily Interaction
*   **The Decision Gate:** When you open a forbidden app, you'll see the **Awareness Mirror**. It shows your current session count and how "expensive" your time has become. You must wait for the countdown before you can proceed.
*   **The Notification:** A persistent notification keeps you informed of your remaining budget and your current "multiplier" (how fast you are spending units) in real-time.
*   **The Grayscale Shift:** Depending on your configuration, the screen may turn grayscale when entering forbidden zones to reduce the visual "pull" of the content.

### Advanced Concepts
*   **Charging Bypass:** If enabled, you can deactivate the blocker without a key if the phone is plugged into a charger. This allows for "safe" maintenance while physically tethered.
*   **Adaptive Recovery:** If you stay "clean" for a period (e.g., 6–24 hours), the system rewards you by reducing the cost of future sessions.

---

## 3. Psychological Framework

### The Dopamine Economy
The app treats digital consumption as a transaction. By assigning a cost to "extractive" apps, it shifts the behavior from an **automatic/impulsive** system (System 1) to a **deliberative/rational** system (System 2).

### Agency Preservation
The system avoids instant punishment. Early interaction remains affordable, allowing the user to notice, reflect, and disengage without panic. This preserves a sense of control, which is essential for users with compulsive or ADHD-driven behavior.

### Implementation Intentions & Friction
The **Decision Gate** creates a "Gap" in the habit loop (Cue -> Craving -> Response -> Reward). By introducing **Interaction Latency** (the countdown timer), it breaks the immediate gratification cycle.

### The Awareness Mirror
By showing the **Compulsion Index** and **Current Multiplier** before launch, the app provides real-time bio-feedback. The user is forced to acknowledge their current state of compulsion before they can proceed.

### Logarithmic Impulse Braking
The wait time at the Decision Gate uses a logarithmic growth model. This provides a strong impulse brake for early violations without escalating into a punitive experience that triggers rage-quitting. By flattening the delay curve, we keep the user within the "Zone of Deliberation" rather than triggering a "Bypass Instinct."

---

## 4. Technical Architecture

### Core Components
*   **`AttentionFirewallService` (Accessibility):** The central nervous system. It monitors window state changes and content changes (URLs) to detect restricted zones. It manages the "Sticky Session" state and provides the real-time notification.
*   **`DopamineBudgetEngine`:** The mathematical core. It calculates costs, multipliers, and remaining "Dopamine Units" (DU) using adaptive depletion logic.
*   **`DecisionGateActivity`:** The friction UI. Intercepts app launches, displays the Awareness Mirror, and enforces the Interaction Latency countdown.
*   **`SystemPhysicsController`:** Manages hardware-accelerated screen overlays (like grayscale) to alter the environment's "reward" signal.
*   **`AppPreferencesManagerSingleton`:** Centralized persistence for daily stats, cost factors, and compulsion tracking.

### Security Mechanisms
1.  **Hard Lock:** Prevents access to system settings and package installers when the blocker is active to stop bypass attempts.
2.  **Uninstaller Protection:** Uses Device Admin and Accessibility hooks to make it significantly harder to remove the app during a moment of low impulse control.
3.  **Live Exhaustion Watchdog:** Continuously checks the budget while a forbidden app is open. If the budget hits zero, the watchdog force-closes the app immediately.

---

## 5. Mathematical Appendix: The "Economic Physics"

The engine uses a non-linear escalation model to ensure that both frequent re-entry and prolonged immersion become increasingly expensive.

### Core Variables
*   **$DU_{rem}$ (Remaining Dopamine Units):** Your daily attention budget.
*   **$C$ (Compulsion Index):** A value between $0.0$ and $1.0$ representing your behavioral signature.
    *   **High $C$:** Few but long sessions. Difficulty disengaging once started ("Rabbit-hole" behavior).
    *   **Low $C$:** Many short sessions. Impulsive checking or high frequency of distraction.
*   **$F_{entry}$ (Entry Multiplier):** The cost of the first second of a session.
*   **$\alpha$ (In-Session Escalation):** The "Gravity" that increases cost-per-second over time.

### The Formulas

#### 1. Entry Multiplier: The "Impulse Toll"
$$F_{entry} = f_0 + (0.5 + 0.5C) \times \text{SessionCount}$$
*   **Logic:** $f_0$ is the base factor (default 1.0). As the session count increases, the entry cost rises. This "toll" is weighted more heavily if your $C$ index is high, punishing the tendency to re-enter apps after long sessions.

#### 2. Instantaneous Multiplier: The "Gravity of Time"
The cost per second ($M(t)$) at time $t$:
$$M(t) = F_{entry} + \alpha \times t$$
$$\alpha = (0.001 + 0.005C) \times \text{GraceMultiplier}$$
*   **Logic:** $\alpha$ determines the "slope" of the cost. If $C$ is high, $\alpha$ is larger, meaning the cost of staying in an app escalates much faster.

#### 3. Total Session Cost: The "Final Bill"
The total DU consumed for a session of duration $T$ seconds:
$$UnitCost = \sum_{i=0}^{T} (F_{entry} + \alpha \times i) \approx T \times F_{entry} + \alpha \frac{T(T-1)}{2}$$
*   **Logic:** This quadratic growth ensures that a 20-minute session isn't just twice as expensive as 10 minutes—it is exponentially more taxing on your budget.

#### 4. Logarithmic Interaction Latency (Wait Time)
The wait time $W$ before allowing access:
$$W = W_{base} \times (1.0 + \log_2(F_{entry}))$$
*   **Logic:** We use a logarithmic scaling based on the current Entry Multiplier ($F_{entry}$). This ensures that while the "Economic Cost" (DU) can grow significantly to prevent long-term depletion, the "Psychological Friction" (wait time) grows sub-linearly. This provides a strong "Impulse Brake" early on but flattens out to prevent triggering frustration-driven bypass behavior or emotional overload.

#### 5. Recovery (Decay): The "Healing Mechanism"
$$H_{threshold} = 24 - 18C$$
*   **Logic:** If the user stays "clean" for $H$ hours where $H > H_{threshold}$, the base factor $f_0$ decreases by the `decayStep`. 
    *   Users with high $C$ ($1.0$) only need 6 hours to start recovery, providing a "shorter loop" for positive reinforcement.
    *   Users with low $C$ ($0.0$) need a full 24 hours of abstinence to reduce their cost factors.

---

## 6. Operational Context & Philosophy

### Typical Day Example
A user opens a forbidden app briefly in the morning; they close it quickly and incur minimal cost. Later, they fall into a "rabbit hole" session that escalates rapidly, consuming most of their remaining budget. Further attempts that day are blocked, encouraging disengagement. Overnight recovery reduces penalties if abstinence is maintained.

### What This Is Not
*   **Not a parental control app:** It is a tool for voluntary self-regulation.
*   **Not a productivity timer:** It regulates the "cost" of extraction, not the "output" of work.
*   **Not a moral judgment system:** The cost is a physical property of the digital environment.

### Summary
**Modern Habit Rewire** acts as a **Digital Environment Simulator**. It re-introduces "scarcity" into a world designed to be infinite and frictionless. By making digital dopamine "expensive" and "heavy," it trains the brain to naturally prioritize more intentional and fulfilling activities.
