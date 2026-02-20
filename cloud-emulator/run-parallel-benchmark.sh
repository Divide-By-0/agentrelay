#!/bin/bash
# Run benchmark tasks in parallel across multiple cloud emulators.
#
# Usage:
#   ./run-parallel-benchmark.sh --tasks-file tasks.json [--timeout 300] [--output-dir DIR]
#
# Discovers all connected cloud emulators (localhost:56XX), distributes tasks
# round-robin, runs them in parallel, and auto-pulls logs+video for failed tasks.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PACKAGE="com.agentrelay"
RECEIVER="$PACKAGE/$PACKAGE.benchmark.BenchmarkReceiver"
VIDEO_DIR_ON_DEVICE="/sdcard/Movies/AgentRelay"
APK_PATH="$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"
TIMEOUT=300
OUTPUT_DIR="$REPO_ROOT/benchmark_output"
TASKS_FILE=""
FAILURES_DIR=""

usage() {
    echo "Usage: $0 --tasks-file FILE [--timeout SECS] [--output-dir DIR]"
    exit 1
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --tasks-file)  TASKS_FILE="$2"; shift 2 ;;
        --timeout)     TIMEOUT="$2"; shift 2 ;;
        --output-dir)  OUTPUT_DIR="$2"; shift 2 ;;
        -h|--help)     usage ;;
        *)             echo "Unknown arg: $1"; usage ;;
    esac
done

[[ -z "$TASKS_FILE" ]] && usage

log()  { echo "[parallel] $*"; }
err()  { echo "[parallel] ERROR: $*" >&2; }

# ── Discover connected cloud emulators ──
discover_devices() {
    adb devices 2>/dev/null | grep -oE "localhost:5[0-9]+" | sort
}

DEVICES=($(discover_devices))
NUM_DEVICES=${#DEVICES[@]}

if [[ $NUM_DEVICES -eq 0 ]]; then
    err "No cloud emulators connected. Run: ./gcp-emulator.sh connect-all"
    exit 1
fi

log "Found $NUM_DEVICES emulator(s): ${DEVICES[*]}"

# ── Resolve API keys (for re-configuring after fresh installs) ──
SECRETS_XML="$REPO_ROOT/app/src/main/res/values/secrets.xml"
read_secret() {
    local key="$1"
    if [[ -f "$SECRETS_XML" ]]; then
        local val
        val=$(grep "name=\"$key\"" "$SECRETS_XML" 2>/dev/null \
            | sed 's/.*>\(.*\)<\/string>.*/\1/' | tr -d '[:space:]') || true
        echo "$val"
    fi
}
ANTHROPIC_KEY="${ANTHROPIC_API_KEY:-$(read_secret default_claude_api_key)}"
GEMINI_KEY="${GEMINI_API_KEY:-$(read_secret default_gemini_api_key)}"
VISION_KEY="${GOOGLE_VISION_API_KEY:-$(read_secret default_google_vision_api_key)}"
[[ -z "$VISION_KEY" ]] && VISION_KEY="$GEMINI_KEY"  # Fallback: use Gemini key for Vision API

# Build CONFIGURE extras string for re-pushing keys after fresh installs
CONFIGURE_EXTRAS=""
[[ -n "$ANTHROPIC_KEY" ]] && CONFIGURE_EXTRAS="$CONFIGURE_EXTRAS --es api_key $ANTHROPIC_KEY"
[[ -n "$GEMINI_KEY" ]]    && CONFIGURE_EXTRAS="$CONFIGURE_EXTRAS --es gemini_api_key $GEMINI_KEY"
[[ -n "$VISION_KEY" ]]    && CONFIGURE_EXTRAS="$CONFIGURE_EXTRAS --es google_vision_api_key $VISION_KEY"
CONFIGURE_EXTRAS="$CONFIGURE_EXTRAS --es screen_recording true --es send_screenshots AUTO --es ocr_enabled true"

# ── Load tasks ──
TASK_COUNT=$(python3 -c "import json; print(len(json.load(open('$TASKS_FILE'))))")
log "Loaded $TASK_COUNT tasks from $TASKS_FILE"

# ── Setup output directories ──
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
RUN_DIR="$OUTPUT_DIR/parallel_$TIMESTAMP"
FAILURES_DIR="$RUN_DIR/failures"
mkdir -p "$RUN_DIR" "$FAILURES_DIR"

log "=========================================="
log "Parallel Benchmark — $TASK_COUNT tasks × $NUM_DEVICES emulators"
log "Output: $RUN_DIR"
log "Failures: $FAILURES_DIR"
log "Timeout: ${TIMEOUT}s per task"
log "=========================================="

# ── Auto-approve "Start now" dialog if visible ──
# Sets DIALOG_APPROVED=true if found and tapped, false otherwise.
# Does NOT use return codes (to avoid set -e issues in subshells).
auto_approve_dialog() {
    local device="$1"
    DIALOG_APPROVED=false
    local ui
    ui=$(adb -s "$device" shell "uiautomator dump /sdcard/ui.xml 2>/dev/null && cat /sdcard/ui.xml" 2>/dev/null || echo "")
    if echo "$ui" | grep -q "Start now"; then
        local tap_coords
        tap_coords=$(python3 -c "
import re, sys
ui = sys.stdin.read()
# Use .* with re.DOTALL to match across / characters in resource-id attributes
m = re.search(r'text=\"Start now\".*?bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', ui)
if m:
    print(f'{(int(m.group(1))+int(m.group(3)))//2} {(int(m.group(2))+int(m.group(4)))//2}')
else:
    # Default: bottom-right area of standard MediaProjection dialog
    print('827 1669')
" <<< "$ui")
        local tx ty
        tx=$(echo "$tap_coords" | cut -d' ' -f1)
        ty=$(echo "$tap_coords" | cut -d' ' -f2)
        adb -s "$device" shell "input tap $tx $ty" > /dev/null 2>&1 || true
        sleep 2
        DIALOG_APPROVED=true
    elif echo "$ui" | grep -q "MediaProjection\|screen capture\|Screen cast\|recording\|Start recording\|entire screen"; then
        # Dialog is showing but "Start now" text not found — try multiple approaches
        # Approach 1: tap common button locations (bottom-right of dialog where positive button is)
        adb -s "$device" shell "input tap 827 1669" > /dev/null 2>&1 || true
        sleep 1
        adb -s "$device" shell "input tap 854 1550" > /dev/null 2>&1 || true
        sleep 1
        # Approach 2: keyboard navigation (Tab to "Start now" button, then Enter)
        adb -s "$device" shell "input keyevent KEYCODE_TAB" > /dev/null 2>&1 || true
        sleep 0.3
        adb -s "$device" shell "input keyevent KEYCODE_TAB" > /dev/null 2>&1 || true
        sleep 0.3
        adb -s "$device" shell "input keyevent KEYCODE_ENTER" > /dev/null 2>&1 || true
        sleep 2
        DIALOG_APPROVED=true
    fi
    # If nothing matched, try keyboard approach anyway (the dialog might be there but uiautomator missed it)
    if [[ "$DIALOG_APPROVED" == "false" ]]; then
        # Check if any dialog is in the foreground by looking at current activity
        local focused
        focused=$(adb -s "$device" shell "dumpsys activity activities 2>/dev/null | grep 'mResumedActivity'" 2>/dev/null || echo "")
        if echo "$focused" | grep -q "MediaProjection\|ScreenCapture\|screen_capture"; then
            adb -s "$device" shell "input keyevent KEYCODE_TAB && input keyevent KEYCODE_TAB && input keyevent KEYCODE_ENTER" > /dev/null 2>&1 || true
            sleep 2
            DIALOG_APPROVED=true
        fi
    fi
}

# ── Ensure screen capture on a single device ──
# Opens the app, launches ScreenCaptureRequestActivity directly to trigger the
# MediaProjection permission dialog, then auto-taps "Start now".
ensure_screen_capture_device() {
    local device="$1"
    local prefix="[$device]"

    # 1. Open the app to ensure process is alive
    adb -s "$device" shell "am start -n $PACKAGE/.MainActivity" > /dev/null 2>&1 || true
    sleep 2

    # 2. Launch ScreenCaptureRequestActivity directly (no task extra = no task started)
    adb -s "$device" shell "am start -n $PACKAGE/.ScreenCaptureRequestActivity" > /dev/null 2>&1 || true
    sleep 3

    # 3. Auto-approve the "Start now" dialog (retry a couple times)
    local approved=false
    for attempt in 1 2 3; do
        auto_approve_dialog "$device"
        if [[ "$DIALOG_APPROVED" == "true" ]]; then
            echo "$prefix Screen capture approved (attempt $attempt)."
            approved=true
            break
        fi
        sleep 2
    done

    if [[ "$approved" == "false" ]]; then
        echo "$prefix No screen capture dialog found (may already be active)."
    fi

    # 4. Go home
    adb -s "$device" shell "input keyevent KEYCODE_HOME" > /dev/null 2>&1 || true
    sleep 1
}

# ── Run a single task on a device ──
# Writes result to $RUN_DIR/<task_id>_result.json
# On failure, pulls logs + video to $FAILURES_DIR/
run_task() {
    local device="$1"
    local task_idx="$2"

    local task_id task_name task_goal
    task_id=$(python3 -c "import json; print(json.load(open('$TASKS_FILE'))[$task_idx]['id'])")
    task_name=$(python3 -c "import json; print(json.load(open('$TASKS_FILE'))[$task_idx]['name'])")
    task_goal=$(python3 -c "import json; print(json.load(open('$TASKS_FILE'))[$task_idx]['goal'])")

    local prefix="[$device:$task_id]"
    echo "$prefix Starting: $task_name"
    echo "$prefix Goal: $task_goal"

    # Fresh install: uninstall + reinstall app for clean state every task
    echo "$prefix Reinstalling app..."
    adb -s "$device" shell "pm uninstall $PACKAGE" > /dev/null 2>&1 || true
    adb -s "$device" install -r "$APK_PATH" > /dev/null 2>&1 || {
        echo "$prefix WARNING: APK install failed, retrying..."
        sleep 3
        adb -s "$device" install -r "$APK_PATH" > /dev/null 2>&1 || true
    }

    # Enable accessibility service after fresh install
    adb -s "$device" shell "settings put secure enabled_accessibility_services $PACKAGE/$PACKAGE.AutomationService" > /dev/null 2>&1 || true
    adb -s "$device" shell "settings put secure accessibility_enabled 1" > /dev/null 2>&1 || true

    # Re-configure API keys + OCR after fresh install (SecureStorage was wiped)
    adb -s "$device" shell "am start -n $PACKAGE/.MainActivity" > /dev/null 2>&1 || true
    sleep 2
    adb -s "$device" shell "am broadcast -n '$RECEIVER' -a com.agentrelay.benchmark.CONFIGURE $CONFIGURE_EXTRAS" > /dev/null 2>&1 || true
    sleep 1

    # Clean old videos
    adb -s "$device" shell "rm -f $VIDEO_DIR_ON_DEVICE/*.mp4" 2>/dev/null || true

    # Go home first
    adb -s "$device" shell "input keyevent KEYCODE_HOME" > /dev/null 2>&1
    sleep 1

    # Start logcat
    local logcat_file="$RUN_DIR/${task_id}_logcat.txt"
    adb -s "$device" logcat -c 2>/dev/null || true
    adb -s "$device" logcat -v threadtime > "$logcat_file" 2>/dev/null &
    local logcat_pid=$!

    # Start task
    local wall_start
    wall_start=$(date +%s)
    adb -s "$device" shell "am broadcast -n '$RECEIVER' -a com.agentrelay.benchmark.START_TASK --es task_id '$task_id' --es task '$task_goal'" > /dev/null 2>&1

    # Give 5s for BenchmarkReceiver to potentially launch ScreenCaptureRequestActivity
    sleep 5
    # Auto-approve screen capture dialog — retry multiple times with delays
    local sc_approved=false
    for sc_attempt in 1 2 3 4 5 6 7 8; do
        auto_approve_dialog "$device"
        if [[ "$DIALOG_APPROVED" == "true" ]]; then
            echo "$prefix Auto-approved screen capture dialog (attempt $sc_attempt)."
            sc_approved=true
            sleep 2
            break
        fi
        # On attempt 3, explicitly re-launch ScreenCaptureRequestActivity in case the dialog was missed
        if [[ $sc_attempt -eq 3 ]]; then
            adb -s "$device" shell "am start -n $PACKAGE/.ScreenCaptureRequestActivity" > /dev/null 2>&1 || true
            sleep 3
        else
            sleep 2
        fi
    done
    if [[ "$sc_approved" == "false" ]]; then
        # Last resort: force-launch ScreenCaptureRequestActivity and try once more
        echo "$prefix WARNING: Screen capture not approved after 8 attempts, force-launching..."
        adb -s "$device" shell "am start -n $PACKAGE/.ScreenCaptureRequestActivity" > /dev/null 2>&1 || true
        sleep 4
        auto_approve_dialog "$device"
        if [[ "$DIALOG_APPROVED" == "true" ]]; then
            echo "$prefix Auto-approved screen capture dialog (final attempt)."
            sc_approved=true
            sleep 2
        else
            echo "$prefix ERROR: Could not auto-approve screen capture dialog. Task will likely fail."
        fi
    fi

    # Poll for result
    local result=""
    while true; do
        local elapsed=$(( $(date +%s) - wall_start ))
        if [[ $elapsed -ge $TIMEOUT ]]; then
            result="{\"status\":\"timeout\",\"duration_ms\":$((elapsed * 1000)),\"final_message\":\"Timed out after ${TIMEOUT}s\"}"
            break
        fi

        result=$(adb -s "$device" shell "run-as $PACKAGE cat files/benchmark_result.json" 2>/dev/null || echo "")
        if [[ -n "$result" && "$result" != *"No such file"* ]]; then
            break
        fi

        sleep 5
    done

    local wall_end
    wall_end=$(date +%s)
    local wall_secs=$((wall_end - wall_start))

    # Stop logcat
    kill "$logcat_pid" 2>/dev/null || true
    wait "$logcat_pid" 2>/dev/null || true

    # Save result
    local result_file="$RUN_DIR/${task_id}_result.json"
    echo "$result" > "${result_file}.tmp"

    local status
    status=$(python3 -c "import json; print(json.load(open('${result_file}.tmp')).get('status','unknown'))" 2>/dev/null || echo "unknown")
    local duration_ms
    duration_ms=$(python3 -c "import json; print(json.load(open('${result_file}.tmp')).get('duration_ms',0))" 2>/dev/null || echo "0")
    local message
    message=$(python3 -c "import json; print(json.load(open('${result_file}.tmp')).get('final_message',''))" 2>/dev/null || echo "")

    # Enrich result with metadata
    python3 << PYEOF
import json
with open("${result_file}.tmp") as f:
    r = json.load(f)
r['task_id'] = "$task_id"
r['task_name'] = $(python3 -c "import json; print(json.dumps('$task_name'))")
r['goal'] = $(echo "$task_goal" | python3 -c "import json,sys; print(json.dumps(sys.stdin.read().strip()))")
r['device'] = "$device"
r['wall_time_s'] = $wall_secs
with open("$result_file", 'w') as f:
    json.dump(r, f, indent=2)
PYEOF
    /bin/rm -f "${result_file}.tmp"

    if [[ "$status" == "completed" ]]; then
        echo "$prefix ✓ PASSED in ${wall_secs}s — $message"
    else
        echo "$prefix ✗ FAILED ($status) in ${wall_secs}s — $message"

        # Stop the task if it timed out
        if [[ "$status" == "timeout" ]]; then
            adb -s "$device" shell "am broadcast -n '$RECEIVER' -a com.agentrelay.benchmark.STOP_TASK" > /dev/null 2>&1
            sleep 2
        fi

        # ── Pull failure artifacts ──
        local fail_dir="$FAILURES_DIR/$task_id"
        mkdir -p "$fail_dir"

        # Copy result + logcat
        cp "$result_file" "$fail_dir/"
        cp "$logcat_file" "$fail_dir/"

        # Pull video recordings
        local videos
        videos=$(adb -s "$device" shell "ls $VIDEO_DIR_ON_DEVICE/*.mp4 2>/dev/null" 2>/dev/null | tr -d '\r' || true)
        if [[ -n "$videos" ]]; then
            local vi=0
            for video in $videos; do
                adb -s "$device" pull "$video" "$fail_dir/${task_id}_video${vi}.mp4" 2>/dev/null || true
                vi=$((vi + 1))
            done
            echo "$prefix   Pulled $vi video(s) to $fail_dir/"
        fi

        echo "$prefix   Logs + recordings saved to $fail_dir/"
    fi

    # Also pull videos for passed tasks (to $RUN_DIR)
    if [[ "$status" == "completed" ]]; then
        local videos
        videos=$(adb -s "$device" shell "ls $VIDEO_DIR_ON_DEVICE/*.mp4 2>/dev/null" 2>/dev/null | tr -d '\r' || true)
        if [[ -n "$videos" ]]; then
            local vi=0
            for video in $videos; do
                adb -s "$device" pull "$video" "$RUN_DIR/${task_id}_video${vi}.mp4" 2>/dev/null || true
                vi=$((vi + 1))
            done
        fi
    fi
}

# ── Ensure screen capture on all devices (now that functions are defined) ──
log "Ensuring screen capture on all devices..."
for device in "${DEVICES[@]}"; do
    (
        set +e  # disable errexit in subshell
        ensure_screen_capture_device "$device"
    ) &
done
wait
log "Screen capture ready on all devices."

# ── Distribute tasks round-robin across devices ──
# Each device gets a queue; tasks run sequentially per device, in parallel across devices.

PIDS=()

run_device_queue() {
    local device_idx="$1"
    local device="${DEVICES[$device_idx]}"
    shift
    local task_indices=("$@")

    for task_idx in "${task_indices[@]}"; do
        run_task "$device" "$task_idx"
    done
}

# Build per-device task queues (round-robin)
declare -a QUEUES
for ((d = 0; d < NUM_DEVICES; d++)); do
    QUEUES[$d]=""
done

for ((t = 0; t < TASK_COUNT; t++)); do
    local_d=$((t % NUM_DEVICES))
    QUEUES[$local_d]="${QUEUES[$local_d]} $t"
done

log ""
log "Task distribution:"
for ((d = 0; d < NUM_DEVICES; d++)); do
    count=$(echo "${QUEUES[$d]}" | wc -w | tr -d ' ')
    log "  ${DEVICES[$d]}: $count task(s)"
done
log ""
log "Starting parallel execution..."
PARALLEL_START=$(date +%s)

# Launch one background process per device (set +e to prevent errexit killing subshells)
for ((d = 0; d < NUM_DEVICES; d++)); do
    if [[ -n "${QUEUES[$d]}" ]]; then
        ( set +e; run_device_queue "$d" ${QUEUES[$d]} ) &
        PIDS+=($!)
    fi
done

# Wait for all device queues to complete
for pid in "${PIDS[@]}"; do
    wait "$pid" || true
done

PARALLEL_END=$(date +%s)
TOTAL_WALL=$((PARALLEL_END - PARALLEL_START))

# ── Aggregate results ──
log ""
log "=========================================="
log "Aggregating results..."

RUN_DIR_FOR_PY="$RUN_DIR"
python3 << PYEOF
import json, glob, os, sys

run_dir = "$RUN_DIR_FOR_PY"
result_files = sorted(glob.glob(os.path.join(run_dir, "*_result.json")))

results = []
for f in result_files:
    # Skip files in failures subdir
    if "/failures/" in f:
        continue
    try:
        with open(f) as fh:
            results.append(json.load(fh))
    except Exception:
        pass

passed = sum(1 for r in results if r.get("status") == "completed")
failed = len(results) - passed
rate = (passed / len(results) * 100) if results else 0

# Save master results
master = {
    "timestamp": os.path.basename(run_dir),
    "total_tasks": len(results),
    "passed": passed,
    "failed": failed,
    "success_rate": round(rate, 1),
    "results": results,
}
with open(os.path.join(run_dir, "results.json"), "w") as f:
    json.dump(master, f, indent=2)

# Print summary
print()
print("=" * 60)
print(f"  BENCHMARK RESULTS: {passed}/{len(results)} passed ({rate:.1f}%)")
print("=" * 60)
print()
for r in results:
    icon = "✓" if r.get("status") == "completed" else "✗"
    name = r.get("task_name", r.get("task_id", "?"))
    status = r.get("status", "?")
    dur = r.get("wall_time_s", r.get("duration_ms", 0) / 1000)
    device = r.get("device", "?")
    msg = r.get("final_message", "")[:70]
    print(f"  {icon} [{device}] {name}: {status} ({dur}s)")
    if status != "completed":
        print(f"    → {msg}")
print()

if failed > 0:
    fail_dir = os.path.join(run_dir, "failures")
    if os.path.isdir(fail_dir):
        fail_tasks = [d for d in os.listdir(fail_dir) if os.path.isdir(os.path.join(fail_dir, d))]
        print(f"  Failed task artifacts in: {fail_dir}/")
        for t in sorted(fail_tasks):
            files = os.listdir(os.path.join(fail_dir, t))
            print(f"    {t}/: {', '.join(sorted(files))}")
        print()
PYEOF

log "Total wall time: ${TOTAL_WALL}s"
log "Results: $RUN_DIR/results.json"
log "Failures: $FAILURES_DIR/"
log "=========================================="
