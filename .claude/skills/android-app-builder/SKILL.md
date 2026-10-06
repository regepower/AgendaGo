---
name: android-app-builder
description: Build, theme, optimize and ship native Android apps (Kotlin, Gradle KTS, no local SDK) from a cloud/CLI session, compiling and linting through GitHub Actions. Covers project skeleton and compiler/R8 options, Android 15/16 behavior (boot receivers, foreground services, background activity launch, overlay), Material You UI without libraries, and measured APK-size tuning. Use whenever the user wants a new Android app/APK, mentions Android Studio-free builds, Gradle/AGP/R8/lint errors, GitHub workflow for APKs, boot/autostart apps on Android 14-16, Material You / dynamic color / dark mode / navigation-bar styling, or APK size ("App ist zu groß", "APK verkleinern"), even if they only describe the app idea. Also trigger for app/launcher/adaptive icon, localization (strings.xml, Übersetzung, Sprachfallback) and German phrasing like "Android App bauen", "App beim Boot starten", "per GitHub Actions bauen".
---

# Android app builder (cloud-only workflow)

Distilled from building *BootDelay* (starts chosen apps with a delay after boot, works on Android 16) end to end. A complete, CI-verified project lives in `assets/reference-app/` — copy it and rename instead of writing a skeleton from memory; every file in it compiled, linted and ran on a real Android 16 device.

## Core idea: CI is your compiler

The sandbox has no Android SDK, so you cannot compile locally. Write code → push → let GitHub Actions run `lintDebug` + `assembleRelease` → read the result through the GitHub tools. Plan for 3–4 minute round trips and make each push count: re-read your diff for Kotlin/Gradle mistakes before pushing, since the user's preference is that all generated code is validated and linted (CI lint with `abortOnError = true` is that validation). Details, log-reading tricks and repo setup: `references/ci-and-validation.md`.

## Workflow

1. **Repo.** `create_repository` usually fails with 403 in these sessions → ask the user to create an (empty) repo and make sure the Claude GitHub App may access it, then `add_repo` (access `push`), clone, `git checkout -b <dev-branch>`, first push creates the branch. Never push elsewhere than the branch the session names.
2. **Skeleton.** Copy `assets/reference-app/`, rename package/applicationId/app name. Keep the verified toolchain: AGP 8.9.1, Kotlin 2.1.10, Gradle 8.11.1 (via `gradle/actions/setup-gradle`, no wrapper), JDK 17, compileSdk/targetSdk 36, minSdk 29.
3. **Strings from day one.** No literal UI text in Kotlin or the manifest: English in `values/strings.xml` (fallback for every language), the user's language in `values-xx/`. See `references/localization.md`.
4. **Feature code.** Plain framework APIs first (Activity, Service, BroadcastReceiver, SharedPreferences, code-built views). Every AndroidX/Material dependency costs real kilobytes (see size section) — add one only when it buys something you cannot do with the framework.
5. **Push, wait, read CI.** Fix by reading the actual error from the job log, not by guessing.
6. **Measure size** whenever you add a dependency or restyle; the CI shows the release APK size in a step name.
7. **Deliver** the `BootDelay-release`-style artifact (Actions run → Artifacts). Tell the user to install the *release* APK: the debug APK is ~30x bigger and users mistake it for the app size.

## Versioning
User decision (AgendaGo, Oct 2026, all apps): `versionName = "1.0.$build"`, `versionCode = maxOf(build, 1)` with `val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0` in `defaultConfig`. Every CI build is visibly numbered (#57 → 1.0.57) and installs as an update; major/minor are bumped by hand only for bigger changes. Build day: `manifestPlaceholders["buildDate"] = LocalDate.now(ZoneId.of("Europe/Berlin")).toString()` → `<meta-data android:name="build_date" android:value="${buildDate}"/>` in `<application>`, read with `getApplicationInfo(…, GET_META_DATA)` (no BuildConfig, no generated string that would trip `MissingTranslation`). **Import `java.time.LocalDate`/`ZoneId` at the top of build.gradle.kts**: written as `java.time.…` inside `android {}`, `java` resolves to Gradle's `java` extension → "Unresolved reference: time". The CI step name shows "Release APK 1.0.<run>: <bytes>". Help shows "<App> 1.0.57 · 06.10.2026" (see `references/app-shell.md` → Help).

## Compiler / build options that matter

`app/build.gradle.kts` essentials (all in the reference app):

- `isMinifyEnabled = true`, `isShrinkResources = true`, `proguard-android-optimize.txt` — R8 full mode is already the default since AGP 8.
- `packaging { dex { useLegacyPackaging = true } }` — AGP stores `classes.dex` uncompressed for minSdk ≥ 28; deflating it halves the APK (measured −116 KB of 255 KB).
- `packaging { resources { excludes += setOf("kotlin/**", "kotlin-tooling-metadata.json", "META-INF/*.version") } }` — drops Kotlin builtins metadata nobody reads at runtime (−13 KB).
- `kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }` plus `compileOptions` 17.
- Signing: **do not** write a `signing {}` block (invalid in `android {}`); create `signingConfigs.create("release")` only when `KEYSTORE_FILE` env exists, and fall back to the debug key for `release` so the APK stays installable without secrets.
- `lint { abortOnError = true }`; annotate unavoidable warnings (e.g. `tools:ignore="QueryAllPackagesPermission"`) instead of lowering the bar.
- Kotlin `freeCompilerArgs.addAll("-Xno-param-assertions", "-Xno-call-assertions", "-Xno-receiver-assertions", "-Xstring-concat=inline")` — measured on AgendaGo (120 KB): −1 248 B and −232 B; the gain grows with the amount of own Kotlin code (BootDelay at 20 KB: noise). Trade-off: a null from Java code no longer fails at the parameter but at first use.
- `app/proguard-rules.pro` with `-assumenosideeffects class android.util.Log { public static int v(...); d; i; w; e }` (−196 B on AgendaGo; drops Log strings) — register it in `proguardFiles(..., "proguard-rules.pro")`.
- Measured as useless or harmful (don't re-add): `dependenciesInfo { includeInApk = false }` (0 B), `-repackageclasses ''` (+268 B), `-overloadaggressively` / `-allowaccessmodification -mergeinterfacesaggressively` (0 B), `enableV3Signing = false` (0 B, signing block is padded), AGP 8.13 instead of 8.9.1 (+1 064 B), `android.r8.optimizedResourceShrinking=true` on AGP 8.13 (+1 076 B).
- **Dead code/resources ("Leichen") after many rebuilds:** run `scripts/find-unused-res.py app/src/main` (unused strings/colors/styles/drawables/layouts, orphan translations) and read CI lint `UnusedResources`. Unused *code* needs no hunt — R8 drops it from the APK.

## Android 15/16 behavior (autostart, background launches)

Short version: a boot receiver may start a foreground service of type `specialUse`; that service shows a 1×1 `TYPE_APPLICATION_OVERLAY` window (needs `SYSTEM_ALERT_WINDOW` granted by the user) and only then calls `startActivity` for other apps, because Android 15+ allows background activity starts for overlay holders *only while the overlay is visible*. Full recipe, manifest entries, permission flow and OEM caveats: `references/android16-background-start.md`.

## DNS-only VPN (MinDNSChanger)

`VpnService.Builder` with `addAddress` + `addDnsServer`, **no routes**, `allowFamily(AF_INET/AF_INET6)`, no packet loop: Android resolves names for the covered apps via those servers while traffic uses the normal network. FGS type `systemExempted` needs `FOREGROUND_SERVICE_SYSTEM_EXEMPTED` **and** `USE_EXACT_ALARM` (lint `ForegroundServicePermission`). Always-on via `<intent-filter android.net.VpnService>` + `SUPPORTS_ALWAYS_ON`; "Block connections without VPN" must stay off. **Always `setMetered(false)`**: for targetSdk 29+ a VPN is metered by default, so Play Store stops auto-updates on Wi-Fi; false = inherit the underlying network's metered state. Network rules through `registerDefaultNetworkCallback` (the app excludes itself from its VPN, so it sees the underlying network) and `NET_CAPABILITY_CAPTIVE_PORTAL` for login hotspots.

## Renaming an app
Display name only (`app_name`): installs over the old version, settings kept. New package ID (`namespace` + `applicationId` + source dir + `package` lines + intent action strings): Android treats it as a new app — tell the user to save the config in the old app first, then load it in the new one (`AppShell.legacyNames` accepts the old name). The GitHub repo is renamed by the user (Settings → Repository name); the proxy blocks settings writes. Done: MinCalWidget → ZenDay → AgendaGo (`de.regepower.agendago`, name clash with zen-day.de), MinDNSChanger → SwitchDNS (`de.regepower.switchdns`).

## UI / design (Material You without libraries)

Use `Theme.DeviceDefault.DayNight` as parent and map your own `md_*` colors to `@android:color/system_accent1_*` / `system_neutral*` in `values-v31` and `values-night-v31`, with fixed fallback palettes for Android 10/11. Tonal/filled buttons, rounded bordered cards, error-container for "missing permission" state, tooltips for long-press help, transparent navigation bar (`android:enforceNavigationBarContrast=false`). This cost ~3 KB total, whereas Material Components or Compose cost megabytes. Snippets and the list-selector pattern (search + selected-first/two lists + drag sort): `references/ui-material-you.md`.

## App shell (all our apps)

Same top row everywhere: app name large + bold, then icons **save config**, **load config**, **help (?)**. Help is a dialog with foldable chapters plus version, build day, donation line and GPL-3.0/FOSS note with the GitHub link; config is one JSON file via the system file dialog (JSON filter, no permission, cloud OK); the last file is remembered and overwritten after asking (Überschreiben / Anderer Ort / Abbrechen), generic `ConfigIO.kt`. Long lists show only the active entry in a card (tap = picker dialog, + / − icons). Details, icon paths and the tested ConfigIO: `references/app-shell.md`.

## App icon

Adaptive vector icon (background colour + foreground vector + monochrome for themed icons) plus a separate white notification glyph; no PNGs. Fill the r = 33 safe zone (the visible area is only the centre 72 of 108 units) — artwork scaled to ~0.83 looked too small next to other apps; scale ~1.0 and enlarge the inner glyph instead. Every app gets its own background colour. Preview drafts as a contact sheet before building the chosen one: `references/app-icon.md`.

## Localization

Default `values/strings.xml` is English and acts as the automatic fallback for any device language that has no translation; add `values-de/` (or the user's language) for the translation. Lint's `MissingTranslation` check is the CI guard that both files stay in sync. Syntax pitfalls (`&amp;`, apostrophes, positional format args) and testing advice: `references/localization.md`. Measured cost: +1 984 B for ~20 strings in two languages (128 279 → 130 263).

## Size optimization

Measured on the reference app (release APK bytes): 255 292 → 138 760 (dex deflate) → 125 467 (metadata excludes); dropping RecyclerView/AndroidX → 20 804. AndroidX was 84 % of the dex. Method (apkanalyzer report + A/B lab, ready-made in `assets/size-lab/`) and the full tables: `references/size-optimization.md`. Rule of thumb: raise the question "is this library worth N KB?" before adding it, and measure instead of estimating.

## Feedback loop with device screenshots

The user tests each CI build on their phone and replies with screenshots plus short remarks ("sieht gut aus, aber…"). Read the screenshot literally (spacing, colours, bars, truncated text), name the cause in one line (e.g. grey bar under the 3-button navigation = contrast scrim → `enforceNavigationBarContrast=false`), fix, push, report: what changed, CI status, APK size delta, artifact name. Typical asks seen: more room for the lists, status colours on buttons, right-aligned fields, long-press help, search + selected-first lists, language fallback, icon drafts. Offer numbered drafts for visual choices and measure size before/after every visual change.

## Communication style with this user

Answers short and direct, bullets, German unless they switch; no closing summaries. Research the web when an Android behavior is uncertain (cite sources), ask only when a real decision is the user's. After each milestone give: what changed, CI status, APK size, where to download.
