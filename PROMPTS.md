# Agent Relay Prompt History

Prompts found in tracked repo artifacts for Claude, Cursor, and Codex sessions.

## Scope
- Source files used: `PROMPTS.md`, `PLAN.md`, `IMPLEMENTATION_LOG.md`, `CLAUDE.md`, and git commit history.
- No Cursor/Codex transcript files were found in this repo.
- `.claude/` only contains `settings.local.json` (no prompt logs).

## Claude / Warp Sessions

### Session 1 (Jan 13, 2026 — Warp AI)
1. "Create an Android app called Agent Relay that uses Claude API + accessibility + screen capture to automate tap/swipe/type/back/home."
- Result: `8ae6d99` initialized the MVP app and core loop.

2. "Add coordinate grid overlay, improve model setup/feedback, and persist status overlay collapse state."
- Result: `b777b0f` added grid-based screenshot guidance and UI/state improvements.

### Session 2 (Feb 14, 2026 — Claude Code)
3. "Fix Android 14/mediaProjection + encrypted prefs + deprecated API issues, and redesign UI to iOS-style tabs/cards."
- Result: `e20b0d3` fixed startup/storage issues and shipped the iOS-style UI rewrite.

4. "Fix OCR import path, activity list direction, service start/stop loading state, and 2s status polling."
- Result: `ed9a1ea` applied import and UX/polling fixes.

5. "Keep conversation history across tasks, improve bubble/overlay behavior, persist drag position, improve spacing, add multi-provider + planning model support."
- Result: `a9d33c9` added persistence and overlay/model UX upgrades.

### Session 3 (Feb 14, 2026 — Claude Code)
6. "Design an element-aware architecture: a11y tree + OCR merge, stable IDs, element-map prompting, verification, and batched execution."
- Result: `PLAN.md` captured the 3-phase semantic pipeline design.

7. "Implement the semantic pipeline end-to-end (models, extractor, generator, OCR, planner/refactor, verifier, settings, remove grid overlay)."
- Result: `IMPLEMENTATION_LOG.md` records the implementation across orchestrator, clients, OCR, verification, and settings.

## Additional Prompt-Like Instructions (Claude)
1. "If duplicate screenshots appear, reconsider path and propose alternatives before proceeding."
2. "After each change, build debug APK, install via ADB if device connected, and report deploy result."

## Cursor Sessions
- No Cursor session prompt artifacts found in this repo.

## Codex Sessions
- No Codex session prompt artifacts found in this repo.
