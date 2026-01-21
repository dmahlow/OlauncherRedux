# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Build debug APK
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk

# Build release APK (unsigned)
./gradlew assembleRelease

# Run all checks (build + lint)
./gradlew build

# Run instrumented tests (requires connected device or emulator)
./gradlew connectedAndroidTest
```

**Requirements:** Java 11-17, Android SDK with platform 33 and build-tools 33.0.0

## Architecture Overview

OlauncherRedux is a minimal Android launcher - a fork of OlauncherCF with added features like usage-based app drawer sorting, gesture customization, and app renaming.

### UI Structure

Single-activity architecture using Navigation Component with three fragments:

- **HomeFragment** - Main home screen with configurable app shortcuts (4-15), clock/date display, and gesture detection
- **AppDrawerFragment** - Searchable RecyclerView list of installed apps with filtering
- **SettingsFragment** - Jetpack Compose-based settings UI

The app uses a hybrid UI approach: traditional XML layouts with View Binding for Home and AppDrawer, Jetpack Compose for Settings.

### State Management

- **MainViewModel** - Shared ViewModel using LiveData for app list, UI state, and gesture events
- **Prefs** - SharedPreferences wrapper for persistent storage (home apps, gestures, theme, hidden apps)
- Backup/restore uses Gson for JSON serialization

### Gesture System

Touch handling is the core complexity, abstracted into listener classes:

- `OnSwipeTouchListener` - Base class detecting swipes, long-press, double-tap via GestureDetector
- `ViewSwipeTouchListener` - Extended version for individual home app views
- Gestures map to configurable actions (open app, lock screen, notifications, quick settings)

### Key Packages

- `data/` - Models (AppModel, Constants enums, Prefs)
- `ui/` - Fragments (Home, AppDrawer, Settings) and Compose components
- `listener/` - Touch event handlers and DeviceAdmin receiver
- `helper/` - Utils for app enumeration, ActionService for accessibility-based actions

### Important Constraints

- **No network permission** - The app deliberately has no internet access
- Uses `LauncherApps` service for querying apps with work profile support
- `ActionService` (AccessibilityService) enables lock screen on Android 9+; `DeviceAdmin` is the fallback

## Testing

Instrumented tests in `app/src/androidTest/` use Compose UI Testing and Espresso. Tests run on API 28 emulator in CI. The single test file `SettingsTest.kt` covers settings interactions and gesture configuration.
