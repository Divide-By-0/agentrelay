#!/bin/bash
# Run benchmark tasks on a cloud emulator with full logging (logcat + video).
#
# Usage:
#   ./run-benchmark.sh [--device SERIAL] [--timeout SECS] [--output-dir DIR] -- "task description" ...
#   ./run-benchmark.sh --tasks-file tasks.json
#
# Each task gets:
#   - logcat saved to <output-dir>/<task_id>_logcat.txt
#   - screen recording pulled to <output-dir>/<task_id>_video.mp4
#   - result JSON from the app pulled to <output-dir>/<task_id>_result.json
#
# Requires: adb, jq
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PACKAGE="com.agentrelay"
RECEIVER="$PACKAGE/$PACKAGE.benchmark.BenchmarkReceiver"
RESULT_FILE_ON_DEVICE="/data/data/$PACKAGE/files/benchmark_result.json"
VIDEO_DIR_ON_DEVICE="/sdcard/Movies/AgentRelay"
APK_PATH="$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"
TIMEOUT=300
OUTPUT_DIR="$REPO_ROOT/benchmark_output"
DEVICE=""
TASKS_FILE=""
TASK_ARGS=()

usage() {
    echo "Usage: $0 [options] -- 'task 1' 'task 2' ..."
    echo "       $0 --tasks-file tasks.json"
    echo ""
    echo "Options:"
    echo "  --device SERIAL     ADB device serial (default: first connected)"
    echo "  --timeout SECS      Per-task timeout (default: 300)"
    echo "  --output-dir DIR    Output directory (default: benchmark_output/)"
    echo "  --tasks-file FILE   JSON file with task list"
    exit 1
}

# Parse args
while [[ $# -gt 0 ]]; do
    case "$1" in
        --device)     DEVICE="$2"; shift 2 ;;
        --timeout)    TIMEOUT="$2"; shift 2 ;;
        --output-dir) OUTPUT_DIR="$2"; shift 2 ;;
        --tasks-file) TASKS_FILE="$2"; shift 2 ;;
        --)           shift; TASK_ARGS=("$@"); break ;;
        -h|--help)    usage ;;
        *)            TASK_ARGS+=("$1"); shift ;;
    esac
done

ADB_CMD="adb"
[[ -n "$DEVICE" ]] && ADB_CMD="adb -s $DEVICE"

log() { echo "[bench] $*"; }
err() { echo "[bench] ERROR: $*" >&2; exit 1; }

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
[[ -z "$VISION_KEY" ]] && VISION_KEY="$GEMINI_KEY"

CONFIGURE_EXTRAS=""
[[ -n "$ANTHROPIC_KEY" ]] && CONFIGURE_EXTRAS="$CONFIGURE_EXTRAS --es api_key $ANTHROPIC_KEY"
[[ -n "$GEMINI_KEY" ]]    && CONFIGURE_EXTRAS="$CONFIGURE_EXTRAS --es gemini_api_key $GEMINI_KEY"
[[ -n "$VISION_KEY" ]]    && CONFIGURE_EXTRAS="$CONFIGURE_EXTRAS --es google_vision_api_key $VISION_KEY"
CONFIGURE_EXTRAS="$CONFIGURE_EXTRAS --es screen_recording true --es send_screenshots AUTO --es ocr_enabled true"

# Build task list: array of JSON objects {id, name, goal}
build_task_list() {
    if [[ -n "$TASKS_FILE" ]]; then
        cat "$TASKS_FILE"
    else
        local json="["
        local i=0
        for task in "${TASK_ARGS[@]}"; do
            [[ $i -gt 0 ]] && json="$json,"
            local id="task_$((i+1))"
            json="$json{\"id\":\"$id\",\"name\":\"Task $((i+1))\",\"goal\":$(echo "$task" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read().strip()))')}"
            i=$((i+1))
        done
        json="$json]"
        echo "$json"
    fi
}

# Clean old videos from device
clean_videos() {
    $ADB_CMD shell "rm -f $VIDEO_DIR_ON_DEVICE/*.mp4" 2>/dev/null || true
}

# Start logcat capture to file, return PID
start_logcat() {
    local outfile="$1"
    $ADB_CMD logcat -c 2>/dev/null || true
    $ADB_CMD logcat -v threadtime > "$outfile" 2>/dev/null &
    echo $!
}

# Stop logcat capture
stop_logcat() {
    local pid="$1"
    kill "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
}

# Auto-approve "Start now" dialog if visible. Returns 0 if approved, 1 if not found.
auto_approve_dialog() {
    local ui
    ui=$($ADB_CMD shell "uiautomator dump /sdcard/ui.xml 2>/dev/null && cat /sdcard/ui.xml" 2>/dev/null || echo "")
    if echo "$ui" | grep -q "Start now"; then
        local tap_coords
        tap_coords=$(python3 -c "
import re, sys
ui = sys.stdin.read()
m = re.search(r'text=\"Start now\"[^/]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', ui)
if m:
    print(f'{(int(m.group(1))+int(m.group(3)))//2} {(int(m.group(2))+int(m.group(4)))//2}')
else:
    print('854 1550')
" <<< "$ui")
        local tx ty
        tx=$(echo "$tap_coords" | cut -d' ' -f1)
        ty=$(echo "$tap_coords" | cut -d' ' -f2)
        $ADB_CMD shell "input tap $tx $ty"
        sleep 2
        return 0
    fi
    return 1
}

# Ensure screen capture is active. Launches ScreenCaptureRequestActivity and auto-approves dialog.
ensure_screen_capture() {
    # 1. Open the app to ensure process is alive
    $ADB_CMD shell "am start -n $PACKAGE/.MainActivity" > /dev/null 2>&1
    sleep 2

    # 2. Launch ScreenCaptureRequestActivity directly to trigger permission request
    $ADB_CMD shell "am start -n $PACKAGE/.ScreenCaptureRequestActivity" > /dev/null 2>&1
    sleep 3

    # 3. Auto-approve the "Start now" dialog (retry a couple times)
    local approved=false
    for attempt in 1 2 3; do
        if auto_approve_dialog; then
            log "Screen capture approved (attempt $attempt)."
            approved=true
            break
        fi
        sleep 2
    done

    if [[ "$approved" == "false" ]]; then
        log "No screen capture dialog found (may already be active)."
    fi

    # Go home
    $ADB_CMD shell "input keyevent KEYCODE_HOME" > /dev/null 2>&1
    sleep 1
}

# Start a benchmark task via broadcast
start_task() {
    local task_id="$1"
    local goal="$2"
    # Clear previous result
    $ADB_CMD shell "run-as $PACKAGE rm -f files/benchmark_result.json" 2>/dev/null || true
    # Send broadcast
    $ADB_CMD shell "am broadcast -n '$RECEIVER' -a com.agentrelay.benchmark.START_TASK --es task_id '$task_id' --es task '$goal'" 2>&1
}

# Poll for result file on device
poll_result() {
    local timeout_secs="$1"
    local start_time
    start_time=$(date +%s)

    while true; do
        local elapsed=$(( $(date +%s) - start_time ))
        if [[ $elapsed -ge $timeout_secs ]]; then
            echo '{"status":"timeout","duration_ms":'$((elapsed * 1000))',"final_message":"Timed out after '${timeout_secs}'s"}'
            return
        fi

        # Check if result file exists
        local result
        result=$($ADB_CMD shell "run-as $PACKAGE cat files/benchmark_result.json" 2>/dev/null || echo "")
        if [[ -n "$result" && "$result" != *"No such file"* ]]; then
            echo "$result"
            return
        fi

        printf "  ... %ds\n" "$elapsed" >&2
        sleep 5
    done
}

# Pull video recordings from device
pull_videos() {
    local dest_prefix="$1"
    local videos
    videos=$($ADB_CMD shell "ls $VIDEO_DIR_ON_DEVICE/*.mp4 2>/dev/null" 2>/dev/null | tr -d '\r' || true)

    if [[ -z "$videos" ]]; then
        log "  No video recordings found on device"
        return
    fi

    local i=0
    for video in $videos; do
        local dest="${dest_prefix}_video${i}.mp4"
        $ADB_CMD pull "$video" "$dest" 2>/dev/null && log "  Pulled video → $dest" || log "  Failed to pull $video"
        i=$((i+1))
    done
}

# Stop any running task
stop_task() {
    $ADB_CMD shell "am broadcast -n '$RECEIVER' -a com.agentrelay.benchmark.STOP_TASK" 2>/dev/null || true
}

# ── Main ──

TASKS_JSON=$(build_task_list)
TASK_COUNT=$(echo "$TASKS_JSON" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))')
[[ "$TASK_COUNT" -eq 0 ]] && err "No tasks specified. Use -- 'task description' or --tasks-file."

mkdir -p "$OUTPUT_DIR"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
RUN_DIR="$OUTPUT_DIR/run_$TIMESTAMP"
mkdir -p "$RUN_DIR"

log "=========================================="
log "Agent Relay Benchmark — $TASK_COUNT task(s)"
log "Output: $RUN_DIR"
log "Timeout: ${TIMEOUT}s per task"
log "=========================================="

# Ensure screen capture is ready before first task
ensure_screen_capture

RESULTS_FILE="$RUN_DIR/results.json"
echo "[]" > "$RESULTS_FILE"

for i in $(seq 0 $((TASK_COUNT - 1))); do
    TASK_ID=$(echo "$TASKS_JSON" | python3 -c "import json,sys; t=json.load(sys.stdin)[$i]; print(t['id'])")
    TASK_NAME=$(echo "$TASKS_JSON" | python3 -c "import json,sys; t=json.load(sys.stdin)[$i]; print(t['name'])")
    TASK_GOAL=$(echo "$TASKS_JSON" | python3 -c "import json,sys; t=json.load(sys.stdin)[$i]; print(t['goal'])")

    log ""
    log "--- [$((i+1))/$TASK_COUNT] $TASK_NAME ($TASK_ID) ---"
    log "  Goal: $TASK_GOAL"

    # Fresh install: uninstall + reinstall app for clean state every task
    log "  Reinstalling app..."
    $ADB_CMD shell "pm uninstall $PACKAGE" > /dev/null 2>&1 || true
    $ADB_CMD install -r "$APK_PATH" > /dev/null 2>&1 || {
        log "  WARNING: APK install failed, retrying..."
        sleep 3
        $ADB_CMD install -r "$APK_PATH" > /dev/null 2>&1 || true
    }
    # Enable accessibility service after fresh install
    $ADB_CMD shell "settings put secure enabled_accessibility_services $PACKAGE/$PACKAGE.AutomationService" > /dev/null 2>&1 || true
    $ADB_CMD shell "settings put secure accessibility_enabled 1" > /dev/null 2>&1 || true

    # Re-configure API keys + OCR after fresh install (SecureStorage was wiped)
    $ADB_CMD shell "am start -n $PACKAGE/.MainActivity" > /dev/null 2>&1 || true
    sleep 2
    $ADB_CMD shell "am broadcast -n '$RECEIVER' -a com.agentrelay.benchmark.CONFIGURE $CONFIGURE_EXTRAS" > /dev/null 2>&1 || true
    sleep 1

    # Clean videos before task
    clean_videos

    # Start logcat
    LOGCAT_FILE="$RUN_DIR/${TASK_ID}_logcat.txt"
    LOGCAT_PID=$(start_logcat "$LOGCAT_FILE")
    log "  Logcat → $LOGCAT_FILE (pid=$LOGCAT_PID)"

    # Start task
    WALL_START=$(date +%s)
    start_task "$TASK_ID" "$TASK_GOAL"
    log "  Started at $(date +%H:%M:%S)."

    # Give 5s for BenchmarkReceiver to potentially launch ScreenCaptureRequestActivity
    sleep 5
    if auto_approve_dialog; then
        log "  Auto-approved screen capture dialog after broadcast."
        sleep 2
    fi

    log "  Polling..."
    # Poll for result (progress goes to stderr, only JSON to stdout)
    RESULT=$(poll_result "$TIMEOUT")
    WALL_END=$(date +%s)
    WALL_SECS=$((WALL_END - WALL_START))

    # Stop logcat
    stop_logcat "$LOGCAT_PID"

    # Save raw result to temp file for safe python parsing
    RESULT_TMP="$RUN_DIR/${TASK_ID}_result_raw.json"
    echo "$RESULT" > "$RESULT_TMP"

    # Parse result
    STATUS=$(python3 -c "import json; r=json.load(open('$RESULT_TMP')); print(r.get('status','unknown'))" 2>/dev/null || echo "unknown")
    DURATION=$(python3 -c "import json; r=json.load(open('$RESULT_TMP')); print(r.get('duration_ms',0))" 2>/dev/null || echo "0")
    MESSAGE=$(python3 -c "import json; r=json.load(open('$RESULT_TMP')); print(r.get('final_message',''))" 2>/dev/null || echo "")

    log "  Status: $STATUS"
    log "  Duration: ${DURATION}ms (wall: ${WALL_SECS}s)"
    log "  Message: $MESSAGE"

    # Save pretty result JSON
    python3 -m json.tool "$RESULT_TMP" > "$RUN_DIR/${TASK_ID}_result.json" 2>/dev/null || cp "$RESULT_TMP" "$RUN_DIR/${TASK_ID}_result.json"
    rm -f "$RESULT_TMP"

    # Pull video recordings
    pull_videos "$RUN_DIR/${TASK_ID}"

    # If timed out, stop the agent
    if [[ "$STATUS" == "timeout" ]]; then
        stop_task
        sleep 2
    fi

    # Accumulate results into the results file using temp files
    python3 << PYEOF
import json
with open("$RESULTS_FILE") as f:
    results = json.load(f)
with open("$RUN_DIR/${TASK_ID}_result.json") as f:
    r = json.load(f)
r['task_number'] = $((i+1))
r['task_id'] = "$TASK_ID"
r['task_name'] = $(python3 -c "import json; print(json.dumps('$TASK_NAME'))")
r['goal'] = $(echo "$TASK_GOAL" | python3 -c "import json,sys; print(json.dumps(sys.stdin.read().strip()))")
r['wall_time_s'] = $WALL_SECS
r['logcat_file'] = "${TASK_ID}_logcat.txt"
results.append(r)
with open("$RESULTS_FILE", 'w') as f:
    json.dump(results, f, indent=2)
PYEOF

    log "  Logcat: $(wc -l < "$LOGCAT_FILE" | tr -d ' ') lines saved"
done

log ""
log "=========================================="
log "All $TASK_COUNT task(s) complete."
log "Results: $RESULTS_FILE"
PASSED=$(python3 -c "import json; r=json.load(open('$RESULTS_FILE')); print(sum(1 for t in r if t.get('status')=='completed'))")
log "Passed: $PASSED/$TASK_COUNT"
log "=========================================="
