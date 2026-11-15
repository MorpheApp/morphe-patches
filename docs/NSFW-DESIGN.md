# Permanently disable NSFW: reference behavior and validation boundary

## Reference snapshots

- Morphe source: [`44031c561ea048c8f196877f30a49c13bfd6afb6`](https://github.com/MorpheApp/morphe-patches/tree/44031c561ea048c8f196877f30a49c13bfd6afb6).
- Continuum current source inspected: [`f9e5f882b825eb381369ef74474348e23c525cdc`](https://github.com/cygnusx-1-org/continuum/tree/f9e5f882b825eb381369ef74474348e23c525cdc).
- Historical Continuum with the feature: [`5982ee770e0d508a0cb69c2ffea7706d07b2cfd9`](https://github.com/cygnusx-1-org/continuum/tree/5982ee770e0d508a0cb69c2ffea7706d07b2cfd9).

The current Continuum version removed this feature. `DisableNsfwForeverMigration.kt` explains that the old global override took precedence over each account's ordinary NSFW setting. The migration turns those switches off once rather than preserving an irreversible lock. Implementing that migration would not fulfill this project's purpose.

## Historical behavior examined

Paths below are relative to `app/src/main/java/ml/docilealligator/infinityforreddit/` in the historical snapshot.

| Area | Source | Behavior |
| --- | --- | --- |
| Activation | `settings/NsfwAndSpoilerFragment.java`, lines 90-177 | Global boolean, confirmation dialog, disabled permanent control after activation, change event with NSFW false. |
| Search | `activities/SearchActivity.java`, lines 213-225 | Global override AND ordinary account NSFW setting; excludes adult autocomplete and hides adult random-subreddit entry. |
| Posts | `fragments/PostFragment.java` | Global override forces `postFilter.allowNSFW` false for feeds and setting changes. |
| History | `fragments/HistoryPostFragment.java` | Same global override for historical posts. |
| Communities and users | `fragments/SubredditListingFragment.java`, `fragments/UserListingFragment.java` | NSFW false passed to listing view models. |
| Direct post | `fragments/ViewPostDetailFragmentNew.java`, line 1417 onward | Sensitive-content warning with a leave action. |
| Direct community | `activities/ViewSubredditDetailActivity.java`, line 610 onward | NSFW community guard. |
| Direct profile | `activities/ViewUserDetailActivity.java` | The NSFW warning is commented out; direct-profile blocking is not promised by this reference. |

The historical confirmation string states that clearing app data resets the setting. This is permanence within the current app installation's data, not protection against uninstalling, installing a different APK, root access, or relabelling by Reddit.

The user authorized blocking marked NSFW profiles as a fallback. The user additionally requested blocking RedGIFs. No keyword, subreddit-name, author-name, or image-classification rules are added.

## Current approach and unresolved coverage

`PermanentNsfwDisabler` has only read and enable operations for permanently disabling NSFW. It latches on, checks for activation by another holder of the store while still off, and reports write failure without displaying a false success. Its store is outside normal Morphe setting exports.

The candidate `OkHttpClient(Builder)` hook installs a first application interceptor before the client snapshots its interceptor list. Such an interceptor can see cached responses as well as network responses. It does not cover other transports or already-parsed local models.

The filter removes explicitly flagged entity envelopes from arrays, preserves pagination cursors, blocks a flagged root, handles nested profile/community flags and crossposts, and rejects malformed inspected JSON instead of returning the unfiltered body. It never changes an adult-content label to make the content appear safe. Account preference responses are handled separately.

The supplied 2026.45.0 base APK has been inspected for the OkHttp constructor, account viewing/search preference getters, and native adult-viewing/blur row keys. Each required anchor matched once. The settings view model filters these rows by the current session before composing them, so the patch returns an empty session set while locked. It also disables Morphe's existing adult-dialog bypass and removes that bypass preference while locked.

Synthetic fixtures are not captures of Reddit traffic. Alternative transports, aliased GraphQL fields, persisted models, and search UI state still require coverage testing. Media requests cannot be labelled from a URL alone; removing a parent entity before presentation is the intended prevention mechanism.

## Verification gates before release

1. Resolve all required fingerprints against Reddit 2026.45.0 and the latest stable recommended target using Desktop CLI `--validate`.
2. Confirm that every relevant API result reaches the filtering boundary, including search, autocomplete, feeds, post detail, profile detail, community detail, history, and cached/offline content.
3. Remove native NSFW viewing and search controls after activation, including ordinary enable and bypass actions. Confirm ordinary settings cannot override the latch.
4. Verify no adult parent is rendered, no associated media fetch is triggered by the renderer, pagination remains usable, and direct navigation cannot bypass the block.
5. Verify account switching, anonymous browsing, restarts, cancelled confirmation, failed persistence, and Morphe import/export. No USB debugging is authorized for this task.

Host command: `python tools/test-nsfw.py`. Full build command: `gradlew.bat :patches:build` (requires access to upstream build dependencies).

## RedGIFs restriction

While permanent disabling is active, the RedGIFs audio and HD preferences are removed, and the audio extension returns before starting lookups or substituting playback/download URLs, regardless of previously saved preferences. The OkHttp boundary rejects requests to `redgifs.com` and its subdomains before transport. The JSON filter also removes entities with RedGIFs domains or recognized destination/media URL fields even if their adult flag is missing. Hostname parsing preserves unrelated domains and ordinary text mentioning the site. This does not establish coverage of WebViews, redirects through other transports, or existing local media.

## Version testing status

The supplied 2026.40.0 and 2026.45.0 APKs each resolve all five original-APK anchors exactly once. Forced Desktop `--validate` on 2026.40.0 passes and rebuilds an unsigned APK using the preceding test bundle, so the patch now advertises these two versions. The final published bundle is rechecked separately; see the version matrix.

2026.10.0 was tested with `-f` rather than accepted as a gated no-op. Its existing settings dependency fails uniqueness validation (two matches), and both native-row anchors used by this feature are absent. It remains unsupported. This does not establish 2026.40.0 as the earliest possible compatible version; intermediate versions have not been tested.

## Device findings: blocking release issues

Manual testing reported on 2026-11-15 shows that the response-only boundary is insufficient. A direct marked-community link can retain its icon and show Reddit's misleading not-found state. Anonymous browsing can expose marked search results and a separate adult-search toggle; community posts can remain visible behind that error. Content also remains visible if ordinary adult viewing was enabled before permanent activation. These are confirmed failures, not pending optional checks. Existing test bundles must not be relied on as complete blockers.

Required fixes are a native presentation/navigation gate with an honest NSFW-disabled state and no underlying media, anonymous-session search control removal, clearing the saved disclaimer bypass on activation, and regression checks with preloaded content and both prior viewing-setting states. Build/fixture success alone does not close these issues.

The revision binds the native community root at its view lifecycle callback and classifies it before the view model checks `hasBeenVisited`, account age preferences or incognito status. Unknown/blocked roots are invisible; blocked roots receive an opaque explanatory dialog with back navigation. A direct rejected community-about response also notifies the gate. A flagged post alone never classifies its enclosing community. A positive community label takes priority over stale safe labels during the session.

The separate native show/hide-NSFW header is omitted after activation, and new `FilterValues` models force `safeSearch` true, including anonymous browsing. Confirmed request parameters are restricted as well. Activation clears saved warning-bypass and RedGIFs audio/HD values; the existing restart dialog restarts the app on every dismissal. These changes are implemented, not yet verified on a phone. See [version matrix](validation/reddit-version-matrix.md).
