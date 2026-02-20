#!/bin/bash
# Sets up all AndroidWorld apps on a cloud emulator VM.
# Replicates what AndroidWorld's setup_apps() does using direct ADB commands.
# Run this on each VM via SSH, or locally with ADB_SERIAL set.
#
# Usage:
#   ssh user@vm 'bash -s' < setup-androidworld-apps.sh          # on VM
#   ADB_SERIAL=localhost:5601 bash setup-androidworld-apps.sh    # local with tunnel

set -euo pipefail

# ADB binary - on VM it's in /opt/android-sdk, locally in SDK
if [[ -x "/opt/android-sdk/platform-tools/adb" ]]; then
    ADB="/opt/android-sdk/platform-tools/adb"
elif [[ -n "${ANDROID_HOME:-}" ]]; then
    ADB="$ANDROID_HOME/platform-tools/adb"
else
    ADB="adb"
fi

# If ADB_SERIAL is set, use -s flag
if [[ -n "${ADB_SERIAL:-}" ]]; then
    ADB="$ADB -s $ADB_SERIAL"
fi

echo "Using ADB: $ADB"
$ADB devices 2>/dev/null | head -5

# Wait for device
$ADB wait-for-device

# ─── Helpers ────────────────────────────────────────────────────────────────

adb_shell() {
    $ADB shell "$@" 2>/dev/null
}

clear_app() {
    local pkg="$1"
    echo "  Clearing $pkg"
    adb_shell pm clear "$pkg" || true
}

grant_perm() {
    local pkg="$1"
    local perm="$2"
    adb_shell pm grant "$pkg" "$perm" 2>/dev/null || true
}

launch_app() {
    local pkg="$1"
    adb_shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 2>/dev/null || true
}

close_app() {
    local pkg="$1"
    adb_shell am force-stop "$pkg" 2>/dev/null || true
}

press_home() {
    adb_shell input keyevent KEYCODE_HOME
}

# Click an element by matching text OR content-desc in uiautomator dump.
# Returns 0 if clicked, 1 if not found.
click_text() {
    local target_text="$1"
    local max_attempts="${2:-3}"

    for attempt in $(seq 1 "$max_attempts"); do
        # Dump UI hierarchy
        adb_shell uiautomator dump /sdcard/window_dump.xml 2>/dev/null || true
        local xml
        xml=$(adb_shell cat /sdcard/window_dump.xml 2>/dev/null) || true

        if [[ -z "$xml" ]]; then
            sleep 1
            continue
        fi

        # Search in text= attribute first
        local bounds
        bounds=$(echo "$xml" | grep -oP "text=\"${target_text}\"[^>]*?bounds=\"\[\d+,\d+\]\[\d+,\d+\]\"" | head -1 | grep -oP 'bounds="\[\d+,\d+\]\[\d+,\d+\]"' || true)

        # If not found, search in content-desc= attribute
        if [[ -z "$bounds" ]]; then
            bounds=$(echo "$xml" | grep -oP "content-desc=\"${target_text}\"[^>]*?bounds=\"\[\d+,\d+\]\[\d+,\d+\]\"" | head -1 | grep -oP 'bounds="\[\d+,\d+\]\[\d+,\d+\]"' || true)
        fi

        # If still not found, try case-insensitive search on text
        if [[ -z "$bounds" ]]; then
            bounds=$(echo "$xml" | grep -ioP "text=\"${target_text}\"[^>]*?bounds=\"\[\d+,\d+\]\[\d+,\d+\]\"" | head -1 | grep -oP 'bounds="\[\d+,\d+\]\[\d+,\d+\]"' || true)
        fi

        # If still not found, try case-insensitive on content-desc
        if [[ -z "$bounds" ]]; then
            bounds=$(echo "$xml" | grep -ioP "content-desc=\"${target_text}\"[^>]*?bounds=\"\[\d+,\d+\]\[\d+,\d+\]\"" | head -1 | grep -oP 'bounds="\[\d+,\d+\]\[\d+,\d+\]"' || true)
        fi

        if [[ -n "$bounds" ]]; then
            # Extract coordinates: bounds="[x1,y1][x2,y2]"
            local x1 y1 x2 y2
            read -r x1 y1 x2 y2 <<< "$(echo "$bounds" | grep -oP '\d+' | tr '\n' ' ')"
            local cx=$(( (x1 + x2) / 2 ))
            local cy=$(( (y1 + y2) / 2 ))
            echo "  Clicking '$target_text' at ($cx, $cy)"
            adb_shell input tap "$cx" "$cy"
            return 0
        fi
        sleep 1
    done
    echo "  WARNING: Could not find '$target_text' on screen"
    return 1
}

# Click an element by resource-id
click_id() {
    local target_id="$1"
    local max_attempts="${2:-3}"

    for attempt in $(seq 1 "$max_attempts"); do
        adb_shell uiautomator dump /sdcard/window_dump.xml 2>/dev/null || true
        local xml
        xml=$(adb_shell cat /sdcard/window_dump.xml 2>/dev/null) || true

        if [[ -z "$xml" ]]; then
            sleep 1
            continue
        fi

        local bounds
        bounds=$(echo "$xml" | grep -oP "resource-id=\"${target_id}\"[^>]*?bounds=\"\[\d+,\d+\]\[\d+,\d+\]\"" | head -1 | grep -oP 'bounds="\[\d+,\d+\]\[\d+,\d+\]"' || true)

        if [[ -n "$bounds" ]]; then
            local x1 y1 x2 y2
            read -r x1 y1 x2 y2 <<< "$(echo "$bounds" | grep -oP '\d+' | tr '\n' ' ')"
            local cx=$(( (x1 + x2) / 2 ))
            local cy=$(( (y1 + y2) / 2 ))
            echo "  Clicking resource '$target_id' at ($cx, $cy)"
            adb_shell input tap "$cx" "$cy"
            return 0
        fi
        sleep 1
    done
    echo "  WARNING: Could not find resource '$target_id' on screen"
    return 1
}

# ─── Package Names ──────────────────────────────────────────────────────────

PKG_CAMERA="com.android.camera2"
PKG_CHROME="com.android.chrome"
PKG_CLOCK="com.google.android.deskclock"
PKG_CONTACTS="com.google.android.contacts"
PKG_DIALER="com.google.android.dialer"
PKG_FILES="com.google.android.documentsui"
PKG_SETTINGS="com.android.settings"
PKG_MARKOR="net.gsantner.markor"
PKG_CALENDAR="com.simplemobiletools.calendar.pro"
PKG_TASKS="org.tasks"
PKG_DRAW="com.simplemobiletools.draw.pro"
PKG_GALLERY="com.simplemobiletools.gallery.pro"
PKG_SMS="com.simplemobiletools.smsmessenger"
PKG_AUDIO="com.dimowner.audiorecorder"
PKG_EXPENSE="com.arduia.expense"
PKG_RECIPE="com.flauschcode.broccoli"
PKG_OSMAND="net.osmand"
PKG_OPENTRACKS="de.dennisguse.opentracks"
PKG_VLC="org.videolan.vlc"
PKG_JOPLIN="net.cozic.joplin"
PKG_RETRO="code.name.monkey.retromusic"

echo "============================================"
echo "AndroidWorld App Setup"
echo "============================================"

press_home

# ─── 1. Camera ──────────────────────────────────────────────────────────────
echo ""
echo "[1/20] Setting up Camera..."
clear_app "$PKG_CAMERA" || true
grant_perm "$PKG_CAMERA" android.permission.ACCESS_COARSE_LOCATION
launch_app "$PKG_CAMERA"
sleep 4
click_text "NEXT" 2 || true
sleep 1
close_app "$PKG_CAMERA"

# ─── 2. Chrome ──────────────────────────────────────────────────────────────
echo ""
echo "[2/20] Setting up Chrome..."
clear_app "$PKG_CHROME"
launch_app "$PKG_CHROME"
sleep 5
# Newer Chrome: "Use without an account" instead of "Accept & continue"
click_text "Use without an account" 2 || click_text "Accept & continue" 2 || true
sleep 3
click_text "No thanks" 2 || true
sleep 2
click_text "No thanks" 2 || true
sleep 1
# Handle "Turn on sync?" screen
click_text "No thanks" 1 || true
sleep 1
close_app "$PKG_CHROME"

# ─── 3. Clock ───────────────────────────────────────────────────────────────
echo ""
echo "[3/20] Setting up Clock..."
clear_app "$PKG_CLOCK"
launch_app "$PKG_CLOCK"
sleep 3
close_app "$PKG_CLOCK"

# ─── 4. Contacts ────────────────────────────────────────────────────────────
echo ""
echo "[4/20] Setting up Contacts..."
clear_app "$PKG_CONTACTS"
launch_app "$PKG_CONTACTS"
sleep 4
click_text "Skip" 2 || true
sleep 3
# Notification permission dialog (text varies by Android version)
click_text "Don't allow" 2 || click_text "Don.t allow" 2 || true
sleep 1
close_app "$PKG_CONTACTS"

# ─── 5. Dialer ──────────────────────────────────────────────────────────────
echo ""
echo "[5/20] Setting up Dialer..."
clear_app "$PKG_DIALER" || true

# ─── 6. Files ───────────────────────────────────────────────────────────────
echo ""
echo "[6/20] Setting up Files..."
clear_app "$PKG_FILES" || true

# ─── 7. Settings ────────────────────────────────────────────────────────────
echo ""
echo "[7/20] Setting up Settings..."
clear_app "$PKG_SETTINGS" || true

# ─── 8. Markor ──────────────────────────────────────────────────────────────
echo ""
echo "[8/20] Setting up Markor..."
clear_app "$PKG_MARKOR"
# Grant file access before launching to skip some dialogs
adb_shell appops set "$PKG_MARKOR" MANAGE_EXTERNAL_STORAGE allow 2>/dev/null || true
launch_app "$PKG_MARKOR"
sleep 4
# Markor uses content-desc="NEXT" on its onboarding button
click_text "NEXT" || true; sleep 2
click_text "NEXT" || true; sleep 2
click_text "NEXT" || true; sleep 2
click_text "NEXT" || true; sleep 2
click_text "DONE" || true; sleep 2
click_text "OK" || true; sleep 2
# If "manage all files" dialog appears, click it
click_text "Allow access to manage all files" 1 || true
sleep 1
close_app "$PKG_MARKOR"
# Ensure file management permission
adb_shell appops set "$PKG_MARKOR" MANAGE_EXTERNAL_STORAGE allow 2>/dev/null || true

# ─── 9. Simple Calendar Pro ─────────────────────────────────────────────────
echo ""
echo "[9/20] Setting up Simple Calendar Pro..."
clear_app "$PKG_CALENDAR"
launch_app "$PKG_CALENDAR"
sleep 2
close_app "$PKG_CALENDAR"
grant_perm "$PKG_CALENDAR" android.permission.READ_CALENDAR
grant_perm "$PKG_CALENDAR" android.permission.WRITE_CALENDAR
grant_perm "$PKG_CALENDAR" android.permission.POST_NOTIFICATIONS

# ─── 10. Tasks.org ───────────────────────────────────────────────────────────
echo ""
echo "[10/20] Setting up Tasks.org..."
clear_app "$PKG_TASKS"
launch_app "$PKG_TASKS"
sleep 3
close_app "$PKG_TASKS"

# ─── 11. Simple Draw Pro ────────────────────────────────────────────────────
echo ""
echo "[11/20] Setting up Simple Draw Pro..."
clear_app "$PKG_DRAW"

# ─── 12. Simple Gallery Pro ─────────────────────────────────────────────────
echo ""
echo "[12/20] Setting up Simple Gallery Pro..."
clear_app "$PKG_GALLERY"
# Grant all permissions via ADB first — this skips the entire onboarding
grant_perm "$PKG_GALLERY" android.permission.WRITE_EXTERNAL_STORAGE
grant_perm "$PKG_GALLERY" android.permission.ACCESS_MEDIA_LOCATION
grant_perm "$PKG_GALLERY" android.permission.READ_MEDIA_IMAGES
grant_perm "$PKG_GALLERY" android.permission.READ_MEDIA_VIDEO
grant_perm "$PKG_GALLERY" android.permission.POST_NOTIFICATIONS
adb_shell appops set "$PKG_GALLERY" MANAGE_EXTERNAL_STORAGE allow 2>/dev/null || true
launch_app "$PKG_GALLERY"
sleep 3
# If onboarding still appears, try clicking through
click_text "All files" 1 || true
sleep 1
click_text "Allow access to manage all files" 1 || true
sleep 1
close_app "$PKG_GALLERY"

# ─── 13. Simple SMS Messenger ───────────────────────────────────────────────
echo ""
echo "[13/20] Setting up Simple SMS Messenger..."
clear_app "$PKG_SMS"
# Set as default SMS app via ADB (avoids UI dialog)
adb_shell settings put secure sms_default_application "$PKG_SMS" || true
# Also need to grant SMS permissions
grant_perm "$PKG_SMS" android.permission.READ_SMS
grant_perm "$PKG_SMS" android.permission.SEND_SMS
grant_perm "$PKG_SMS" android.permission.RECEIVE_SMS
grant_perm "$PKG_SMS" android.permission.READ_CONTACTS
grant_perm "$PKG_SMS" android.permission.POST_NOTIFICATIONS
launch_app "$PKG_SMS"
sleep 4
# Try to handle any onboarding dialogs
click_text "SMS Messenger" 2 || true
sleep 2
click_text "Set as default" 2 || true
sleep 1
close_app "$PKG_SMS"

# ─── 14. Audio Recorder ─────────────────────────────────────────────────────
echo ""
echo "[14/20] Setting up Audio Recorder..."
clear_app "$PKG_AUDIO"
grant_perm "$PKG_AUDIO" android.permission.RECORD_AUDIO
grant_perm "$PKG_AUDIO" android.permission.POST_NOTIFICATIONS
launch_app "$PKG_AUDIO"
sleep 3
close_app "$PKG_AUDIO"

# ─── 15. Pro Expense ────────────────────────────────────────────────────────
echo ""
echo "[15/20] Setting up Pro Expense..."
clear_app "$PKG_EXPENSE"
launch_app "$PKG_EXPENSE"
sleep 4
click_text "NEXT" 2 || true
sleep 2
click_text "CONTINUE" 2 || true
sleep 3
close_app "$PKG_EXPENSE"

# ─── 16. Broccoli (Recipe) ──────────────────────────────────────────────────
echo ""
echo "[16/20] Setting up Broccoli Recipe..."
clear_app "$PKG_RECIPE"
launch_app "$PKG_RECIPE"
sleep 3
close_app "$PKG_RECIPE"

# ─── 17. OsmAnd ─────────────────────────────────────────────────────────────
echo ""
echo "[17/20] Setting up OsmAnd..."
clear_app "$PKG_OSMAND"
launch_app "$PKG_OSMAND"
sleep 4
click_text "SKIP DOWNLOAD" 2 || click_text "Skip download" 2 || true
sleep 1
close_app "$PKG_OSMAND"
grant_perm "$PKG_OSMAND" android.permission.POST_NOTIFICATIONS

# Download and install Liechtenstein map
echo "  Downloading Liechtenstein map..."
MAPS_DIR="/storage/emulated/0/Android/data/net.osmand/files"
adb_shell mkdir -p "$MAPS_DIR" 2>/dev/null || true

MAP_LOCAL="/tmp/Liechtenstein_europe.obf"
if [[ ! -f "$MAP_LOCAL" ]]; then
    wget -q "https://storage.googleapis.com/gresearch/android_world/Liechtenstein_europe.obf" -O "$MAP_LOCAL" 2>/dev/null || \
    curl -sL "https://storage.googleapis.com/gresearch/android_world/Liechtenstein_europe.obf" -o "$MAP_LOCAL" 2>/dev/null || true
fi
if [[ -f "$MAP_LOCAL" ]]; then
    $ADB push "$MAP_LOCAL" "$MAPS_DIR/Liechtenstein_europe.obf" 2>/dev/null || true
    adb_shell chcon u:object_r:media_rw_data_file:s0 "$MAPS_DIR/Liechtenstein_europe.obf" 2>/dev/null || true
    echo "  Map installed"
else
    echo "  WARNING: Could not download Liechtenstein map"
fi

# ─── 18. OpenTracks ─────────────────────────────────────────────────────────
echo ""
echo "[18/20] Setting up OpenTracks..."
clear_app "$PKG_OPENTRACKS"
grant_perm "$PKG_OPENTRACKS" android.permission.ACCESS_COARSE_LOCATION
grant_perm "$PKG_OPENTRACKS" android.permission.ACCESS_FINE_LOCATION
grant_perm "$PKG_OPENTRACKS" android.permission.POST_NOTIFICATIONS
grant_perm "$PKG_OPENTRACKS" android.permission.BLUETOOTH_SCAN 2>/dev/null || true
grant_perm "$PKG_OPENTRACKS" android.permission.BLUETOOTH_CONNECT 2>/dev/null || true
# Grant Bluetooth permissions by pre-approving via appops
adb_shell appops set "$PKG_OPENTRACKS" android:bluetooth_scan allow 2>/dev/null || true
launch_app "$PKG_OPENTRACKS"
sleep 3
# Handle Bluetooth permission dialog
click_text "Allow" 2 || true
sleep 1
close_app "$PKG_OPENTRACKS"

# ─── 19. VLC ────────────────────────────────────────────────────────────────
echo ""
echo "[19/20] Setting up VLC..."
clear_app "$PKG_VLC"
grant_perm "$PKG_VLC" android.permission.POST_NOTIFICATIONS
adb_shell mkdir -p /storage/emulated/0/VLCVideos 2>/dev/null || true
adb_shell appops set "$PKG_VLC" MANAGE_EXTERNAL_STORAGE allow 2>/dev/null || true
sleep 1
launch_app "$PKG_VLC"
sleep 4
# VLC has multiple onboarding screens, click SKIP through all of them
for i in 1 2 3 4; do
    click_text "SKIP" 1 || break
    sleep 2
done
# Handle permission screens if they appear
click_text "GRANT PERMISSION" 1 || true
sleep 2
click_text "OK" 1 || true
sleep 2
click_text "Allow access to manage all files" 1 || true
sleep 1
close_app "$PKG_VLC"
adb_shell appops set "$PKG_VLC" MANAGE_EXTERNAL_STORAGE allow 2>/dev/null || true

# ─── 20. Retro Music ────────────────────────────────────────────────────────
echo ""
echo "[20/20] Setting up Retro Music..."
clear_app "$PKG_RETRO"
grant_perm "$PKG_RETRO" android.permission.READ_MEDIA_AUDIO
grant_perm "$PKG_RETRO" android.permission.POST_NOTIFICATIONS
launch_app "$PKG_RETRO"
sleep 3
close_app "$PKG_RETRO"

# ─── Joplin (bonus — used by some tasks) ────────────────────────────────────
echo ""
echo "[bonus] Setting up Joplin..."
if adb_shell pm list packages | grep -q "$PKG_JOPLIN"; then
    clear_app "$PKG_JOPLIN"
    grant_perm "$PKG_JOPLIN" android.permission.ACCESS_COARSE_LOCATION
    grant_perm "$PKG_JOPLIN" android.permission.ACCESS_FINE_LOCATION
    launch_app "$PKG_JOPLIN"
    sleep 10
    close_app "$PKG_JOPLIN"
    sleep 5
else
    echo "  Joplin not installed, skipping"
fi

# ─── Return to home screen ──────────────────────────────────────────────────
press_home

echo ""
echo "============================================"
echo "AndroidWorld app setup complete!"
echo "============================================"
