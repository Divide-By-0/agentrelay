# Agent Relay - Android Autonomous Agent

An Android app that provides autonomous control of your device using Claude Opus 4.5. The app can see your screen, understand tasks, and execute actions through cursor control and keyboard input.

## Features

- **Visible Cursor Control**: Animated cursor overlay that moves like a real mouse
- **Screen Capture**: Captures screenshots and sends them to Claude
- **Agentic Loop**: Autonomous task execution with Claude Opus 4.5
- **Secure Storage**: Encrypted API key storage
- **Accessibility Service**: Full control over UI interactions
- **Persistent Notification**: Easy access to start/stop the agent

## Setup Instructions

### Prerequisites

1. **Android Studio**: Install the latest version of Android Studio
2. **Android Device**: Android 7.0 (API 24) or higher
3. **Claude API Key**: Get your API key from [console.anthropic.com](https://console.anthropic.com)

### Building the App

1. Clone or download this project
2. Open the project in Android Studio
3. Wait for Gradle sync to complete
4. Connect your Android device or start an emulator
5. Click "Run" or press Shift+F10

### First-Time Setup

1. **Enter API Key**:
   - Open the app
   - Enter your Claude API key (starts with `sk-ant-api`)
   - Tap "Save"

2. **Grant Permissions**:
   - **Overlay Permission**: Tap to open settings and enable
   - **Accessibility Service**:
     - Tap to open Accessibility settings
     - Find "AgentRelay" in the list
     - Enable the service
     - Confirm the warning dialog

3. **Start the Service**:
   - Tap "Start Service"
   - Grant screen capture permission when prompted
   - A persistent notification will appear

## Usage

1. **Start the Agent**:
   - Tap the "Start Agent" button in the notification
   - An overlay window will appear

2. **Enter Your Task**:
   - Type what you want the agent to do (e.g., "Open Chrome and search for cats")
   - Tap "Start Task"

3. **Watch the Magic**:
   - The cursor will appear on screen
   - Claude will analyze screenshots and control your device
   - The cursor moves to show what the agent is doing

4. **Stop the Agent**:
   - Tap "Stop Agent" in the notification
   - Or wait for the task to complete

## How It Works

1. **Screenshot Capture**: Uses MediaProjection API to capture the screen
2. **Claude Analysis**: Sends screenshots to Claude Opus 4.5 with task context
3. **Action Parsing**: Claude responds with JSON actions (tap, swipe, type, etc.)
4. **Cursor Animation**: Visible cursor moves to the target location
5. **Action Execution**: AccessibilityService performs the actual interaction
6. **Loop**: Captures new screenshot and repeats until task is complete

## Available Actions

Claude can perform these actions:
- `tap`: Tap at specific coordinates
- `swipe`: Swipe from one point to another
- `type`: Enter text (using clipboard paste)
- `back`: Press the back button
- `home`: Go to home screen
- `wait`: Wait for UI to settle
- `complete`: Mark task as finished

## Architecture

- **MainActivity**: Settings UI and permission management
- **ScreenCaptureService**: Foreground service for screen capture
- **AutomationService**: Accessibility service for device control
- **CursorOverlay**: Animated cursor visualization
- **OverlayWindow**: Task input dialog
- **AgentOrchestrator**: Main agentic loop controller
- **ClaudeAPIClient**: API communication with Claude
- **SecureStorage**: Encrypted API key storage

## Important Notes

- **API Costs**: Claude Opus 4.5 is expensive. Each screenshot uses significant tokens. Monitor your usage!
- **Safety**: The app has full device control. Only use with trusted tasks.
- **Performance**: Screenshots are scaled to 1920px max for efficiency
- **Iteration Limit**: Maximum 50 iterations to prevent infinite loops
- **Battery Usage**: Screen capture and AI processing are battery-intensive

## Troubleshooting

### "Please enable Accessibility Service"
- Go to Settings → Accessibility → AgentRelay
- Toggle the switch to enable
- Confirm the warning

### "Screen capture not initialized"
- Make sure you granted screen capture permission
- Restart the service from the app

### "API key invalid"
- Ensure your API key starts with `sk-ant-api`
- Check that you copied the entire key

### Cursor not visible
- Check that overlay permission is granted
- Restart the app

### Actions not working
- Verify Accessibility Service is enabled
- Some system apps may block accessibility actions

## Development

### Tech Stack
- Kotlin
- Jetpack Compose
- Coroutines
- Retrofit + OkHttp
- Android Accessibility Service
- MediaProjection API

### Key Files
- `MainActivity.kt`: UI and permissions
- `AgentOrchestrator.kt`: Main agent loop
- `ClaudeAPIClient.kt`: API integration
- `AutomationService.kt`: Device control
- `CursorOverlay.kt`: Cursor animation

## License

This is a demonstration project. Use at your own risk.

## Credits

Built with Claude Code by Anthropic.
