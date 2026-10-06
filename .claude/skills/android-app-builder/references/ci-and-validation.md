# CI as compiler: repo setup, workflow, reading results

## Repo and access
- `mcp__github__create_repository` returned `403 Resource not accessible by integration` → the user creates the repo (empty is fine, private is fine).
- `add_repo` fails with "not found / no access" until the Claude GitHub App is allowed for that repo (user: GitHub → Settings → Applications → Claude → Repository access). Retry `add_repo` afterwards with `access: "push"`, then clone **once** (`git clone --depth 1`), then `register_repo_root`.
- A fresh repo is empty: `git checkout -b <session-branch>`, commit, `git push -u origin <session-branch>`. That push creates the branch. Do not create PRs unless asked.
- Commit trailer from the session reminder (Co-Authored-By + Claude-Session) on every commit.

## Workflow file (verified, see assets/reference-app/.github/workflows/build.yml)
Order of steps matters:
1. `actions/checkout@v4`, `actions/setup-java@v4` (temurin 17), `gradle/actions/setup-gradle@v4` with `gradle-version: "8.11.1"`.
2. `gradle --no-daemon lintDebug assembleDebug` (lint is the code validation).
3. Release build (`assembleRelease`) — optional keystore from secrets `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, decoded to `$RUNNER_TEMP`, exported as `KEYSTORE_FILE`.
4. **Size report must run after the release build** (my first attempt ran before it and failed with "no such file").
5. Upload artifacts; tag `v*` → `softprops/action-gh-release@v2` attaches the release APK.

Pitfalls seen:
- `android-actions/setup-android@v3` crashed on the Node 24 runner. `ubuntu-latest` already ships the SDK (`$ANDROID_HOME`, `cmdline-tools/latest/bin/apkanalyzer`, build-tools) — just don't install anything.
- No Gradle wrapper in the repo is fine with `setup-gradle` + `gradle-version`; call plain `gradle`.
- `if: always()` on report/lint-artifact steps so failures still leave evidence.

## Reading results with the GitHub tools (cheap → expensive)
1. `actions_list list_workflow_runs` (`perPage: 1`) → status/conclusion of the newest run. Wait for CI with `sleep 200–230` in Bash, not by polling in a tight loop.
2. `actions_list list_workflow_jobs` → step names, conclusions. **Trick:** put numbers into a step name (`name: "Release APK: ${{ env.APK_BYTES }} bytes"`, value written earlier via `echo "APK_BYTES=…" >> $GITHUB_ENV`). They show up here with zero log reading.
3. `get_job_logs` with `return_content: true` and a `tail_lines` just big enough. The signed log URL (blob storage) is blocked by the egress proxy, so `curl` on it fails with 403. Post-job steps (setup-gradle cleanup) add ~100 noise lines at the end, so when you need a report block either print it compactly or count ~230 lines back. Gradle/Kotlin compile errors appear as `e: file:///…/MainActivity.kt:LINE:COL message`.
4. `list_workflow_run_artifacts` shows artifact zip sizes (zip of the APK, slightly smaller than the APK).

For experiments (A/B size matrix) a throw-away `workflow_dispatch`/path-filtered-push workflow with a job matrix + a final `report` job that downloads tiny result artifacts and prints one table (no Gradle in that job → short log tail) worked well; delete it afterwards.

## Before/after measurements in one go
To compare a change against a baseline in the same pipeline, push the *instrumentation* commit (e.g. the size-in-step-name step) and the *change* commit back to back: GitHub starts one run per commit, each builds its own SHA, and both finish within the same ~4 minutes. Read both runs' step names. No rebuild of an old commit needed.

## Repo hygiene (the session's stop hook checks it)
- Never leave scratch or packaging output (`*.skill`, preview PNGs, drafts) inside the cloned repo directory: the stop hook reports untracked files and demands commit + push. Write such files to the scratchpad or a sibling directory, and send them with the file tool.
- If a stray file already sits in the repo: delete it when it is only a delivery copy, commit it when the user wants it versioned.
- Chain validation and publishing with `&&` and check the *whole* output: a failed `quick_validate` (e.g. skill description over 1024 chars) must stop the push. Set variables (paths) in the same command block in which they are used.

## When job logs and artifacts are unreachable (gh CLI sessions)
`gh run view --log-failed` and artifact downloads redirect to blob storage, which the egress proxy blocks (403). Make the workflow put the evidence into **annotations**, which `gh run view <id>` prints: tee the Gradle/ktlint output to `$RUNNER_TEMP/build.log`, then an `if: failure()` step greps `^e: |: Error: |error: |What went wrong|(standard:` plus `lint-results-debug.txt` (`lint { textReport = true }`) and emits one `::error title=Build errors::…` (newlines as `%0A`). Reference: `regepower/MinDNSChanger/.github/workflows/build.yml` (ktlint step + annotations + APK size in a step name, read with `gh run view <id> --json jobs -q '.jobs[0].steps[].name'`).
- ktlint 1.5.0 from GitHub releases with `.editorconfig` `ktlint_code_style = android_studio`; locally `ktlint -F` before pushing.
- Local compile check: kotlinc from GitHub releases + `android.jar` from `raw.githubusercontent.com/Reginer/aosp-android-jar/main/android-36/android.jar`, generated `R` stub, Java stubs for AndroidX classes (Google Maven is blocked), `-Werror`.


## Artifact storage
Upload only the release APK (`retention-days: 30`) and the lint report (`retention-days: 7`); never the debug APK (≈4 MB per run, BootDelay had collected 53 MB of artifacts). The repositories themselves stay small (≈130–230 KB incl. history); old artifacts and runs can only be deleted by the user in the GitHub UI (Actions → run → Artifacts / ⋯ → Delete), the proxy blocks those API calls.
