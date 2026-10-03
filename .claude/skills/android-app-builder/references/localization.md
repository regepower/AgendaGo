# Localization: English fallback + translations

Rule: **every user-visible string lives in `res/values*/strings.xml`; the default `values/` is English.** Android picks the translation that matches the device language (`values-de` for German) and falls back to `values/` for every other language — no code needed. Do the first translation pair from the start; retrofitting means touching every literal.

## Layout
- `res/values/strings.xml` — English, the fallback. `app_name` with `translatable="false"` (brand names).
- `res/values-de/strings.xml` — German (or the user's language; add more `values-xx/` the same way).
- Code/manifest use `getString(R.string.x)` / `getString(R.string.x, arg1, arg2)` / `android:label="@string/…"`. Views built in code (tooltips, hints, headers, button labels, notification text, channel names) are covered too — grep Kotlin for umlauts and quotes with natural-language text before pushing.

## Syntax pitfalls
- Format args: `%1$s`, `%2$d` (positional, so translations may reorder). Counts such as "Selected (N)" → `getString(R.string.header_selected, count)`.
- Escape `&` as `&amp;`, apostrophe as `\'` (or avoid it: write "does not"), double quote as `\"`. Non-ASCII may be written as `ü` etc., which also keeps the file encoding-proof.
- Strings that are identical in both languages (e.g. "Test", "Overlay") still get an entry in both files so the key set stays equal.
- Symbols like ✓ stay in code, they are language-neutral.

## Guard rails
- Lint (`MissingTranslation`, `ExtraTranslation`) fails the CI build when `values-de` lacks a key that `values/` has — that is the automatic check; keep `abortOnError = true`.
- You cannot test locale switching in the cloud sandbox. Tell the user how to check: system language to English (expect English UI) and to German (expect German UI); on Android 13+ also *Settings → Apps → <app> → Language*.
- Optional per-app language picker (Android 13+): `android:localeConfig="@xml/locales_config"` in `<application>` plus `res/xml/locales_config.xml` listing `en` and `de`. Not used in the reference app.
- Optional: `android { androidResources { localeFilters += listOf("en", "de") } }` strips library translations; irrelevant when there are no UI libraries (size effect not measured).

## Cost
Measured: moving ~20 UI strings into resources plus a full German translation added 1 984 B to the release APK (128 279 → 130 263). Verify with the CI size step name before/after (see `size-optimization.md`).

## When the user's language is not German
Take the user's language as the second `values-xx` and keep English as default. Answer the user in their language regardless; the app's fallback language is English.
