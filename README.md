# AgendaGo

A calm home-screen agenda: upcoming events, contact birthdays and open tasks in one list. Android 12–16, no libraries.

## Features

- Event list with calendar colour, date/time column and title (optional location)
- Choose calendars, number of events and number of days
- Birthdays read directly from your contacts (with age)
- Open tasks from several sources, overdue tasks highlighted, configured in their own *Task sources* screen:
  - Tasks.org 15.12+ (Google Tasks, Microsoft To Do, CalDAV), selectable lists
  - OpenTasks (CalDAV via DAVx⁵), selectable lists
  - On-premises Exchange via EWS (Basic or NTLMv2 login, fetched every 30 minutes, password encrypted with the Android Keystore and never exported)
- Change calendar colours: Google palette synced to Google, own colours for Exchange/local calendars kept on the device
- Save/load all settings as a JSON file, built-in help
- Font (Standard, Serif, Monospace, Handwriting), font size and width of the date column
- Material You colours from the wallpaper, follows light/dark mode
- Transparent background and "invert colours" for any wallpaper
- Tap an event to open it in your calendar app
- Updates itself when calendar data changes, at midnight and when an event ends – no polling
- English UI with German translation

## Usage

1. Install the release APK, open the app and allow calendar access.
2. Long-press the home screen → Widgets → *AgendaGo*, or use "Add widget to home screen" in the app.
3. Adjust the settings (live preview) and tap *Apply*.
4. Change settings later: long-press the widget → reconfigure, or open the app.

## Build

No local Android SDK needed: GitHub Actions runs `lintDebug` + `assembleRelease` on every push.
The APK is attached to each run as artifact `AgendaGo-release`; tags `v*` create a GitHub release.

Signing uses the repository secrets `KEYSTORE_BASE64` and `KEYSTORE_PASSWORD`
(optional `KEY_ALIAS`, `KEY_PASSWORD`; without `KEY_ALIAS` the first alias in the keystore is used).
Without secrets the release APK is signed with the debug key.

Local build: `gradle assembleRelease` (Gradle 8.11.1, JDK 17, Android SDK 36).

## Permissions

- `READ_CALENDAR` – read events
- `RECEIVE_BOOT_COMPLETED` – re-draw the widget after a reboot
- Optional, asked only when switched on: `READ_CONTACTS` (birthdays), `org.tasks.permission.READ_TASKS` (Tasks.org), `org.dmfs.permission.READ_TASKS` (OpenTasks)
- `INTERNET`, `ACCESS_NETWORK_STATE` – only used by the Exchange (EWS) source

## Support

This app weighs less than a photo. Support its development on [Liberapay](https://liberapay.com/regepower/donate).

## License

[GPL-3.0](LICENSE) – free software: use, modify and share it; derived versions must also be licensed under GPL-3.0.
