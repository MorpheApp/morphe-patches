# Permanently disable native NSFW viewing

This opt-in patch enforces Reddit's existing NSFW-off policy and removes the controls that can reverse it. It adds no content classifier, network interceptor, JSON rewriting, custom blocked screen or runtime dependency.

After confirmation, a private installation-wide preference applies to signed-in accounts, logged-out use and anonymous browsing. It has no disable operation and stays outside ordinary settings import/export. Clearing app data resets it, as in historical Continuum. Its original private preference filename is retained so test-build upgrades do not reset activation.

## Native hooks

- `AccountPreferences.getNsfwEnabledPreferenceState()` returns the native `Disabled` enum while active. Current Reddit age gating reads this state; changing only legacy `getOver18()` was insufficient.
- Legacy viewing/search getters return false, and native `FilterValues` construction forces safe search on.
- The native `setOver18` method, resolved through its named continuation, refuses true while active. No independent account/backend request is sent by the extension.
- Native adult-viewing and blur settings rows return no supported sessions, and the adult-search header is omitted. The saved disclaimer bypass and RedGIFs audio/HD settings are cleared and their controls removed; their existing extension hooks also respect permanent disabling.

When inactive, native values and controls retain their original behavior. Reddit handles all content decisions through its normal off-state logic; this patch does not mark content as nonexistent or construct replacement responses.

## Validation

Run `python tools/test-nsfw.py` with Python 3 and JDK 17+. It verifies failed persistence, successful activation, reloads and irreversible state without downloads or device access. The regular Gradle checks remain required.

Use Desktop `--validate` on the supplied 2026.40.0 and 2026.45.0 APKs, then run `CheckNativeNsfwPatch` on each rebuilt APK. The native-structure check verifies the modern Disabled policy, guards on the native setter, and absence of the retired filtering/overlay code. Test on the oldest recommended target with `-f` when establishing compatibility; a gated no-op is not a passing test.

Releases through `2.3.0-nsfw.6` are not functional passes: phone testing found content visible behind a false not-found message. The old response filter prevented Reddit from seeing community metadata, and a custom view hook ran on detach. Both systems have been removed. This revision requires phone regression testing with ordinary viewing previously on/off, direct links, account switching and anonymous browsing. Patch original APKMs, not an already modified APK.

## Reference

Historical Continuum commit [5982ee7](https://github.com/cygnusx-1-org/continuum/tree/5982ee770e0d508a0cb69c2ffea7706d07b2cfd9) supplies the installation-wide, irreversible preference behavior. The implementation uses Reddit's native off-state rather than porting Continuum's content-filtering internals.
