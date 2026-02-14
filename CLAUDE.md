# Agent Relay - Claude Code Instructions

## Build & Deploy

- Build command: `ANDROID_HOME=/Users/aayushgupta/Library/Android/sdk ./gradlew assembleDebug`
- APK location: `app/build/outputs/apk/debug/app-debug.apk`
- Install command: `ANDROID_HOME=/Users/aayushgupta/Library/Android/sdk adb install -r app/build/outputs/apk/debug/app-debug.apk`

## Auto-Deploy Rule

**IMPORTANT**: After every code change to the repo, ALWAYS:
1. Build the debug APK
2. If an ADB device is connected, install the updated APK onto it
3. Report the deploy result to the user

This should happen automatically after any edit — do not wait for the user to ask.
