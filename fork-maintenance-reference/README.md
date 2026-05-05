# Fork Maintenance Reference

This folder records the fork-specific maintenance and release process for this repository. It is meant to be used when syncing from upstream, applying custom defaults, and publishing a fork release again later.

## Current Fork Goals
- Sync from upstream before making changes.
- Keep the forked YouTube and YouTube Music defaults applied.
- Release from the fork without depending on MorpheApp private secrets.
- Keep `patches-bundle.json` updated for Morphe Manager.

## What Was Changed In This Run
- YouTube package default changed to `com.google.android.apps.youtube.kids`.
- YouTube Music package default changed to `app.revanced.android.apps.youtube.music`.
- Preferred settings defaults were enabled for video, seekbar, swipe controls, and shorts behavior.
- Environment check warnings were disabled in the patch logic.
- Release workflow was made fork-safe.
- Release metadata was updated to the published fork release.

## Files Commonly Touched
- `patches/src/main/kotlin/app/morphe/patches/youtube/misc/gms/Constants.kt`
- `patches/src/main/kotlin/app/morphe/patches/music/misc/gms/Constants.kt`
- `extensions/youtube/src/main/java/app/morphe/extension/youtube/settings/Settings.java`
- `settings.gradle.kts`
- `.github/workflows/release.yml`
- `.releaserc.js`
- `.releaserc` was removed because it still referenced the old Gradle semantic-release plugin.
- `package.json`
- `patches-bundle.json`
- `CHANGELOG.md`

## Upstream Sync Routine
Run these commands when you want to rebase the fork on the latest upstream state:

```powershell
git fetch upstream
git checkout main
git merge --ff-only upstream/main
git push origin main
```

## Release Routine For This Fork
1. Make the desired fork changes.
2. Commit using a semantic-release trigger commit, usually `feat:` for a minor release or `fix:` for a patch release.
3. Push `main` to the fork.
4. Wait for GitHub Actions release workflow to run.
5. Verify the GitHub release tag exists.
6. Update `patches-bundle.json` to point at the new fork release.
7. Push the bundle update.

## Release Problems Found And Fixed
### 1. Old `.releaserc` file
- The repository still had a JSON `.releaserc` that referenced `gradle-semantic-release-plugin`.
- That made semantic-release fail with `Cannot find module 'gradle-semantic-release-plugin'`.
- Fix: remove `.releaserc` and keep the `.releaserc.js` fork config only.

### 2. Invalid workflow expressions
- GitHub Actions does not allow `secrets.*` directly inside `if:` expressions.
- Fix: move secret values into job-level `env` variables and check `env.*` in the `if:` conditions.

### 3. Missing release tag history on the fork
- Semantic-release kept trying to create `v1.0.0` because the fork did not have the upstream tag history.
- Fix: push the existing upstream release tag to the fork first, then trigger a new release run.

### 4. Tag history not visible to the runner
- The workflow needed tag history available before semantic-release ran.
- Fix: checkout with full history and fetch tags explicitly before the release step.

## Successful Release Result
- Published GitHub release tag: `v1.26.0`
- Release commit: `0541de64`
- Release URL: `https://github.com/atanuroy22/morphe-patches/releases/tag/v1.26.0`
- Latest bundle file: `patches-bundle.json`
- Bundle download URL currently points to the GitHub release archive URL for `v1.26.0`.

## Verification Commands
Check the latest release tag:

```powershell
git tag --list "v1.26.0"
```

Check the last few commits:

```powershell
git log --oneline -3
```

Inspect the release page:

```text
https://github.com/atanuroy22/morphe-patches/releases/tag/v1.26.0
```

Inspect the bundle source:

```text
https://raw.githubusercontent.com/atanuroy22/morphe-patches/refs/heads/main/patches-bundle.json
```

## Notes For The Next Release
- If semantic-release tries to start at `v1.0.0` again, check that tag history exists on the fork and that the workflow is fetching tags.
- If a new release should publish a built `.mpp` asset instead of the GitHub source archive, the release workflow will need an additional build-and-upload path.
- `workflow.md` should remain unchanged; use this folder for future reference instead.
