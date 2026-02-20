#!/bin/bash
# Manage Android emulator instances on GCP for Agent Relay.
#
# Usage:
#   ./gcp-emulator.sh create [N]       Create N emulator VMs (default: 1)
#   ./gcp-emulator.sh connect [N]      SSH-tunnel ADB for instance N (default: 1)
#   ./gcp-emulator.sh connect-all      SSH-tunnel ADB for all running instances
#   ./gcp-emulator.sh status           Show status of all emulator VMs
#   ./gcp-emulator.sh stop [N|all]     Stop instance N or all instances
#   ./gcp-emulator.sh start [N|all]    Start stopped instance N or all instances
#   ./gcp-emulator.sh delete [N|all]   Delete instance N or all instances
#   ./gcp-emulator.sh deploy [APK]     Build & install APK on all connected emulators
#   ./gcp-emulator.sh setup-keys       Push API keys to all connected emulators
#   ./gcp-emulator.sh ssh [N]          SSH into instance N
#   ./gcp-emulator.sh logs [N]         Tail emulator logs on instance N
#
# Environment:
#   ANTHROPIC_API_KEY    — Required for Claude models
#   GEMINI_API_KEY       — Required for Gemini models
#   GCP_PROJECT          — GCP project (default: proteus)
#   GCP_ZONE             — Zone (default: us-central1-a)
#   GCP_MACHINE_TYPE     — VM type (default: n2-standard-4)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT="${GCP_PROJECT:-proteus-photos}"
ZONE="${GCP_ZONE:-us-central1-a}"
MACHINE_TYPE="${GCP_MACHINE_TYPE:-n2-standard-4}"
BOOT_DISK_SIZE="${GCP_BOOT_DISK_SIZE:-50GB}"
IMAGE_FAMILY="ubuntu-2204-lts"
IMAGE_PROJECT="ubuntu-os-cloud"
NESTED_VIRT_IMAGE="agentrelay-ubuntu-nested-virt"
LOCAL_ADB_PORT_BASE=5600
PACKAGE="com.agentrelay"
SECRETS_XML="${SCRIPT_DIR}/../app/src/main/res/values/secrets.xml"

read_secret_from_xml() {
    local key="$1"
    if [[ -f "$SECRETS_XML" ]]; then
        local val
        val=$(grep "name=\"$key\"" "$SECRETS_XML" 2>/dev/null \
            | sed 's/.*>\(.*\)<\/string>.*/\1/' | tr -d '[:space:]')
        [[ -n "$val" ]] && echo "$val"
    fi
}

resolve_anthropic_key() {
    local key="${ANTHROPIC_API_KEY:-}"
    [[ -z "$key" ]] && key=$(read_secret_from_xml "default_claude_api_key")
    echo "$key"
}

resolve_gemini_key() {
    local key="${GEMINI_API_KEY:-}"
    [[ -z "$key" ]] && key=$(read_secret_from_xml "default_gemini_api_key")
    echo "$key"
}

resolve_vision_key() {
    local key="${GOOGLE_VISION_API_KEY:-}"
    [[ -z "$key" ]] && key=$(read_secret_from_xml "default_google_vision_api_key")
    # Fall back to Gemini key (same Google API key format, works if Vision API enabled)
    [[ -z "$key" ]] && key=$(resolve_gemini_key)
    echo "$key"
}

log()  { echo "[agentrelay-cloud] $*"; }
err()  { echo "[agentrelay-cloud] ERROR: $*" >&2; exit 1; }

instance_name() { echo "agentrelay-emu-${1}"; }
local_adb_port() { echo $((LOCAL_ADB_PORT_BASE + $1)); }

# Find the next available instance number (no collisions)
next_available_number() {
    local existing
    existing=$(gcloud compute instances list \
        --project="$PROJECT" \
        --filter="name~'^agentrelay-emu-'" \
        --format="value(name)" 2>/dev/null | sed 's/agentrelay-emu-//' | sort -n)

    local n=1
    while echo "$existing" | grep -qx "$n"; do
        n=$((n + 1))
    done
    echo "$n"
}

ensure_nested_virt_image() {
    if gcloud compute images describe "$NESTED_VIRT_IMAGE" --project="$PROJECT" &>/dev/null; then
        return 0
    fi
    log "Creating base image with nested virtualization license (one-time)..."
    gcloud compute images create "$NESTED_VIRT_IMAGE" \
        --project="$PROJECT" \
        --source-image-family="$IMAGE_FAMILY" \
        --source-image-project="$IMAGE_PROJECT" \
        --licenses="https://www.googleapis.com/compute/v1/projects/vm-options/global/licenses/enable-vmx" \
        --quiet
    log "Nested-virt image created."
}

list_instances() {
    gcloud compute instances list \
        --project="$PROJECT" \
        --filter="name~'^agentrelay-emu-'" \
        --format="$1" 2>/dev/null
}

cmd_create() {
    local count="${1:-1}"
    ensure_nested_virt_image

    local created=0
    for _ in $(seq 1 "$count"); do
        local num
        num=$(next_available_number)
        local name
        name=$(instance_name "$num")

        log "Creating $name ($MACHINE_TYPE in $ZONE)..."
        gcloud compute instances create "$name" \
            --project="$PROJECT" \
            --zone="$ZONE" \
            --machine-type="$MACHINE_TYPE" \
            --image="$NESTED_VIRT_IMAGE" \
            --image-project="$PROJECT" \
            --boot-disk-size="$BOOT_DISK_SIZE" \
            --boot-disk-type="pd-ssd" \
            --metadata-from-file="startup-script=${SCRIPT_DIR}/vm-startup.sh" \
            --scopes="default" \
            --quiet
        created=$((created + 1))
    done

    log "Created $created instance(s). Provisioning takes ~5 min."
    log "Check progress: $0 status"
}

cmd_status() {
    log "Emulator instances (project=$PROJECT, zone=$ZONE):"
    echo ""
    list_instances "table(name, status, zone, machineType.basename(), networkInterfaces[0].accessConfigs[0].natIP)"
    echo ""

    local running
    running=$(list_instances "value(name)" | head -20)

    if [[ -z "$running" ]]; then
        log "No instances found."
        return
    fi

    for name in $running; do
        local num="${name##*-}"
        local port
        port=$(local_adb_port "$num")
        local status
        status=$(gcloud compute instances describe "$name" \
            --zone="$ZONE" --project="$PROJECT" \
            --format="value(status)" 2>/dev/null || echo "UNKNOWN")

        if [[ "$status" != "RUNNING" ]]; then
            log "$name: $status"
            continue
        fi

        local provisioned
        provisioned=$(gcloud compute ssh "$name" \
            --zone="$ZONE" --project="$PROJECT" \
            --command="test -f /opt/android-emulator-ready && echo yes || echo no" \
            2>/dev/null || echo "unreachable")

        if [[ "$provisioned" == "yes" ]]; then
            log "$name: ready  →  adb connect localhost:$port"
        elif [[ "$provisioned" == "unreachable" ]]; then
            log "$name: booting (SSH not ready yet)"
        else
            log "$name: provisioning (SDK installing...)"
        fi
    done
}

cmd_connect() {
    local num="${1:-1}"
    local name
    name=$(instance_name "$num")
    local port
    port=$(local_adb_port "$num")

    log "Tunneling ADB: localhost:$port → $name:5555"
    log "Once connected, run: adb connect localhost:$port"
    log "Press Ctrl+C to disconnect."

    gcloud compute ssh "$name" \
        --project="$PROJECT" \
        --zone="$ZONE" \
        -- -N -L "${port}:localhost:5555" \
        -o "ServerAliveInterval=30" \
        -o "ServerAliveCountMax=3" \
        -o "ExitOnForwardFailure=yes"
}

cmd_connect_all() {
    local instances
    instances=$(gcloud compute instances list \
        --project="$PROJECT" \
        --filter="name~'^agentrelay-emu-' AND status=RUNNING" \
        --format="value(name)" 2>/dev/null)

    if [[ -z "$instances" ]]; then
        err "No running emulator instances found."
    fi

    local pids=()
    trap 'kill "${pids[@]}" 2>/dev/null; exit 0' INT TERM

    for name in $instances; do
        local num="${name##*-}"
        local port
        port=$(local_adb_port "$num")
        log "Tunneling: localhost:$port → $name:5555"

        gcloud compute ssh "$name" \
            --project="$PROJECT" \
            --zone="$ZONE" \
            -- -N -L "${port}:localhost:5555" \
            -o "ServerAliveInterval=30" \
            -o "ServerAliveCountMax=3" \
            -o "ExitOnForwardFailure=yes" &
        pids+=($!)
    done

    log "All tunnels active. Connect with:"
    for name in $instances; do
        local num="${name##*-}"
        echo "  adb connect localhost:$(local_adb_port "$num")   # $name"
    done
    log "Press Ctrl+C to disconnect all."
    wait
}

resolve_targets() {
    local target="$1"
    local filter_status="${2:-}"
    if [[ "$target" == "all" ]]; then
        local filter="name~'^agentrelay-emu-'"
        [[ -n "$filter_status" ]] && filter="$filter AND status=$filter_status"
        gcloud compute instances list \
            --project="$PROJECT" \
            --filter="$filter" \
            --format="value(name)" 2>/dev/null
    else
        instance_name "$target"
    fi
}

cmd_stop() {
    local target="${1:-}"
    [[ -z "$target" ]] && err "Usage: $0 stop [N|all]"

    local names
    names=$(resolve_targets "$target" "RUNNING")
    [[ -z "$names" ]] && { log "No running instances to stop."; return; }

    for name in $names; do
        log "Stopping $name..."
        gcloud compute instances stop "$name" --zone="$ZONE" --project="$PROJECT" --quiet &
    done
    wait
    log "Done."
}

cmd_start() {
    local target="${1:-}"
    [[ -z "$target" ]] && err "Usage: $0 start [N|all]"

    local names
    names=$(resolve_targets "$target" "TERMINATED")
    [[ -z "$names" ]] && { log "No stopped instances to start."; return; }

    for name in $names; do
        log "Starting $name..."
        gcloud compute instances start "$name" --zone="$ZONE" --project="$PROJECT" --quiet &
    done
    wait
    log "Done."
}

cmd_delete() {
    local target="${1:-}"
    [[ -z "$target" ]] && err "Usage: $0 delete [N|all]"

    local names
    names=$(resolve_targets "$target")
    [[ -z "$names" ]] && { log "No instances to delete."; return; }

    for name in $names; do
        log "Deleting $name..."
        gcloud compute instances delete "$name" --zone="$ZONE" --project="$PROJECT" --quiet &
    done
    wait
    log "Done."
}

get_cloud_emulator_devices() {
    adb devices | grep -oE "localhost:5[0-9]+" || true
}

cmd_setup_keys() {
    local anthropic_key
    anthropic_key=$(resolve_anthropic_key)
    local gemini_key
    gemini_key=$(resolve_gemini_key)

    [[ -z "$anthropic_key" && -z "$gemini_key" ]] && \
        err "No API keys found. Set ANTHROPIC_API_KEY / GEMINI_API_KEY env vars, or add them to secrets.xml."

    local devices
    devices=$(get_cloud_emulator_devices)
    [[ -z "$devices" ]] && err "No cloud emulators connected via ADB. Run '$0 connect N' first."

    local vision_key
    vision_key=$(resolve_vision_key)

    for device in $devices; do
        log "Configuring API keys on $device..."

        local extras=""
        [[ -n "$anthropic_key" ]] && extras="$extras --es api_key $anthropic_key"
        [[ -n "$gemini_key" ]]    && extras="$extras --es gemini_api_key $gemini_key"
        [[ -n "$vision_key" ]]    && extras="$extras --es google_vision_api_key $vision_key"
        # Always enable screen recording, screenshots, and OCR for cloud emulators
        extras="$extras --es screen_recording true --es send_screenshots AUTO --es ocr_enabled true"

        adb -s "$device" shell am broadcast \
            -n "$PACKAGE/$PACKAGE.benchmark.BenchmarkReceiver" \
            -a com.agentrelay.benchmark.CONFIGURE \
            $extras \
            > /dev/null

        log "  Keys + recording + OCR configured on $device"
    done
    log "All API keys configured."
}

cmd_deploy() {
    local apk="${1:-}"
    local repo_root
    repo_root="$(cd "$SCRIPT_DIR/.." && pwd)"

    if [[ -z "$apk" ]]; then
        log "Building APK..."
        (cd "$repo_root" && JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/Users/aayushgupta/Library/Android/sdk ./gradlew assembleDebug --quiet)
        apk="$repo_root/app/build/outputs/apk/debug/app-debug.apk"
    fi

    [[ -f "$apk" ]] || err "APK not found: $apk"

    local devices
    devices=$(get_cloud_emulator_devices)
    [[ -z "$devices" ]] && err "No cloud emulators connected via ADB. Run '$0 connect N' first."

    for device in $devices; do
        log "Installing APK on $device..."
        adb -s "$device" install -r "$apk" &
    done
    wait
    log "APK deployed to all connected cloud emulators."

    cmd_setup_keys_if_available
}

cmd_setup_keys_if_available() {
    local anthropic_key
    anthropic_key=$(resolve_anthropic_key)
    local gemini_key
    gemini_key=$(resolve_gemini_key)

    if [[ -n "$anthropic_key" || -n "$gemini_key" ]]; then
        cmd_setup_keys
    else
        log "Tip: Add keys to secrets.xml or set ANTHROPIC_API_KEY / GEMINI_API_KEY env vars."
    fi
}

cmd_ssh() {
    local num="${1:-1}"
    local name
    name=$(instance_name "$num")
    gcloud compute ssh "$name" --project="$PROJECT" --zone="$ZONE"
}

cmd_logs() {
    local num="${1:-1}"
    local name
    name=$(instance_name "$num")
    gcloud compute ssh "$name" --project="$PROJECT" --zone="$ZONE" \
        --command="journalctl -u android-emulator -f --no-pager"
}

case "${1:-help}" in
    create)      cmd_create "${2:-1}" ;;
    connect)     cmd_connect "${2:-1}" ;;
    connect-all) cmd_connect_all ;;
    status)      cmd_status ;;
    stop)        cmd_stop "${2:-}" ;;
    start)       cmd_start "${2:-}" ;;
    delete)      cmd_delete "${2:-}" ;;
    deploy)      cmd_deploy "${2:-}" ;;
    setup-keys)  cmd_setup_keys ;;
    ssh)         cmd_ssh "${2:-1}" ;;
    logs)        cmd_logs "${2:-1}" ;;
    help|*)
        echo "Usage: $0 <command> [args]"
        echo ""
        echo "Commands:"
        echo "  create [N]       Create N emulator VMs (default: 1)"
        echo "  connect [N]      SSH-tunnel ADB for instance N (default: 1)"
        echo "  connect-all      SSH-tunnel ADB for all running instances"
        echo "  status           Show status of all emulator VMs"
        echo "  stop [N|all]     Stop instance N or all"
        echo "  start [N|all]    Start instance N or all"
        echo "  delete [N|all]   Delete instance N or all"
        echo "  deploy [APK]     Build & install APK on all connected emulators"
        echo "  setup-keys       Push ANTHROPIC_API_KEY / GEMINI_API_KEY to emulators"
        echo "  ssh [N]          SSH into instance N"
        echo "  logs [N]         Tail emulator logs on instance N"
        echo ""
        echo "API Keys (auto-read from secrets.xml, or override with env vars):"
        echo "  ANTHROPIC_API_KEY  Anthropic/Claude API key"
        echo "  GEMINI_API_KEY     Google Gemini API key"
        echo ""
        echo "Environment:"
        echo "  GCP_PROJECT        GCP project (default: proteus)"
        echo "  GCP_ZONE           Zone (default: us-central1-a)"
        echo "  GCP_MACHINE_TYPE   VM type (default: n2-standard-4)"
        echo "  GCP_BOOT_DISK_SIZE Disk size (default: 50GB)"
        ;;
esac
