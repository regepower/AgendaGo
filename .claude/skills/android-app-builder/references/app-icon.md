# App icon without PNGs (adaptive vector icon)

With minSdk ≥ 26 (here 29) one XML set replaces all launcher PNGs. Measured cost of the full icon set (foreground + monochrome + notification glyph + adaptive XML, replacing a simple old vector): +2 428 B (130 263 → 132 691).

## Files
- `res/mipmap-anydpi/ic_launcher.xml`: `<adaptive-icon>` with `<background android:drawable="@color/ic_launcher_background"/>`, `<foreground android:drawable="@drawable/ic_launcher_foreground"/>`, `<monochrome android:drawable="@drawable/ic_launcher_monochrome"/>` (themed icon, Android 13+; only the alpha channel is used). Use `mipmap-anydpi`, not `-v26` (lint flags the redundant qualifier at minSdk 26+). Manifest: `android:icon="@mipmap/ic_launcher"`.
- `drawable/ic_launcher_foreground.xml`: `VectorDrawable` 108dp × 108 viewport. Path data is plain SVG syntax (arcs `A`, round caps via `strokeLineCap`, circles as two arcs).
- `drawable/ic_notification.xml`: separate status-bar glyph, 24dp, white only. Never reuse the colored launcher icon as `setSmallIcon` — Android renders notification icons as a mask, colored art turns into a blob. Trick: same paths, `viewportWidth=72` and a `<group translateX=-18 translateY=-18>` to crop the 108 grid to its centre.
- Background as a color resource (`ic_launcher_background`), user picks the hex.

## Geometry rules (learned the hard way)
- The visible area is the centre 72 of 108 units (circle masks r = 36); the guaranteed-safe zone is a circle of r = 33. A design drawn on the full 108 grid gets clipped — wrap the art in `<group pivotX=54 pivotY=54 scaleX=0.83 scaleY=0.83>` or draw it smaller. Check the farthest point of the artwork (arrow tips, badges) against r = 33.
- Preview before pushing: render an SVG twin of the vector clipped to `viewBox="18 18 72 72"` as circle (r 36) and squircle, with a dashed r = 33 guide, plus a 48 px version and the white notification glyph. Headless Chromium from `/opt/pw-browsers/chromium-*/chrome-linux/chrome --headless --no-sandbox --screenshot=out.png --window-size=W,H file://…` renders it (use window height ≥ the page height + ~100, or the bottom is cut off). No cairosvg/PIL in the sandbox.
- Offer 4–6 numbered drafts in one contact sheet (large rounded square + small circle + caption) and let the user choose by number and colour; then build only the chosen one.

## Size: use the safe zone, prefer filled shapes
The only rule is the safe zone (Android adaptive icons: 108dp layer, 72dp visible, 66dp diameter guaranteed). Our first icons (scale 0.83, outlines) used only ~60 % of it and looked small next to e.g. Zepp. MinDNSChanger fix: shield path touching r ≈ 33 at scale 1.0, **filled** in the accent colour, inner glyph drawn in the background colour (`@color/ic_launcher_background` works as `strokeColor`) and scaled ×1.3. The themed (monochrome) icon only uses alpha, so it keeps the outline variant.

## Colours in use (keep them distinct)
BootDelay `#B10010` (red) · MinCalSync indigo · MinCalWidget teal · MinDNSChanger `#5B2A86` (violet, chosen from violet/petrol/dark blue/anthracite drafts). Accent `#FFC857` (amber) on all.

## After installing
Launchers cache icons: if the old icon stays after an update, uninstall and reinstall once. Themed icons only appear when the user enables "Themed icons" in the wallpaper/launcher settings (Android 13+).

## Design notes from BootDelay
Concept "delay × app list": 270° arc with arrowhead (counter-clockwise restart arrow) in accent amber `#FFC857` around a white bullet list, on user colour `#B10010`. Alternatives that were drafted: arrow around 3×3 app grid, grid + clock badge, stopwatch with list, fading tiles with progress ring, grid with clock in the centre.
