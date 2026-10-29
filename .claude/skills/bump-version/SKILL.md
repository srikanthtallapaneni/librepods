---
name: bump-version
description: Bump the LibrePods Android version (version.properties major/minor/patch/stage/build) and verify the resulting versionName/versionCode. Use when preparing a release or when asked to bump the version.
---

# Bump the Android version

Version lives only in `android/version.properties`:

```
major=1
minor=0
patch=4
stage=stable      # alpha | beta | rc | stable
build=1           # 1..99
```

`android/buildSrc/src/main/kotlin/versioning.gradle.kts` derives:
- `versionName` = `M.m.p` for stable, otherwise `M.m.p-<stage>.<build>`.
- `versionCode` = `M*100_000_000 + m*1_000_000 + p*10_000 + stageCode*100 + build`
  with stageCode alpha=0, beta=1, rc=2, stable=3. Each field is range-checked (0..99, build 1..99).

Steps:
1. Decide the new version with the user if not given. Patch for fixes, minor for features.
   For pre-releases, set `stage` and increase `build`; reset `build=1` when the stage or number changes.
2. The versionCode must go **up** compared with the last release, or Play upload is skipped/rejected.
   Compute it by hand from the formula above and show old → new.
3. If a JDK/SDK is available, confirm with `cd android && ./gradlew -q printAppVersion`.
4. Leave the change uncommitted for Sri; suggest committing only this file as `android: bump version`.
5. Don't edit `.github/workflows/ci-android.yml` release logic as part of a bump. Note that pushes to
   `main` in that workflow create GitHub releases and upload to Play using secrets the fork may not
   have.
