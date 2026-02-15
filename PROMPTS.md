# Agent Relay - Claude Prompts History

All prompts given to Claude (Code / Warp AI) that shaped this codebase, reconstructed from git history, commit messages, plan files, and implementation logs.

---

## Session 1: Initial MVP (Jan 13, 2026 — Warp AI)

### Prompt 1: Bootstrap the project
> Create an Android app called "Agent Relay" that uses the Claude API to automate device actions via an accessibility service and screen capture. It should capture the screen, send it to Claude, and execute the returned actions (tap, swipe, type, back, home) on the device.

**Result:** Commit `8ae6d99` — "Initial commit w claude code mvp"
- Created full Android project with Kotlin + Jetpack Compose
- `MainActivity.kt`, `ScreenCaptureService.kt`, `AutomationService.kt`, `AgentOrchestrator.kt`, `ClaudeAPIClient.kt`
- Basic agent loop: screenshot → Claude API → parse action → execute via accessibility service

### Prompt 2: Grid overlay and model improvements
> Add a coordinate grid overlay to screenshots so the model can target actions better. Also switch the default model to Gemini 2.0 Flash and Claude Sonnet 4.5, add feedback messages when the screen changes after actions, and persist the status overlay collapse state.

**Result:** Commit `b777b0f` — "Add grid overlay and improve screen capture feedback"
- 100px coordinate grid overlay on screenshots
- Gemini 2.0 Flash + Claude Sonnet 4.5 as default models
- Screen change feedback messages
- Foreground service init fixes
- Status overlay collapse state persistence
- Gemini model options in UI

---

## Session 2: Crash Fixes + UI Redesign (Feb 14, 2026 — Claude Code)

### Prompt 3: Fix crashes and redesign UI
> Fix the crash bugs (SecurityException on Android 14+, EncryptedSharedPreferences crash, deprecated API warnings) and redesign the entire UI to an iOS-style light mode with a bottom tab bar (Agent/Settings), large title navigation, and grouped card settings.

**Result:** Commit `e20b0d3` — "Fix crash bugs and redesign UI to iOS-style light mode"
- Fixed SecurityException: restructured FGS mediaProjection flow (request permission before startForeground)
- Fixed EncryptedSharedPreferences: try-catch with fallback to plain SharedPreferences
- Fixed deprecated APIs: `defaultDisplay.getRealMetrics()` → `windowManager.currentWindowMetrics`; `getParcelableExtra<T>()` → type-safe version
- Fixed thread safety: `CopyOnWriteArrayList` for shared collections
- Full iOS-style UI redesign: light colors, bottom tab bar, large titles, grouped cards
- Added accessibility permission deep link
- Added gradle wrapper and CLAUDE.md

### Prompt 4: Fix import and UI polish
> Fix the OCRClient import path (should be `com.agentrelay.ocr.OCRClient`), fix the activity list expanding upward by removing reverseLayout, add a loading state that polls until the service actually starts/stops, and reduce the status polling interval to 2s.

**Result:** Commit `ed9a1ea` — "Fix OCRClient import path and UI improvements"

### Prompt 5: Persist history and overlay improvements
> Don't clear conversation history when starting a new agent task. Hide the floating bubble when the agent status overlay is active and restore it when the agent stops. Persist the status overlay drag position across hide/show cycles. Improve the Agent tab padding and spacing. Add multi-provider model selection and planning agent support.

**Result:** Commit `a9d33c9` — "Persist activity history, fix overlay UX, and improve layout"

---

## Session 3: Semantic Element-Based Architecture (Feb 14, 2026 — Claude Code)

### Prompt 6: Plan the element-aware OCR pipeline
> Design a new architecture that replaces raw pixel coordinate guessing with a semantic element-based approach. The agent should: (1) extract the accessibility tree for ground-truth UI elements, (2) optionally run OCR for custom-drawn UIs, (3) merge and deduplicate into an element map with stable IDs, (4) send the element map text alongside screenshots to Claude so it references elements by ID instead of coordinates, (5) verify the screen hasn't changed before executing, (6) batch-execute multiple steps.

**Result:** `PLAN.md` created with full 3-phase architecture:
- Phase 1: Screen Analysis (accessibility tree + DeepSeek OCR + merge/dedup)
- Phase 2: Planning (element-ID-based Claude prompt, multi-step plans)
- Phase 3: Verify-then-execute (re-OCR, staleness check, batch execution)

### Prompt 7: Implement the semantic pipeline
> Implement the element-aware semantic pipeline from the plan. Create the data models (UIElement, ElementMap, SemanticStep), AccessibilityTreeExtractor, ElementMapGenerator, OCR pipeline with Google Vision + Replicate fallback, refactor ClaudeAPIClient to use element maps, rewrite AgentOrchestrator for the new loop, add VerificationClient, remove the grid overlay, and add settings UI for OCR/verification toggles.

**Result:** `IMPLEMENTATION_LOG.md` documents full implementation:

**New files created:**
- `AccessibilityTreeExtractor.kt` — Recursive a11y tree traversal, className→ElementType mapping, type-prefixed IDs
- `ElementMapGenerator.kt` — Merge a11y + OCR elements, IoU dedup, relative positions
- `VerificationClient.kt` — Re-extract tree, bounds shift detection, modal overlay detection, Haiku fallback
- `ocr/OCRClient.kt` — Google Vision TEXT_DETECTION + Replicate polling fallback
- `ocr/OCRModels.kt` — Request/response data classes
- `ocr/ColumnDetector.kt` — Horizontal occupancy histogram gap detection

**Files modified:**
- `models/ClaudeModels.kt` — Added UIElement, ElementType, ElementSource, ElementMap, SemanticStep, SemanticActionPlan
- `ClaudeAPIClient.kt` — Added `sendWithElementMap()`, `parseSemanticActionPlan()`, deprecated `sendWithScreenshot`
- `AgentOrchestrator.kt` — Full rewrite: capture → extract tree → OCR → merge → Claude plan → verify → batch-execute
- `AutomationService.kt` — Added `getRootNode()`
- `ScreenCaptureService.kt` — Removed grid overlay (~75 lines)
- `SecureStorage.kt` — OCR/verification API key storage
- `MainActivity.kt` — Settings UI for semantic pipeline + OCR section

---

## Uncommitted Changes (as of Feb 14, 2026)

There are currently staged/unstaged modifications across most of the key files from Session 3 implementation that haven't been committed yet. These represent the semantic pipeline work described above.

---

## Workflow Notes Given to Claude

### Duplicate screenshot handling
> If you ever see the same screenshot as before again, especially with a different one in the middle, then reconsider why that's happening and tell the thinking model to propose a different path before proceeding. Other than that, try to proactively act while the thinking model is thinking.

### Auto-deploy rule
> After every code change, always: (1) Build the debug APK, (2) If an ADB device is connected, install the updated APK onto it, (3) Report the deploy result.
