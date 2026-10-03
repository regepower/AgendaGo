# BootDelay

Startet ausgewählte Apps verzögert nach dem Boot (Android 10–16).

- `BOOT_COMPLETED` → Foreground Service (`specialUse`) → 1×1-Overlay (`SYSTEM_ALERT_WINDOW`) → Apps nacheinander starten.
- Setup: Overlay-Berechtigung erteilen, Akku-Optimierung aus, Apps wählen, Delays setzen.
- Build: GitHub Actions (`.github/workflows/build.yml`), APK als Artifact; Tag `v*` → Release.
- Signierung (optional): Secrets `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
