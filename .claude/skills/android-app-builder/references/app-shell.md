# App shell shared by all our apps (header, help, config save/load)

User decision (MinDNSChanger, Oct 2026): every app gets the same top row. Reference implementation: `regepower/MinDNSChanger` → `Ui.kt` (`appHeader`, `iconButton`), `ConfigIO.kt`, `MainActivity.kt` (`saveConfig/loadConfig/showHelp`).

## Header row
- Left: app name, 24sp, bold, `md_on_container`, weight 1.
- Right, in this order: **save config** (`ic_save`, arrow down into tray), **load config** (`ic_load`, arrow up out of tray), **help** (`ic_help`, question mark in circle). 44dp `ImageButton`s, `selectableItemBackgroundBorderless`, tinted `md_primary`, `tooltipText` + `contentDescription` from strings.
- Glyphs are Material Icons paths (Apache 2.0) in 24×24 vectors, white fill, tinted in code:
  - help: `M11,18h2v-2h-2v2zM12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM12,20c-4.41,0 -8,-3.59 -8,-8s3.59,-8 8,-8 8,3.59 8,8 -3.59,8 -8,8zM12,6c-2.21,0 -4,1.79 -4,4h2c0,-1.1 0.9,-2 2,-2s2,0.9 2,2c0,2 -3,1.75 -3,5h2c0,-2.25 3,-2.5 3,-5 0,-2.21 -1.79,-4 -4,-4z`
  - save: `M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z` · load: `M9,16h6v-6h4l-7,-7 -7,7h4zM5,18h14v2H5z`
  - add: `M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z` · remove: `M19,13H5v-2h14v2z`

- **No window title bar**: `Base.AppTheme` sets `android:windowActionBar=false` + `android:windowNoTitle=true` (DeviceDefault otherwise shows a small app-name bar above our header). Secondary screens then draw their own bold title (22sp).
- Picker dialogs: two-line items — name on line 1, details on line 2 via `SpannableString` with `RelativeSizeSpan(0.75f)` + `ForegroundColorSpan(md_outline)` passed to `setSingleChoiceItems`.

## Help
`AlertDialog` with `setMessage(getText(R.string.help_text))`; `help_text` uses `<b>` section titles and `\n` line breaks, EN + DE. Sections: what the app does, setup steps, each feature in 1–2 sentences, OEM caveats, save/load. Button `help_ok` ("Verstanden"). MinCalSync also opens help automatically on first start (nothing configured yet).

## Config save/load (no permission)
- Storage Access Framework: save = `ACTION_CREATE_DOCUMENT` (`application/json`, `EXTRA_TITLE "<AppName>.json"`), load = `ACTION_OPEN_DOCUMENT` (`*/*` + `EXTRA_MIME_TYPES` json/text/octet-stream, because file managers often tag .json wrongly). Write with `openOutputStream(uri, "wt")` (truncate!).
- `ConfigIO.kt` is generic — copy unchanged: exports all entries of one SharedPreferences file with type tags (`b/i/l/f/s/ss`) plus `"app"` name and `"format": 1`; import validates the whole file first (wrong app, broken JSON or unknown type → false, nothing changed), then `clear()` + typed puts + `commit()`.
- Expose the app's store (`Prefs.sp`). Do not export device-specific state (boot counters, calendar IDs that differ per phone) — keep those in a second prefs file or skip their keys.
- After import: re-apply running services (e.g. restart the VPN), then `recreate()`.
- Tested on the JVM with a fake `SharedPreferences` and org.json built from GitHub source (Maven Central and Google Maven are blocked in the sandbox): round trip, wrong app, broken JSON, unknown type.

## "Show the active item, pick from a dialog"
Long option lists (DNS servers, calendars) take too much room inline. Pattern: one card shows the active entry (bold name + small detail), tap → `AlertDialog.setSingleChoiceItems` with all entries and a neutral "Add" button; `+` / `−` icon buttons next to the card add an entry / delete the shown one (− disabled with alpha 0.3 for built-in entries).
