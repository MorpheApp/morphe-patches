# Reddit NSFW controls and native UI fixes

Three independent patches address separate behaviors. All are selected by default while patching and can be unchecked separately. Including permanent disabling only adds its in-app option; activation still requires explicit confirmation. None adds a content classifier, network interceptor, JSON rewriting, custom blocked screen or runtime dependency.

- **Permanently disable NSFW content** adds irreversible local enforcement and removes re-enable controls and prompts after confirmation.
- **Prevent NSFW content flashing** reads each screen's own preference repository, including anonymous preferences. It works with the ordinary reversible NSFW setting and does not require permanent activation. Flagged posts receive Reddit's native hidden/blocked flags before the post view is built; flagged profiles are stopped before model publication. Unflagged content and NSFW viewing enabled retain native behavior.
- **Fix NSFW warning navigation** makes the native warning's button and back gesture return to the preceding page, independently of permanent activation. The warning replaces the blocked page so dismissal cannot reveal it.

The user's stock-app comparison found that communities did not flash, but posts and profiles did, and warning dismissal returned Home. This is reported device evidence, not an automated baseline test. The additional hooks fix presentation timing; they do not promise that metadata or media requests never reach Reddit's servers. Comment permalinks use the post-detail screen; no new classification rule is applied to comments on otherwise safe posts.

After confirmation, a private installation-wide preference applies to signed-in accounts, logged-out use and anonymous browsing. It has no disable operation and stays outside ordinary settings import/export. Clearing app data resets it, as in historical Continuum. Its original private preference filename is retained so test-build upgrades do not reset activation.

## Native hooks

- Flagged profile models are intercepted before the native flow publishes them to the header and feed, including cached results. The existing NSFW warning replaces the profile on the main thread when native viewing is off.
- The native warning wraps its remaining content after the opt-in button is removed. The anonymous drawer hides the accompanying Settings heading and divider along with its two adult-content switches.

- `AccountPreferences.getNsfwEnabledPreferenceState()` returns the native `Disabled` enum while active. Reddit 2026.45.0 age gating reads this state; changing only legacy `getOver18()` was insufficient.
- Legacy viewing/search getters return false, and native `FilterValues` construction forces safe search on. The independent `SafeSearch` enum serializes as native `on` while active, including previously saved anonymous-search overrides.
- The native `setOver18` method refuses true while active. Version 2026.40.0 uses the legacy model setter; version 2026.45.0 resolves the repository setter through its named continuation. No independent account/backend request is sent by the extension.
- Native adult-viewing and blur settings rows skip their shared Compose renderers. The anonymous Profile drawer uses XML switches, which are hidden when its view is created. The repository viewing getter also returns false for saved anonymous preferences. The adult-search header is omitted. Shared NSFW sheets/dialogs retain their native block, replace re-enable instructions with a permanent-disabled message, replace “No Thanks” with “Go Back”, and omit the primary opt-in button for profile, community and post entry points. The saved disclaimer bypass and RedGIFs audio/HD settings are cleared and their controls removed; their existing extension hooks also respect permanent disabling.
- Reddit's native recent-community writer receives a `Subreddit` before storing it. While permanently disabled, it skips communities whose native `over18` field is true. The drawer and full recent-history screen omit such communities already stored before activation. Other community lists are untouched.
- The native NSFW warning replaces the blocked page while preserving the preceding page. Its decline action is intercepted before either session branch can clear navigation or launch MainActivity, and invokes the sheet's existing `navigateBack` callback; its back gesture uses that same callback.
- Search banners with Reddit's own “adjust NSFW settings” or age-verification action are marked dismissed while permanent disabling is active. Other search banners remain visible. This uses the banner behavior, not English text or a network response rewrite.
- Search dropdowns composed entirely of Reddit's native SafeSearch options are omitted. Mixed dropdowns retain their other filters; the existing native SafeSearch value remains forced on.

When permanent disabling is inactive, native values and controls remain reversible. The independently selected flashing/navigation fixes still apply. Permanent disabling forces local reads and guards setters; it does not perform the same account/backend update as manually changing Reddit's setting. No content is marked nonexistent and no replacement response is constructed.

## Validation

Run `python tools/test-nsfw.py` with Python 3 and JDK 17+. It verifies failed persistence, successful activation, reloads and irreversible state without downloads or device access. The regular Gradle checks remain required.

Use Desktop `--validate` on the supplied 2026.40.0 and 2026.45.0 APKs, then run `CheckNativeNsfwPatch` on each rebuilt APK. The native-structure check verifies the modern Disabled policy, guards on the native setter, and absence of the retired filtering/overlay code. Test on the oldest recommended target with `-f` when establishing compatibility; a gated no-op is not a passing test.

Also patch each version with **Prevent NSFW content flashing** and **Fix NSFW warning navigation**, excluding permanent disabling. Run `CheckNsfwFixes rebuilt.apk --normal-off` to verify that the native preference controls both guards without the permanent patch included. Run `CheckNsfwFixes rebuilt.apk` on the full combination too. These checks verify injected bytecode, not phone rendering or frame timing.

Releases through `2.3.0-nsfw.7` are not functional passes: phone testing found content visible behind a false not-found message in earlier releases, then a search prompt and broken native warning navigation in `.7`. The old response filter and custom view hook have been removed. This revision requires phone regression testing with ordinary viewing previously on/off, direct links, account switching and anonymous browsing. Patch original APKMs, not an already modified APK.

## Reference

Historical Continuum commit [5982ee7](https://github.com/cygnusx-1-org/continuum/tree/5982ee770e0d508a0cb69c2ffea7706d07b2cfd9) supplies the installation-wide, irreversible preference behavior. The implementation uses Reddit's native off-state rather than porting Continuum's content-filtering internals.
