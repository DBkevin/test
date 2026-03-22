# Repository Guidelines

## Project Structure & Module Organization
This repository is a single-module Android app. Main code lives under `app/src/main/java/com/example/a11yframework`, with feature areas such as `capture`, `search`, `appplugin`, and `rule`. Android resources are in `app/src/main/res`, plugin/rule assets are in `app/src/main/assets`, and the manifest is `app/src/main/AndroidManifest.xml`.

Unit tests live under `app/src/test/java/com/example/a11yframework`. Operational notes and handoff docs are in `docs/`. Utility scripts and simulators are in `tools/`. Keep `artifacts/` for retained debug samples and CI-downloaded build outputs, not for large ad hoc dumps.

## Build, Test, and Development Commands
Use Gradle from the repo root:

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
```

`assembleDebug` builds the debug APK. `testDebugUnitTest` runs JVM unit tests in `app/src/test`. `connectedDebugAndroidTest` is for device-backed instrumentation tests when a phone is attached.

This repository does not treat local compilation as the source of truth. Use GitHub Actions as the default verification path, and do not claim a build passed unless the cloud workflow passed. `.github/workflows/build-apk.yml` builds `app-debug.apk` on pushes to `main`, `master`, and `codex/**`. After a successful run, download artifacts with `gh run download <run-id> -n app-debug`.

## Coding Style & Naming Conventions
Use Kotlin with 4-space indentation and standard Android/Kotlin style. Class and object names use `PascalCase`; functions, properties, and local variables use `camelCase`; constants use `UPPER_SNAKE_CASE`. Keep package names lowercase and aligned with existing folders. Name tests after the subject under test, for example `RuleParserTest` or `DouyinPluginParsingTest`.

## Testing Guidelines
Prefer focused unit tests for parsing, rule evaluation, and dedup logic. Add tests beside the relevant package in `app/src/test/java`. New test files should end with `Test.kt`. For automation-flow changes, include the related artifact or XML sample needed to reproduce the case.

For Douyin end-to-end tests, follow the fixed route from `docs/douyin_test_runbook.md`: cold-start Douyin, enter `团购` only if it is not already selected, open the group-buy search entry, search the merchant name, tap the upper half of the merchant card, close coupon popups immediately, then expand the merchant’s group-buy list until `收起` appears.
Treat Douyin taps as region-based by default. Prefer bounds or documented tap ranges over one hard-coded pixel, especially for the home `团购` tab, the group-buy search entry, the merchant-card upper half, and `展开更多` / `展开全部`.
When adding a new list-page collector, a new page boundary, or a new stop condition, do a real-device manual-assisted replay first. Capture screenshots, then XML, derive the valid click region and stop markers, and only then update code.

## Commit & Pull Request Guidelines
Follow the repository’s existing conventional style: `ci: ...`, `chore: ...`, `refactor: ...`, `fix: ...`. Keep subjects short and imperative. PRs should summarize the user-visible or automation-flow impact, list verification steps, and link any relevant issue or doc. For UI/automation changes, include screenshots, XML captures, or the GitHub Actions run used to validate the build.

## Security & Agent Notes
Do not commit secrets, personal tokens, or unnecessary large artifacts. Prefer GitHub Actions for build verification and `gh` for artifact retrieval. If local Android tooling is missing, state that explicitly instead of implying local verification. When testing on-device, confirm `adb devices` is stable before install or launch steps, then install the latest CI-built APK rather than stale local files.

For this repository, treat the Douyin collection flow as project memory, not disposable context. Do not replace the documented route with heuristic shortcuts without updating the runbook first.
When changing code or the runbook for a verified real-device flow, commit and push the update promptly so the local worktree stays aligned with GitHub and the desktop app does not accumulate a large dirty state.
Treat pulled screenshots, XML dumps, logs, and CI artifact downloads as transient by default. Delete useless captures after analysis, and keep them out of Git unless they are promoted into a documented baseline asset on purpose.
