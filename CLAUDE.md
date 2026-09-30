# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Build debug APK (package app.olauncherredux.debug)
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk

# Build release APK (signed if release.properties exists in the project root, unsigned otherwise)
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk

# Run all checks (build + lint)
./gradlew build

# Run instrumented tests (requires connected device or emulator)
./gradlew connectedAndroidTest
```

**Requirements:** JDK 17 (AGP 8.1.4 needs 17+, Kotlin 1.7.10 does not work on 21+), Android SDK with platform 33 and build-tools 33.0.0. On this machine the system JDK is 22, so run Gradle with `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`.

**Release signing:** `release.properties` (gitignored) holds `storeFile`, `storePassword`, `keyAlias`, `keyPassword`. The keystore lives outside the repo at `~/.android/olauncherredux.jks` with a copy of the properties next to it. Losing the keystore means existing installs cannot be updated, so back it up.

**Releasing:** bump `versionCode` and `versionName` in `app/build.gradle`, add a `CHANGELOG.md` section, `./gradlew assembleRelease`, then tag `vX.Y.Z` and `gh release create` with the APK attached as `OlauncherRedux-vX.Y.Z.apk`. GitHub Actions only builds (`build.yml`); `release.yml` is manual-only and needs keystore secrets that are not set up.

## Architecture Overview

OlauncherRedux is a minimal Android launcher (package: `app.olauncherredux`) - a fork of OlauncherCF with added features like usage-based app drawer sorting, optional app icons, and gesture customization.

### UI Structure

Single-activity architecture using Navigation Component with three fragments:

- **HomeFragment** - Main home screen with configurable app shortcuts (0-15, default 10), app groups, clock/date display, and gesture detection
- **AppDrawerFragment** - Searchable RecyclerView list of installed apps with optional icons
- **SettingsFragment** - Jetpack Compose-based settings UI

The app uses a hybrid UI approach: traditional XML layouts with View Binding for Home and AppDrawer, Jetpack Compose for Settings.

### State Management

- **MainViewModel** - Shared ViewModel using LiveData for app list, UI state, and gesture events
- **Prefs** - SharedPreferences wrapper for all persistent storage; automatically included in backup/restore via Gson serialization

### Key Features Implementation

**Usage-based sorting** (`Utils.kt`):
- `getAppUsageScores()` queries `UsageStatsManager` with weighted recency (7-day usage 3x, 30-day 1x)
- `getQuickAccessApps()` collects home screen and gesture apps to deprioritize in sorting
- Requires `PACKAGE_USAGE_STATS` permission (user must grant via system settings)

**App icons in drawer** (`AppDrawerAdapter.kt`):
- Icons loaded via `PackageManager.getApplicationIcon()`
- Sized to match text size preference
- Position (left/right) configurable via `IconPosition` enum

**App groups** (`HomeFragment.kt`, `Prefs.kt`):
- A home slot is either an app or a group (`HOME_SLOT_IS_GROUP_<i>`); group members are stored as `GROUP_<slot>_<index>` app entries with `GROUP_APP_COUNT_<slot>`
- `rebuildHomeAppsLayout()` renders either the normal slot list or the expanded group (anchored around the tapped row); `expandedGroupIndex` tracks state
- Home rows are sorted by rendered text width, not by slot index
- `AppDrawerFlag.SetGroupApp` plus a `groupAppIndex` argument drive the picker for group members
- `getQuickAccessApps()` includes group members so they are deprioritized in most-used sorting

**Bundled wallpaper** (`res/raw/wallpaper.jpg`, `Utils.setBundledWallpaper()`):
- Offered in a dialog on first run (`HomeFragment`) and via Settings > Homescreen > Wallpaper
- Needs `SET_WALLPAPER`; applied on `Dispatchers.IO`

**Defaults** (`Prefs.kt`): 10 slots, text size 28, most-used sort, right/bottom alignment, dark theme, status bar on. Most-used sort falls back to A-Z without usage access; the drawer shows a tappable hint in that case.

**Time-of-day sort** (`Utils.getTimeOfDayScores()`): counts app launches (foreground app changes) from `UsageStatsManager.queryEvents` over the last 14 days. Each launch is weighted by a Gaussian on circular time-of-day distance (sigma 90 min), a 7-day half-life, and 0.3x if it happened on the other day type (weekday vs weekend); 10% of overall launch weight is added so apps used at other times stay ordered. Android keeps the event log only about 7-10 days.

### Gesture System

Touch handling abstracted into listener classes:

- `OnSwipeTouchListener` - Base class detecting swipes, long-press, double-tap via GestureDetector
- `ViewSwipeTouchListener` - Extended version for individual home app views
- Gestures map to configurable actions defined in `Constants.Action` enum

### Key Packages

- `data/` - Models (AppModel, Constants enums, Prefs)
- `ui/` - Fragments and Compose components
- `listener/` - Touch event handlers and DeviceAdmin receiver
- `helper/` - Utils for app enumeration, usage stats, ActionService

### Important Constraints

- **No network permission** - The app deliberately has no internet access
- Uses `LauncherApps` service for querying apps with work profile support
- `ActionService` (AccessibilityService) enables lock screen on Android 9+; `DeviceAdmin` is the fallback
- Translations exist in `res/values-*/strings.xml` - update all when changing user-facing strings

## Testing

Instrumented tests in `app/src/androidTest/` use Compose UI Testing and Espresso. The test file `SettingsTest.kt` covers settings interactions and gesture configuration.
