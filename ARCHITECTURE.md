# Agent Relay - Architecture

## Overview
Android app that uses LLM APIs (Claude/OpenAI/Gemini) to automate device actions via accessibility service + screen capture. The agent captures the screen, extracts a structured element map, sends it to an LLM for planning, then executes the returned actions via accessibility gestures.

## Data Flow
```
User Task (OverlayWindow / ADB broadcast)
  → AgentOrchestrator.startTask()
  → Agent Loop (up to 50 iterations):
      1. ScreenCaptureService.captureScreenshot()     → JPEG bitmap
      2. AccessibilityTreeExtractor.extract()          → UI element tree
      3. OCRClient.recognizeText() [optional]          → visual text blocks
      4. ElementMapGenerator.generate()                → merged element map
      5. LLMClient.sendWithElementMap()                → SemanticActionPlan
      6. executeSemanticStep()                         → accessibility gestures
      7. VerificationClient.verify()                   → confirm action effect
      8. Repeat until COMPLETE or max iterations
```

## Dual-Agent System: Planning Agent + Fast Agent

### Component Roles

| Component | Model | Role | Latency |
|-----------|-------|------|---------|
| **Fast Agent** | Haiku/Sonnet (user-selected) | Per-iteration action planning: receives screenshot + element map + device context, outputs a `SemanticActionPlan` (list of tap/type/swipe steps) | ~2-4s |
| **Planning Agent** | Opus (user-selected) | High-level strategy: analyzes the task, installed apps, and screen state to produce 2-3 `ApproachStrategy` objects with step summaries and confidence scores | ~10-25s |
| **Verification Client** | Haiku (hardcoded) | Pre-execution safety check: compares element map before/after planning to detect stale UI | ~1-2s |
| **Progress Checker** | Same as Fast Agent | Evaluates whether the agent is making meaningful progress toward the task | ~1-2s |
| **Completion Verifier** | Same as Fast Agent | Validates whether the task is actually done when the fast agent claims "complete" | ~2-3s |

### Interaction Timeline

```
Iter  Fast Agent                          Planning Agent                    Data Flow
────  ──────────────────────────────────  ────────────────────────────────  ────────────────
1     Captures screenshot + element map   planInitial() launched async      screenshot, elementMap, task,
      Calls sendWithElementMap()          (running in background)           installedApps → planning
      Executes steps (NO guidance yet)

2     Checks pendingPlanJob: not done     Still computing...
      Acts based on raw task only

3     Checks pendingPlanJob: DONE         Returns PlanningResult            PlanningResult → enhancedTask,
      Injects guidance into prompt                                          pinned conversation msg
      Acts WITH strategic guidance

4-6   Normal execution with guidance      Idle

7     Progress check: NOT progressing     pendingRecoveryPlanJob launched   failureContext, currentPlan,
      Fast agent continues acting         (async in background)             screenshot → recovery planning

8     Recovery job: DONE                  New PlanningResult                New guidance → enhancedTask,
      Adopts new strategy                                                   pinned msg updated

...   Normal execution                    Idle until next trigger

45+   Last-resort planning (BLOCKING)     planRecovery() synchronous        Full failure history → final strategy
```

### Interaction Trigger Points

There are **5 moments** when the Planning Agent and Fast Agent interact:

#### 1. Initial Planning (Iteration 1 — Async, Non-Blocking)

**Trigger**: First iteration of the agent loop, after first screenshot is captured.

**What Planning Agent receives** (via `PlanningAgent.planInitial`):
- `task`: Raw user task string (e.g. "find seville oranges near me")
- `screenshotInfo`: Base64 JPEG screenshot (if screenshots enabled)
- `elementMapText`: Text representation of accessibility tree elements
- `installedApps`: List of `AppInfo(name, packageName)` from `DeviceContextCache`

**What it returns** (`PlanningResult`):
- `approaches`: 2-3 `ApproachStrategy(name, description, stepsSummary, confidence)` objects
- `recommendedIndex`: Which approach to try first
- `splitScreen`: Optional `SplitScreenRecommendation` for parallel two-app execution

**How the fast agent consumes it**: The PlanningResult is checked at the TOP of each iteration. When available, it's injected into the fast agent's prompt in two ways:

1. **Pinned conversation history message** — `conversationHistory[0]` is updated to:
   ```
   TASK: {task}

   STRATEGIC GUIDANCE (from planning agent):
   Recommended approach: {name}
   {description}
   Steps:
     1. {step1}
     2. {step2}
   Alternative approaches if this fails:
   - {altName}: {altDescription}
   ```
   This message survives conversation trimming and is always the first thing the fast agent sees.

2. **`enhancedTask` parameter** — The same guidance is prepended to the `userTask` parameter passed to `LLMClient.sendWithElementMap()`, which places it in the system prompt after the element map and before the behavioral rules.

**Critical behavior**: The fast agent does NOT wait. It starts acting on iteration 1 using only the screenshot, element map, and raw task. This means the first 1-3 iterations run without strategic guidance — the fast agent uses its own judgment.

#### 2. Recovery Planning — Progress Stall (Every 3 Iterations — Async)

**Trigger**: `iterationsSinceLastComplete >= 3 && iteration - lastProgressCheckIteration >= 3`

**Two-phase process**:
1. `checkProgressWithHaiku()` asks the FAST model: "Given the task, current screen, and last 5 actions, is the agent making meaningful progress?" Returns `(progressing: Boolean, reason: String)`.
2. If NOT progressing → `pendingRecoveryPlanJob` is launched asynchronously.

**What Planning Agent receives** (via `PlanningAgent.planRecovery`):
- `task`: Original user task
- `screenshotInfo`: Current screenshot (captured on Main thread, then passed to IO)
- `elementMapText`: Current element map
- `failureContext`: List of failure description strings accumulated so far, e.g.:
  - `"Progress check (iter 8): Agent clicking same element repeatedly"`
  - `"Step 'Tap search button' failed twice. First: Element not found."`
  - `"Element map unchanged 4x, pressed back to try different path"`
  - `"Screen structure stagnant: same layout seen 3 times in last 10 iterations"`
- `previousPlan`: The current `PlanningResult` (so it knows what already failed)
- `installedApps`: Full installed apps list

**How fast agent consumes it**: Same as initial planning — checked at iteration top, injected into pinned message + `enhancedTask`. The `failuresSinceLastPlan` counter resets to 0.

#### 3. Recovery Planning — Step Failures (3+ Failures — Async)

**Trigger**: `failuresSinceLastPlan >= 3 && iteration - lastPlanConsultIteration >= 3` inside `handleStepFailure()`

**Context**: A specific step failed twice (original attempt + retry with fresh element map). After accumulating 3+ such failures since the last plan, recovery planning is launched.

**Same data flow as #2**, but triggered from within step execution rather than the progress checker. The `failureContext` will contain more specific step-level failure details.

#### 4. Wrong-Task Detection → Fast-Track Re-Plan

**Trigger**: Fast agent claims "complete" but completion verification says it's not done, AND the reason contains keywords like "wrong", "different", "instead of".

**Mechanism**: `failuresSinceLastPlan += 3` (instead of the normal `+= 1`), which causes the threshold (`>= 3`) to be hit immediately, triggering recovery planning on the very next failure.

**No direct planning call** — this works by accelerating the failure counter to trigger mechanism #3 sooner.

#### 5. Last-Resort Planning (Final 5 Iterations — Blocking)

**Trigger**: `iteration >= maxIterations - 5 && failuresSinceLastPlan > 0 && !lastResortConsulted`

**This is the only BLOCKING planning call.** The orchestrator waits for the result because there are only a few iterations left and acting without guidance would be wasteful. Same data flow as recovery planning.

### Information Flow Summary

#### Planning Agent → Fast Agent

| Data | How it arrives |
|------|---------------|
| Recommended approach (name + description + steps) | Prepended to `enhancedTask` via `PlanningResult.toGuidanceText()` |
| Alternative approaches | Listed in `toGuidanceText()` under "Alternative approaches if this fails" |
| Root-cause diagnosis (recovery only) | Included in `toGuidanceText()` as "Diagnosis: ..." |
| Split-screen recommendation | Consumed by `SplitScreenCoordinator` directly — not passed to fast agent |

**Format of `toGuidanceText()`**:
```
STRATEGIC GUIDANCE (from planning agent):
Recommended approach: Use Google Maps search
Navigate directly to Google Maps and use the search bar to find "seville oranges near me"
Steps:
  1. Open Google Maps
  2. Tap the search bar
  3. Type the query
  4. Review results

Alternative approaches if this fails:
- Browser search: Open Chrome and navigate to google.com/maps

Diagnosis: The agent was clicking on yogurt search results instead of searching for oranges
```

#### Fast Agent → Planning Agent

| Data | How it arrives |
|------|---------------|
| Failure history | `failureContext: List<String>` accumulated by orchestrator, passed as parameter |
| Previous strategy | `previousPlan: PlanningResult?` reference passed as parameter |
| Current screen state | `screenshotInfo` + `elementMapText` passed as parameters |
| Installed apps | `installedApps: List<AppInfo>` passed as parameter |

The planning agent has NO access to:
- The fast agent's conversation history
- The fast agent's action history (only failure summaries)
- The element diff between iterations
- The fast agent's confidence levels or reasoning

#### Fast Agent Self-Feedback (via Orchestrator)

| Data | Mechanism |
|------|-----------|
| Action loop detection | `buildActionHistoryContext()` → appended to `enhancedTask` with diagnostic instructions |
| Step verification failures | `conversationHistory.add(...)` with "WARNING: step didn't work" messages |
| Completion verification failures | `conversationHistory.add(...)` with "NOT done, keep working" messages |
| Element diff (what changed) | `computeElementDiff()` in system prompt via `LLMClient.sendWithElementMap` |
| Failure context | Last 3 failure strings appended to `enhancedTask` under "CONTEXT - Previous issues" |

## Core Files

| File | Purpose |
|------|---------|
| `AgentOrchestrator.kt` | Central agent loop: capture → plan → execute → verify |
| `AutomationService.kt` | AccessibilityService for gesture execution + device context |
| `ScreenCaptureService.kt` | Foreground service managing MediaProjection + VirtualDisplay |
| `MainActivity.kt` | Jetpack Compose UI (settings, task input, activity log) |
| `LLMClient.kt` | Abstract base for LLM calls; builds automation prompts |
| `ClaudeAPIClient.kt` | Anthropic Claude API adapter |
| `OpenAIClient.kt` | OpenAI GPT API adapter |
| `GeminiClient.kt` | Google Gemini API adapter |
| `PlanningAgent.kt` | Background task decomposition via thinking model |
| `AccessibilityTreeExtractor.kt` | Traverses a11y tree → UIElement list (max 200) |
| `ElementMapGenerator.kt` | Merges a11y + OCR elements, assigns semantic IDs |
| `VerificationClient.kt` | Post-action verification (re-extract tree, check element state) |

## Overlay System

| File | Purpose |
|------|---------|
| `OverlayWindow.kt` | Main control panel (task input, start/stop) |
| `StatusOverlay.kt` | Real-time scrolling status log |
| `FloatingBubble.kt` | Draggable floating bubble for quick access |
| `CursorOverlay.kt` | Visual cursor animating to tap targets |
| `TouchBlockOverlay.kt` | Blocks user touch during agent execution |

## Storage & Caching

| File | Purpose |
|------|---------|
| `SecureStorage.kt` | EncryptedSharedPreferences for API keys + settings |
| `ConversationHistory.kt` | Persists last 50 conversation items + 10 screenshots |
| `DeviceContextCache.kt` | Pre-fetched installed apps list (5-min TTL) |
| `AppKnowledgeCache.kt` | LLM-generated app capability descriptions |

## Supporting Systems

| File | Purpose |
|------|---------|
| `SplitScreenCoordinator.kt` | Parallel agent loops for split-screen tasks |
| `WindowAgentLoop.kt` | Per-window agent loop for split-screen slots |
| `ScreenRecorder.kt` | MP4 recording via MediaRecorder surface swap |
| `intervention/InterventionTracker.kt` | Detects user vs agent gestures |
| `intervention/InterventionDatabase.kt` | Room DB for intervention events |
| `ocr/OCRClient.kt` | Google Vision + Replicate fallback for text recognition |
| `benchmark/BenchmarkReceiver.kt` | ADB-driven task execution for benchmarking |

## Key Patterns
- **Singletons**: AutomationService, ScreenCaptureService, DeviceContextCache, InterventionTracker
- **Coroutines**: Main thread for UI, IO dispatcher for API calls, SupervisorJob for isolation
- **Thread safety**: CopyOnWriteArrayList for listeners, AtomicBoolean for gesture flags
- **Abstract LLM**: LLMClient base → ClaudeAPIClient / OpenAIClient / GeminiClient via LLMClientFactory
- **Two-phase extraction**: Accessibility tree (structured) + OCR (visual) merged by IoU matching

## Device Context Sent to LLM
- Screenshot (base64 JPEG, auto-quality 15-50%)
- Element map (up to 200 elements with IDs, text, bounds, types, interactivity flags)
- Current app name + package
- Country, language, timezone
- Keyboard visibility, active windows
- Installed apps with descriptions
- Conversation history (sliding window of last 12 messages)

## Robustness Features
- Conversation sliding window (keeps pinned task message + last 12 messages)
- Screen structure hash stagnation detection (CRC32 of quantized layout)
- Progress-based planning re-intervention (every 3 stale iterations, async)
- Safety checker directive injection (pattern-matched actionable instructions)
- Hard guard: refuses to start without active MediaProjection
- Same-element loop detection with diagnostic suggestions
- Exact element-map diffing for stuck detection + recovery (keyboard dismiss, scroll, back)
- Wrong-task detection on completion failure (fast-tracks re-planning)
- Parallel step verification (check action took effect while preparing next step)
- Pinned original task in conversation history (never trimmed)

## Build
```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/Users/aayushgupta/Library/Android/sdk \
./gradlew assembleDebug
```
- Target SDK 34, min SDK 24
- Kotlin + Jetpack Compose
- Dependencies: Retrofit2, OkHttp3, Gson, Room, Coroutines, Material3
