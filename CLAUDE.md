# Agent Relay - Claude Code Instructions

## Build & Deploy

- Build command: `JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/Users/aayushgupta/Library/Android/sdk ./gradlew assembleDebug`
- APK location: `app/build/outputs/apk/debug/app-debug.apk`
- Install command: `ANDROID_HOME=/Users/aayushgupta/Library/Android/sdk adb install -r app/build/outputs/apk/debug/app-debug.apk`

## Auto-Deploy Rule

**IMPORTANT**: After every code change to the repo, ALWAYS:
1. Build the debug APK
2. If an ADB device is connected, install the updated APK onto it
3. Report the deploy result to the user

This should happen automatically after any edit — do not wait for the user to ask.

## Cloud Emulator (GCP)

Instead of running the Android emulator locally, use cloud VMs for better performance and parallelization.

### Quick Start

```bash
cd cloud-emulator

# Create 1 emulator VM (takes ~5 min to provision)
./gcp-emulator.sh create

# Create 3 parallel emulators
./gcp-emulator.sh create 3

# Check if provisioning is done
./gcp-emulator.sh status

# Tunnel ADB to your machine (run in a separate terminal)
./gcp-emulator.sh connect 1      # single instance
./gcp-emulator.sh connect-all    # all instances

# Connect ADB (after tunnel is up)
adb connect localhost:5601        # instance 1
adb connect localhost:5602        # instance 2

# Build & deploy to all connected cloud emulators
./gcp-emulator.sh deploy

# Stop/start/delete when done
./gcp-emulator.sh stop all
./gcp-emulator.sh delete all
```

### API Keys

Set these env vars before `deploy` or `setup-keys` to auto-configure the emulators:

```bash
export ANTHROPIC_API_KEY="sk-ant-..."
export GEMINI_API_KEY="AIza..."
./gcp-emulator.sh deploy        # builds, installs APK, pushes keys
./gcp-emulator.sh setup-keys    # pushes keys only (no rebuild)
```

### Environment Overrides

- `GCP_PROJECT` — GCP project ID (default: `proteus`)
- `GCP_ZONE` — zone (default: `us-central1-a`)
- `GCP_MACHINE_TYPE` — VM type (default: `n2-standard-4`, ~$0.20/hr)
- `GCP_BOOT_DISK_SIZE` — disk (default: `50GB`)
