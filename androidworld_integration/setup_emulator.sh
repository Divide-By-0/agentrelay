#!/bin/bash
# Setup script for running Agent Relay with AndroidWorld benchmark.
# Run this after building the APK and starting an emulator/device.
#
# Usage:
#   API_KEY=sk-ant-... MODEL=claude-sonnet-4-5-20250929 ./setup_emulator.sh

set -euo pipefail

PACKAGE="com.agentrelay"
APK_PATH="${APK_PATH:-../app/build/outputs/apk/debug/app-debug.apk}"
API_KEY="${API_KEY:?Set API_KEY environment variable}"
MODEL="${MODEL:-claude-sonnet-4-5-20250929}"

echo "=== Agent Relay AndroidWorld Setup ==="

# 1. Install APK
echo "[1/6] Installing APK..."
adb install -r "$APK_PATH"

# 2. Enable accessibility service
echo "[2/6] Enabling accessibility service..."
adb shell settings put secure enabled_accessibility_services "$PACKAGE/$PACKAGE.AutomationService"
adb shell settings put secure accessibility_enabled 1

# 3. Grant overlay permission
echo "[3/6] Granting overlay permission..."
adb shell appops set "$PACKAGE" SYSTEM_ALERT_WINDOW allow

# 4. Launch app to trigger screen capture permission
echo "[4/6] Launching app for screen capture setup..."
adb shell am start -n "$PACKAGE/.MainActivity"
sleep 3
# Auto-accept the screen capture dialog (coordinates may vary by device/emulator)
echo "  Attempting to auto-accept screen capture dialog..."
adb shell input tap 540 1400
sleep 2

# 5. Configure API key and model via broadcast
echo "[5/6] Configuring API key and model..."
adb shell am broadcast \
    -n "$PACKAGE/$PACKAGE.benchmark.BenchmarkReceiver" \
    -a com.agentrelay.benchmark.CONFIGURE \
    --es api_key "$API_KEY" \
    --es model "$MODEL"

# 6. Go home
echo "[6/6] Returning to home screen..."
adb shell input keyevent 3

echo ""
echo "=== Setup complete ==="
echo "Test with:"
echo "  adb shell am broadcast -n $PACKAGE/$PACKAGE.benchmark.BenchmarkReceiver -a com.agentrelay.benchmark.START_TASK --es task 'Open the clock app' --es task_id test1"
echo "  adb shell run-as $PACKAGE cat files/benchmark_result.json"
