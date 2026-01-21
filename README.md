# OlauncherRedux

Minimal and clutter-free Android launcher.

## About

OlauncherRedux is a fork of [OlauncherCF](https://github.com/OlauncherCF/OlauncherCF), which itself was a fork of the original [Olauncher](https://github.com/tanujnotes/Olauncher).

The original OlauncherCF repository is now archived and no longer maintained. This fork continues development with new features and a renamed package (`app.olauncherredux`).

## Features

**Inherited from OlauncherCF:**
- Minimal, text-based home screen with configurable app shortcuts (4-15)
- Gesture support: swipe left/right/up/down, clock tap, date tap, double-tap
- Configurable actions: open app, lock screen, notifications, quick settings
- App renaming in drawer and home screen
- Independent clock/date positioning
- Customizable alignment and font size
- No internet permission - complete privacy

**New in OlauncherRedux:**
- **Usage-based app drawer sorting** - sort apps by most frequently used instead of alphabetically
  - Weighted recency: last 7 days count 3x more than older usage
  - Home screen and gesture apps are deprioritized (they're already quick to access)
  - Requires granting "Usage access" permission in system settings
- **Optional app icons in drawer** - show small icons alongside app names
  - Icons sized to match text size
  - Configurable position (left or right)

## Building

Requirements: Java 11-17, Android SDK (platform 33, build-tools 33.0.0)

```bash
# Clone the repository
git clone https://github.com/dmahlow/OlauncherRedux.git
cd OlauncherRedux

# Build debug APK
./gradlew assembleDebug

# APK will be at: app/build/outputs/apk/debug/app-debug.apk
```

To install on a connected device:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Permissions

- `EXPAND_STATUS_BAR` - Expand/collapse status bar for notification gesture
- `QUERY_ALL_PACKAGES` - List installed apps in the drawer
- `SET_ALARM` - Open default alarm app when clock is tapped
- `REQUEST_DELETE_PACKAGES` - Show uninstall dialog for apps
- `PACKAGE_USAGE_STATS` - Optional, for usage-based sorting (requires manual grant)

## License

GPL-3.0 - free to use, study, modify, and distribute.

- No network access
- No data collection or transmission
