---
name: sync-upstream
description: Bring changes from upstream kavishdevar/librepods into Sri's fork while keeping the fork's own changes (FOSS paywall removal). Use when asked to update, sync or rebase the fork on upstream.
---

# Sync the fork with upstream

- Fork: `origin` = `https://github.com/srikanthtallapaneni/librepods`
- Upstream: `https://github.com/kavishdevar/librepods` (default branch `main`; Android rewrite work is
  on `android/rewrite`, Rust Linux app on `linux/rust`)

Work in Sri's local copy (`/Users/srikantht/Freelance/librepods`) and never commit, push or open a
PR yourself: the merge is left staged-but-uncommitted for Sri to review.

Steps:
1. `git remote add upstream https://github.com/kavishdevar/librepods.git` (if missing), then
   `git fetch upstream main`.
2. Make sure the working tree is clean (`git status`). If it isn't, stop and ask Sri; don't stash.
   Optionally create a branch if Sri wants one: `git checkout -b sync-upstream-<yyyy-mm-dd>`.
3. Merge without committing (don't rebase shared history): `git merge --no-commit --no-ff upstream/main`.
4. Conflicts to expect and how to resolve them, keeping the fork's intent:
   - `billing/FOSSBillingProvider.kt`: keep `_isPremium = MutableStateFlow(true)` and
     `queryPurchases()` setting `true`.
   - Screens and `AirPodsViewModel` where the fork removed premium gating (`isPremium` checks,
     `navigateToPurchase` banners): keep the ungated version, but take upstream's other changes.
   - `services/AirPodsService.kt` and `AACPManager.kt` (fork added `onNameReceived` and the
     `Opcodes.RENAME` receive branch): keep both sides; if upstream adds its own rename handling,
     prefer upstream's and drop the fork duplicate.
   - `android/gradle.properties`: keep upstream's file; the fork's `org.gradle.java.home` line is
     machine-specific.
5. `grep -rn "<<<<<<<\|>>>>>>>" android linux docs` must be empty.
6. Build (`build-android` skill) if possible and run the `code-reviewer` agent on the merge. Then stop
   and report to Sri: the conflicts, how each was resolved, and that `git commit` will conclude the
   merge (or `git merge --abort` undoes it).

Never push to upstream or open PRs against `kavishdevar/librepods`.
