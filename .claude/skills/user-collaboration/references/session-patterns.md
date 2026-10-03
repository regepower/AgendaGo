# Patterns observed (BootDelay session)

## Typical flow of a session
1. Idea in one or two sentences ("App um Apps beim Boot verzögert zu starten, Android 16, per GitHub Actions bauen").
2. Blocker handling: tool could not create the repo (403) → say so once, give the minimal steps the user must do (create repo, allow the GitHub app), continue when they confirm.
3. First working build → user installs on phone → replies with a screenshot and 3–5 wishes (list too cluttered, search, selected-first, two lists, drag sort, notification text, wait before home screen).
4. Polish rounds: space, colours, Material You, nav-bar colour, right-aligned field, long-press help, language fallback, icon drafts → each round: push, CI green, size delta, artifact.
5. Meta requests: size analysis, skills for what was learned, a skill about collaboration.

## Things that worked
- Concrete numbered options with a recommendation; they answer with a number or "Ja, setz um".
- A/B measurement table in the answer (bytes, delta) — they accept "die Größe ist OK" quickly when shown.
- One-line cause for each visual defect seen in a screenshot.
- Visual drafts rendered to a PNG (headless Chromium) and previewed before sending.

## Things to avoid
- Long explanations, restating the request, closing summaries, emojis.
- Unmeasured size/performance claims ("wenige hundert Bytes") — measure or label as estimate.
- Leaving generated files in the repo directory; pushing without running the validation step to completion; truncated previews (check the rendered image before sending).
- Asking about things a default can settle (toolchain versions, file names, colours already given).

## Language
Answer in German; code, identifiers, commit messages and skill bodies may be English. UI strings: English default + German translation (see the android-app-builder skill).
