# Material You look with framework APIs only

## Theme and colors (files in assets/reference-app/app/src/main/res/)
- `values/styles.xml`: `Base.AppTheme` parent `@android:style/Theme.DeviceDefault.DayNight` with `android:windowBackground=@color/md_surface`, `android:navigationBarColor=@color/md_surface`, `android:enforceNavigationBarContrast=false`; `AppTheme` adds `windowLightStatusBar/NavigationBar=true`. `values-night/styles.xml` redefines `AppTheme` with both `false` (resource overriding replaces the whole style, so keep shared items in `Base.AppTheme`).
- Why: with targetSdk 35+ the app is edge-to-edge; with 3-button navigation Android draws a grey contrast scrim unless `enforceNavigationBarContrast` is false. Put `fitsSystemWindows = true` on the root view so content stays out of the bars.
- Palette tokens `md_primary, md_on_primary, md_container, md_on_container, md_surface, md_outline, md_error_container, md_on_error_container`:
  - `values/` light fallback (API 29/30), `values-night/` dark fallback, error colors only there.
  - `values-v31/` (light): primary=`system_accent1_600`, on_primary=`system_accent1_0`, container=`system_accent1_100`, on_container=`system_accent1_900`, surface=`system_neutral1_10`, outline=`system_neutral2_500`.
  - `values-night-v31/`: primary=`system_accent1_200`, on_primary=`_800`, container=`_700`, on_container=`_100`, surface=`system_neutral1_900`, outline=`system_neutral2_600`.

- **Text colours** (user found grey text hard to read on HyperOS): add `md_on_surface` (`system_neutral1_900` / night `_100`) and `md_on_surface_variant` (`system_neutral2_700` / night `_200`); theme items `android:textColor` + `textColorPrimary` = on_surface, `textColorSecondary` = on_surface_variant. Secondary/hint text uses `md_on_surface_variant` — never `alpha 0.6–0.7` on default text, never `md_outline` for text.
- **Foreground-service notification**: title only (e.g. "DNS aktiv: HaGeZi"), no content text — then HyperOS shows the action button (Stop) without expanding the notification.

## Widgets
- Buttons: one shape drawable `bg_btn` (ripple + rounded rect, solid white) tinted in code via `backgroundTintList`; text color from tokens; `isAllCaps=false`, `stateListAnimator=null`, `minimumHeight=40dp`. Filled = primary/on_primary (main action), tonal = container/on_container (OK state, add " ✓" to the label), error = error_container (missing permission).
- Cards: `bg_card` = surface fill + 1dp outline stroke + 16dp corners; on the container view `setBackgroundResource` + `clipToOutline = true`.
- Section headers: bold 13sp in `md_primary`.
- Rows: `selectableItemBackground` resolved via `TypedValue` for the ripple.
- Long-press help on any view: `view.tooltipText = "…"` (API 26). Cheap and good UX for icon-less buttons and input fields.
- Number inputs with unit: `Label [123] Unit` in one horizontal row; a `View` with weight 1 between two groups pushes the second group to the right edge; `gravity = END` on the EditText puts the digits next to the unit.
- Build views in code for small apps: no layout XML parsing overhead, and fewer resources in the APK.

## Selector screen pattern (pick N items from a long list)
- Top: search field (filters name + package, case-insensitive). Below: two bordered lists — "Ausgewählt (N)" (ordered, user-sortable) and "Verfügbar" (alphabetical, filtered). Tapping moves an item between lists, so the list never jumps to the top, and no counter/icon strip is needed.
- Optional switch "show system apps": load `getInstalledApplications(0)`, mark launcher apps via `queryIntentActivities(MAIN/LAUNCHER)`; without the switch only launcher apps are offered, selected system apps are always listed (needs `QUERY_ALL_PACKAGES`).
- Icons: load `ResolveInfo.loadIcon` in a background `Thread`, post the result to the UI thread.
- Persist after every change and in `onPause`.
- Drag sorting: RecyclerView + `ItemTouchHelper` (long-press drag) works but costs ~110 KB of the APK (measured; `StaggeredGridLayoutManager` etc. are kept by consumer rules and can't be shrunk). Zero-dependency alternative (not yet built/tested here): `ListView` + framework `View.startDragAndDrop` on long click, `OnDragListener` on the list with `pointToPosition` and edge auto-scroll — ~21 KB total APK instead of ~125 KB. Offer it when size matters and tell the user the drag UX needs a device test.

## Orientation lock
`android:screenOrientation="portrait"` on the activity locks phones to portrait. With targetSdk 36 Android 16 ignores `screenOrientation`, `resizeableActivity` and aspect-ratio limits on screens ≥ 600dp (tablets, unfolded foldables) except for games; the opt-out property `PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY` is temporary (gone at targetSdk 37). So build layouts that survive landscape/wide windows even when portrait is requested. Source: developer.android.com "Behavior changes: Apps targeting Android 16 or higher".

## Heavier UI libraries — cost
RecyclerView alone ≈ +105 KB APK (incl. androidx.core/collection). Material Components / Compose were not measured; expect hundreds of KB to MBs. The whole Material You styling above: ≈ +2.2 KB, nav-bar theme ≈ +0.6 KB.

## Home-screen widgets (RemoteViews, ZenDay)
- Tight list rows: `android:includeFontPadding="false"` + `android:fallbackLineSpacing="false"` on every TextView; otherwise font padding (and emoji rows via fallback fonts) add several dp per row and a 0.5dp row padding is invisible.
- Side margins you can't remove: `AppWidgetHostView` always applies `default_app_widget_padding_*` plus the launcher's cell margin. Transparent widgets should use 0 own padding; only some launchers (Nova, Lawnchair) can switch widget padding off.
