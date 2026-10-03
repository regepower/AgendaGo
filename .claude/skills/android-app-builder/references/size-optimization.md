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

Debug APK was 4.1 MB (zip artifact 1.5 MB) — never ship or compare against it.

Dex composition at 171 KB "defined" code: androidx 146.7 KB (RecyclerView 112 KB, core 22 KB, collection 8.7 KB), Kotlin stdlib 9.6 KB, own code 8.5 KB. Without RecyclerView: Kotlin 6.2 KB of 15.6 KB defined → Kotlin→Java or "Kotlin-lite" (plain loops instead of `mapNotNull/associateBy/sortedBy`) would save an estimated 4–5 KB (not measured).

Fixed overhead of a tiny app ≈ 20 KB: dex ~13 KB deflated, manifest ~1.4 KB, resources.arsc ~1 KB, signature block + zip headers.

Not worth it here: R8 full mode (default), optimized resource shrinking (`android.r8.optimizedResourceShrinking=true` needs AGP 8.12+, resources were ~1 KB), AAB/ABI splits (no native code).

## Report step (paste into the workflow after the release build)
Prints APK size, per-entry compressed sizes (`unzip -lv`), and dex size by package/class with `apkanalyzer dex packages --defined-only --proguard-folder app/build/outputs/mapping/release "$APK"` (awk on tab-separated columns: type, state, defs, refs, size, name; `P` rows = packages, `C` rows = classes, `<TOTAL>` row = sum). Full step in `assets/reference-app/.github/workflows/build.yml`.

## A/B lab workflow (throw-away)
Matrix of cumulative variants; each job patches `app/build.gradle.kts` with a small Python script (regex-remove one knob), runs `gradle assembleRelease`, writes one line (`apk=… dex(raw/zip)=… defined=… androidx=… kotlin=… own=…`) to a result file and uploads it; a final job downloads all results and prints them sorted. Trigger via `on: push: paths: [".github/workflows/size-lab.yml"]` so it runs even before the file exists on the default branch. Delete the workflow afterwards and mention the commit.
