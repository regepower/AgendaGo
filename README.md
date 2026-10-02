# MinCalWidget

A minimal home-screen widget that lists your upcoming calendar events. Android 12–16, no libraries.

## Features

- Event list with calendar colour, date/time column and title (optional location)
- Choose calendars, number of events and number of days
- Font (Standard, Serif, Monospace, Handwriting), font size and width of the date column
- Material You colours from the wallpaper, follows light/dark mode
- Transparent background and "invert colours" for any wallpaper
- Tap an event to open it in your calendar app
- Updates itself when calendar data changes, at midnight and when an event ends – no polling
- English UI with German translation

## Usage

1. Install the release APK, open the app and allow calendar access.
2. Long-press the home screen → Widgets → *MinCalWidget*, or use "Add widget to home screen" in the app.
3. Adjust the settings (live preview) and tap *Apply*.
4. Change settings later: long-press the widget → reconfigure, or open the app.

## Build

No local Android SDK needed: GitHub Actions runs `lintDebug` + `assembleRelease` on every push.
The APK is attached to each run as artifact `MinCalWidget-release`; tags `v*` create a GitHub release.

Signing uses the repository secrets `KEYSTORE_BASE64` and `KEYSTORE_PASSWORD`
(optional `KEY_ALIAS`, `KEY_PASSWORD`; without `KEY_ALIAS` the first alias in the keystore is used).
Without secrets the release APK is signed with the debug key.

Local build: `gradle assembleRelease` (Gradle 8.11.1, JDK 17, Android SDK 36).

## Permissions

- `READ_CALENDAR` – read events
- `RECEIVE_BOOT_COMPLETED` – re-draw the widget after a reboot
