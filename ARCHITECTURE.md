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

## Planning Agent (Thinking Model)
The **PlanningAgent** runs a heavier "thinking" model (e.g. Claude Opus, GPT-4) in parallel with the fast action model. It provides strategic guidance at three points:

1. **Initial planning** — On iteration 1, launched in background with the first screenshot + element map + installed apps. Generates 2-3 ranked approach strategies (e.g. "use Settings app" vs "use notification shade") with confidence scores. The fast model starts acting immediately and adopts the strategy when it arrives.

2. **Recovery planning** — When the fast model accumulates 3+ failures since the last plan, the planning agent is consulted with failure context to suggest an alternative approach.

3. **Progress-based re-intervention** — Every 5 iterations without a COMPLETE signal, a progress check asks the model whether meaningful progress is being made. If not, the planning agent is forced to re-plan.

4. **Last-resort consultation** — In the final 5 iterations before max, a final recovery plan is requested.

The planning agent can also recommend **split-screen mode** (two apps side-by-side with parallel agent loops) for tasks that span multiple apps.

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
- Conversation sliding window (keeps first + last 12 messages)
- Screen structure hash stagnation detection (CRC32 of quantized layout)
- Progress-based planning re-intervention (every 5 stale iterations)
- Safety checker directive injection (pattern-matched actionable instructions)
- Hard guard: refuses to start without active MediaProjection
- Same-element loop detection with diagnostic suggestions
- Exact element-map diffing for stuck detection + recovery (keyboard dismiss, scroll, back)

## Build
```bash
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/Users/aayushgupta/Library/Android/sdk \
./gradlew assembleDebug
```
- Target SDK 34, min SDK 24
- Kotlin + Jetpack Compose
- Dependencies: Retrofit2, OkHttp3, Gson, Room, Coroutines, Material3
