# Starting apps after boot on Android 15/16

Known recipes broke because background activity launches are restricted (since 10, tightened in 14/15/16). What worked on a real Android 16 device:

```
BOOT_COMPLETED receiver
  └─ startForegroundService(LaunchService)         // type specialUse
        ├─ startForeground(id, notification, FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        ├─ wait initial delay (Handler.postDelayed)
        ├─ WindowManager.addView(1x1 View, TYPE_APPLICATION_OVERLAY, NOT_FOCUSABLE|NOT_TOUCHABLE)
        ├─ for each package: Toast "Starte <App> (i/n)", startActivity(launchIntent + FLAG_ACTIVITY_NEW_TASK), wait gap
        ├─ wait gap once more, startActivity(HOME intent)  // user ends on the home screen
        └─ removeView(overlay), stopForeground, stopSelf
```

Why: apps targeting 15+ may start activities from the background if they hold `SYSTEM_ALERT_WINDOW` **and** currently show a visible overlay window. `BOOT_COMPLETED` exempts the receiver for starting a foreground service, but not certain FGS types (dataSync, mediaPlayback, camera, microphone, phoneCall, mediaProjection …) — use `specialUse`. Sources: developer.android.com "Restrictions on starting activities from the background", "…foreground service from the background", "Behavior changes: Apps targeting Android 15".

## Manifest checklist
```xml
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" tools:ignore="QueryAllPackagesPermission" /> <!-- sideloaded launcher-style apps only -->

<receiver android:name=".BootReceiver" android:exported="true">
  <intent-filter><action android:name="android.intent.action.BOOT_COMPLETED" /></intent-filter>
</receiver>
<service android:name=".LaunchService" android:exported="false" android:foregroundServiceType="specialUse">
  <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="…why…" />
</service>
```

## User-facing setup (expose as buttons with live status)
- Overlay: `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` + `Uri.parse("package:$packageName")`; check `Settings.canDrawOverlays()` in `onResume`.
- Battery: `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; check `PowerManager.isIgnoringBatteryOptimizations`.
- Notifications: runtime `POST_NOTIFICATIONS` request on first start.
- A "Test" button that runs the same service immediately saves a reboot per iteration.
- Some OEMs (e.g. Xiaomi/HyperOS) need "Autostart" enabled in system settings; mention it, the app cannot do it.

## Implementation notes
- Guard re-entry (`running` flag) and `START_NOT_STICKY`.
- If overlay permission is missing or the list is empty, finish immediately (still after `startForeground`, or the system kills the app for not calling it in time).
- Order of apps = user's order; persist as newline-joined package names in SharedPreferences; prune uninstalled packages on load.
- Launch intents via `packageManager.getLaunchIntentForPackage`; label via `getApplicationLabel` for the notification.
- Progress per app is shown as a **Toast** (`Toast.makeText(...).show()` from the main-looper Handler; plain text toasts are still allowed from the background). The foreground service must still have its notification — keep it static (title only) instead of updating it per app.
## Trigger hygiene: run only on a real boot or the Test button
Seen on a Xiaomi/HyperOS phone: opening the app again and pressing Home started the whole autostart sequence again, although the service is only started from `BootReceiver` and the Test button. Treat that as a **re-delivered `BOOT_COMPLETED`** (OEM behavior; cause not provable from the sandbox) and guard against it:
- `BootReceiver` runs once per boot: compare `Settings.Global.BOOT_COUNT` (API 24, readable without permission) with the value stored when the last run started, plus `SystemClock.elapsedRealtime() >= storedUptime` as a check against a stale counter. Log and return on duplicates.
- On first start of an updated app, `markBootHandled()` stores the current boot, so a stray broadcast right after install/update cannot trigger a run. Don't mark it on every app open: a real boot where the user unlocks and opens the app before the broadcast would be suppressed.
- Start the service with an explicit source extra (`boot` / `test`); a start without a valid source calls `startForeground` (mandatory after `startForegroundService`) and finishes immediately.
- Test button = same sequence **without the initial delay** (gap between apps stays), so the user can verify the setup in seconds.
- No uptime cut-off (e.g. "ignore after 10 min"): users may unlock hours after a boot and still expect the autostart.

- Reference implementation: `assets/reference-app/app/src/main/java/de/regepower/bootdelay/LaunchService.kt`.
