# APK size: measure, then cut

## Measurements (BootDelay release APK, bytes; A/B in one CI run)
| Variant | APK |
|---|---|
| baseline (minify+shrinkResources) | 255 292 |
| + `packaging.dex.useLegacyPackaging = true` | 138 760 |
| + exclude `kotlin/**`, `kotlin-tooling-metadata.json`, `META-INF/*.version` | 125 467 |
| + `dependenciesInfo` off | 125 467 (no effect) |
| + `-repackageclasses ''` | 125 735 (worse) |
| + Kotlin assertion flags | 125 571 (noise) |
| RecyclerView/AndroidX removed (old non-draggable list UI) | 20 804 |
| later: Material You styling | +2 216 (127 683) |
| later: nav-bar theme, tooltips, alignment | +596 (128 279) |
| later: string resources + German translation (EN default) | +1 984 (130 263) |
| later: adaptive icon + themed + notification glyph | +2 428 (132 691) |

## Measurements (AgendaGo release APK, Oct 2026, bytes; one variant per build in one CI run)
Base 120 188: dex 71 411, resources.arsc 27 128 (stored **uncompressed**, mandatory since targetSdk 30 — every string byte counts fully, EN + DE), res/ 12 297, META-INF 168.
| Variant | APK | Δ |
|---|---|---|
| `enableV3Signing = false` | 120 188 | 0 |
| `-assumenosideeffects class android.util.Log` | 119 992 | −196 |
| `-overloadaggressively` | 120 188 | 0 |
| `-allowaccessmodification -mergeinterfacesaggressively` | 120 188 | 0 |
| Kotlin `-Xstring-concat=inline` | 119 956 | −232 |
| Kotlin `-Xno-param/call/receiver-assertions` | 118 940 | −1 248 |
| AGP 8.13.0 + Gradle 8.14.3 | 121 252 | +1 064 |
| AGP 8.13 + `android.r8.optimizedResourceShrinking=true` | 121 264 | +1 076 |
Adopted: Log rule + both Kotlin flags — combined on main: 120 192 → 118 652 (−1 540 B, CI #62 → #65). Not yet measured: `aapt2 optimize --collapse-resource-names --shorten-resource-paths --enable-sparse-encoding` as a post-build step (needs re-zipalign + re-sign; safe only without `getIdentifier`). Moving long help texts to deflated assets would save only ~1.5 KB (help EN 1.5 KB → 0.8 KB deflated, DE 1.75 → 0.93) — not worth the code.
Font variants: each extra widget font needs its own row layout (~1 KB each); `sans-serif-light/-medium/-condensed` rendered identical to Standard on HyperOS (measured stroke width 6–7 px for all) — only offer families that look different on the target phone (kept: smallcaps, casual).

## Finding dead resources ("Leichen")
`python3 scripts/find-unused-res.py app/src/main`: resources referenced neither as `R.type.name` in code nor as `@type/name` in XML, style parents by `Base.` naming, translations without default string, missing translations. Plus CI lint `UnusedResources` (shown as notice annotation). Unused code needs no hunt: R8 removes it; it only costs readability.

Debug APK was 4.1 MB (zip artifact 1.5 MB) — never ship or compare against it.

Dex composition at 171 KB "defined" code: androidx 146.7 KB (RecyclerView 112 KB, core 22 KB, collection 8.7 KB), Kotlin stdlib 9.6 KB, own code 8.5 KB. Without RecyclerView: Kotlin 6.2 KB of 15.6 KB defined → Kotlin→Java or "Kotlin-lite" (plain loops instead of `mapNotNull/associateBy/sortedBy`) would save an estimated 4–5 KB (not measured).

Fixed overhead of a tiny app ≈ 20 KB: dex ~13 KB deflated, manifest ~1.4 KB, resources.arsc ~1 KB, signature block + zip headers.

Not worth it here: R8 full mode (default), optimized resource shrinking (`android.r8.optimizedResourceShrinking=true` needs AGP 8.12+, resources were ~1 KB), AAB/ABI splits (no native code).

## Report step (paste into the workflow after the release build)
Prints APK size, per-entry compressed sizes (`unzip -lv`), and dex size by package/class with `apkanalyzer dex packages --defined-only --proguard-folder app/build/outputs/mapping/release "$APK"` (awk on tab-separated columns: type, state, defs, refs, size, name; `P` rows = packages, `C` rows = classes, `<TOTAL>` row = sum). Full step in `assets/reference-app/.github/workflows/build.yml`.

## A/B lab (ready-made: `assets/size-lab/`)
Copy `size-lab.sh` to `tools/` and `size-lab.yml` to `.github/workflows/`, push a branch `size-lab`: each variant starts from a clean checkout (`git checkout -- .`), patches gradle files/ProGuard rules, builds the signed release and appends one line `name total= dex= arsc= res= meta=` to a file that the last step posts as notice annotation (readable via the check-runs annotations API, unlike job logs and artifacts, which the sandbox proxy blocks). Phase 2 switches Gradle with a second `setup-gradle` step for AGP upgrades. Delete the branch afterwards (the user does it; the proxy blocks ref deletion).

## A/B lab workflow (older matrix variant)
Matrix of cumulative variants; each job patches `app/build.gradle.kts` with a small Python script (regex-remove one knob), runs `gradle assembleRelease`, writes one line (`apk=… dex(raw/zip)=… defined=… androidx=… kotlin=… own=…`) to a result file and uploads it; a final job downloads all results and prints them sorted. Trigger via `on: push: paths: [".github/workflows/size-lab.yml"]` so it runs even before the file exists on the default branch. Delete the workflow afterwards and mention the commit.
