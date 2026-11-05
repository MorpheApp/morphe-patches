# Archived public Reddit profile history

Local custom downstream of [Morphe Patches v2.0.0](https://github.com/MorpheApp/morphe-patches/releases/tag/v2.0.0), upstream commit `6cb8e6b6ee425eda8a854e026507286359f38a32`.

The patch automatically replaces Reddit's explicit hidden-public-history message with archived posts or comments in that tab. It uses [Arctic Shift's public API](https://github.com/ArthurHeitmann/arctic_shift/blob/master/api/README.md). It does not embed Rosint or a browser.

**Supported input:** official Reddit Android `com.reddit.frontpage`, **2026.40.0**, version code **2640091**. The patch deliberately advertises no other Reddit versions.

**Version .4:** archived records are individual keyed items in Reddit's existing feed. The embedded ListView, screen-height workaround, and scroll forwarding have been removed. Card appearance and discussion actions retain the .3 baseline.

**Device comparison:** S26 Ultra / Android 17 verification on u/literatemice is pending user testing. Emulator fixture results do not establish parity with Reddit's complete profile header and horizontal pager. The supplied .3 APK was inspected as a diagnostic baseline; all .4 builds use the clean supported APKM.

## Install with Morphe Manager

1. Copy `patches-2.0.0-profile-archive.4.mpp` and your unmodified Reddit **2026.40.0 APKM** to your phone. If upgrading from `.3` or earlier, import this new bundle and patch the original APKM again; importing a bundle alone does not update the installed Reddit app.
2. In Morphe Manager, open **Sources**, tap **+**, choose **Local**, and import the `.mpp`. The source is named **RedditApp custom patches**. See the [official local-source guide](https://github.com/MorpheApp/morphe-manager/blob/main/docs/patch-sources.md).
3. Use **only this custom source for Reddit**. On Manager's home screen, long-press Reddit, tap **Patch sources**, untick **Morphe Patches**, and leave **RedditApp custom patches** ticked. This bundle already includes the standard upstream patches. This per-app choice leaves other apps' patch sources unchanged.
4. Enable Expert mode if needed and select **Archived public profile history**. It is deliberately opt-in in the patch list. Its in-app setting starts enabled once the patch is selected. Select the unmodified APKM and patch it.
5. Install the resulting APK through Manager. A patched APK has a different signing key from the official app; Android may require replacing an existing official installation. Preserve any local settings you need before doing that.
6. Open a profile whose public Posts or Comments history is hidden. Archived results load automatically, with more history loading near the end of the list. A small **Unhidden · Archived history** row appears above the first restored item and scrolls away with the list. Tap it for source details. Tap a card, action, or preview to open its original Reddit discussion for voting, replying, sharing, or full media.

Toggle the feature in **Morphe settings → Miscellaneous → Archived public profile history**. Reopen the profile after turning it off to return to Reddit's ordinary view.

The `.mpp` is an unsigned **local custom source**, not an official Morphe release. Local sources do not update automatically. No test activity, instrumentation, original Reddit APK, or signing key is included in the release bundle.

### If patching fails at `ApplicationFingerprint` / `Spoof signature`

If the error report lists both **Morphe Patches** and **RedditApp custom patches**, start a new patching run with only the custom source selected for Reddit, as in step 3. Select your usual patches and **Archived public profile history** from that source. Use the original unmodified 2026.40.0 APKM, not an already patched or installed copy.

Both bundles contain the upstream signature dependency. Its first execution changes Reddit's Application superclass; a second source's copy then searches for the original superclass and can fail with `Failed to match the fingerprint: ...ApplicationFingerprint`. Disabling just the visible **Spoof signature** checkbox does not prevent it from running as a required dependency of Settings. Keep the required dependency and select one complete source instead. See Morphe's [per-app source selection and conflict guidance](https://github.com/MorpheApp/morphe-manager/blob/main/docs/patch-sources.md#choosing-sources-per-app).

If the same error persists, confirm the new error report lists only **RedditApp custom patches** and retain the full patch log for further diagnosis.

## Behavior and limits

- Activates only for a visitor's filtered Posts/Comments state. Ordinary empty tabs, the owner's tabs, flair-filtered views, invalid usernames, and the disabled setting return to Reddit's existing code.
- Does not add a fallback for blocked, suspended, or deleted accounts. Archive coverage can be incomplete, delayed, or outdated; private messages and private community access are not provided.
- Sends the viewed username to Arctic Shift. No Reddit account token, authenticated API client, or session headers are copied. Requests use HTTPS, reject redirects, and have timeouts and a response-size bound.
- Uses Reddit's bundled Reddit Sans fonts, native icons, and active theme resources for post and comment cards, relative timestamps, flair, archived scores/counts, and action rows. The profile header and tabs remain Reddit's own. Text previews remain plain text and are truncated in the feed. The only archive heading is the scrolling **Unhidden · Archived history** indicator; it is absent during initial loading, empty results, and initial errors.
- Enriches visible posts and comment parent context using batches of at most eight full archive records. Image previews, gallery covers/counts, and video posters are optional and never block readable text. Voting, replying, sharing, gallery browsing, and video playback happen in the original discussion; the archive rows do not change votes. Discussion links are constructed from validated IDs.
- Preview images are fetched without account credentials from HTTPS `i.redd.it`, `preview.redd.it`, or `external-preview.redd.it` only. Redirects are rejected; each download is limited to 6 MiB, decoded images to approximately 1.2 million pixels, and the shared image cache to 8 MiB. Spoiler/NSFW media stays masked in the archive view and opens in Reddit. Missing or failed images retain a usable media placeholder.
- Loads 25 records per page, newest first, when Reddit reports visible items within three archive records of the end. Stable profile/tab/item keys preserve native lazy-list anchors during page additions and enrichment; Reddit owns scrolling and each tab's scroll state. Loading/error/Retry/end states are scrolling footer content. A failed page pauses automatic loading until Retry. Before advancing the timestamp cursor, it resolves ties in the oldest second and deduplicates IDs. If 100 records fill a single-second query, it explicitly stops with a warning instead of silently skipping records.
- Handles loading, empty archives, retryable errors, and rate limits. At most one automatic retry is used. The provider's rate-limit headers apply across profile tabs.
- Remembers a separate controller for each profile/tab in Reddit's Compose runtime. Cancels paging and enrichment when that profile feed becomes invisible or leaves composition. Each detached row cancels only its own image request. A changed username/tab invalidates old callbacks. Recent small feeds have a two-minute memory cache, with at most eight entries and a text/metadata-size eviction budget. Archive data and preview images are not persisted to disk.
- The new UI strings are English in this first version. Upstream settings, signature compatibility, and update-disabling dependencies are included automatically.

## Build on Windows

Requirements: Git, JDK 21 (Android Studio's bundled JBR works), an Android SDK with platform 36 and build tools 36.0.0 or later, and internet access for public dependencies. The Gradle wrapper resolves its Java 17 compile toolchain. The pinned source bootstrap avoids needing GitHub Packages credentials.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:GRADLE_USER_HOME = "$PWD\.local\gradle"
.\scripts\bootstrap-profile-archive.ps1
.\gradlew.bat :patches:buildAndroid :patches:checkStringResources --max-workers=4
.\scripts\test-profile-archive.ps1
```

Bundle output: `patches/build/libs/patches-2.0.0-profile-archive.4.mpp`.

Apply source code to a local APK/APKM and perform Android SDK checks:

```powershell
.\scripts\build-profile-archive.ps1 -ApkPath 'C:\path\reddit-2026.40.0.apkm'
```

Validate the actual packaged bundle instead of loose build outputs:

```powershell
.\gradlew.bat :patches:validateProfileArchiveBundle `
  '-PredditApk=C:/path/reddit-2026.40.0.apkm' `
  '-PredditOutput=.local/output/reddit-from-bundle-unsigned.apk' `
  '-PredditBundle=patches/build/libs/patches-2.0.0-profile-archive.4.mpp'
```

For a compatibility check against another prepared local Patcher checkout, add
`'-PmorphePatcherPath=C:/absolute/path/to/morphe-patcher'`. Use an absolute path so nested composite builds resolve the same checkout, and prepare that release's declared smali/ARSCLib revisions. Adding `'-PredditSourceConflictCheck=true'` verifies that the signature dependency applied and that a fresh copy of its original fingerprint then fails, reproducing the duplicate-source conflict without executing duplicate patches on the output.

The APK output is unsigned. Use Manager for normal installation/signing. For emulator testing only, pass `-Probe` to the build script. This adds a dedicated activity and deterministic transport from `tests/android`, using Reddit's own Compose lazy-list runtime and section interface. Sign this test APK with a local test key and install it on a disposable emulator:

```powershell
adb -s emulator-5582 shell am instrument -w -e tab VISITOR_POST `
  com.reddit.frontpage/app.morphe.test.ArchiveProbe
adb -s emulator-5582 shell am instrument -w -e tab VISITOR_COMMENT `
  com.reddit.frontpage/app.morphe.test.ArchiveProbe
```

The fixture injects real MotionEvent drags/flings. It feeds actual lazy-layout positions into Reddit's visible-item event constructor. Its header is a synthetic lazy item, not the complete Reddit profile shell. Fixture network responses cover three pages, delayed previews, cancellation, empty results, error and retry. It never contacts the archive service. Release builds contain neither these fixtures nor the transport replacement.

## Native scrolling .4 verification recorded on 2026-11-05

The release replaces the native hidden-history section with one native section per indicator, card, or status. Patch-time discovery verifies Reddit's section renderer/key interface, feed-state constructor, snapshot state, RememberObserver lifecycle, and visible-item event before emitting typed bridges. Any failed match stops patching. The controller owns paging, caching, cancellation, and enrichment; non-scrolling row views own their image requests. No second Compose runtime is shipped.

- Pure checks: eligibility plus **244 core assertions and 22 native-item assertions** pass. Item checks cover immutable snapshots, profile/tab identity, stable card/footer keys, empty/error/end states, and initial-loading/footer pagination regressions.
- Disposable read-only **Pixel 10 Pro Fold emulator, API 37, x86_64 with ARM64 translation**, phone viewport 1080 × 2340 at 420 dpi: Posts, Comments, and Comments at **1.6× font scale** each pass **28 native runtime assertions**. Both tabs load **73 records across three pages** through visible-item events. The checks inject MotionEvent drags/flings; they do not use setSelection or programmatic scrolling.
- Runtime coverage includes native eligibility exclusions, a header item scrolling away in the same drag, continuing fling after release, immediate reverse drag, bounded attached rows, delayed media metadata that changes row height while retaining the visible item and exact offset, visibility-return anchors, cancellation during profile switching, disposal when a feed becomes ordinary, no stale-profile results, empty/error states, and retry recovery. The full profile's collapsing header and horizontal pager are not part of this isolated fixture.
- `:patches:buildAndroid` and `:patches:checkStringResources` pass. The packaged `.4` bundle loads **195 patches**, applies to the original supported APKM, and passes SDK DEX encoding/hierarchy validation with the same **30 stock optional-SDK findings**, without additional findings.
- The release APK built from the packaged bundle was signed with the local emulator test key, installed, and launched successfully. Reddit reached its ordinary welcome/sign-in screen; the crash buffer stayed empty. No account was signed in.
- An additional packaged-DEX audit verifies the emitted section/lifecycle interfaces, exactly one feed hook and one visible-item hook, absence of `ArchiveFeedView`, and absence of test classes in the release. The release build has no test instrumentation or transport override.
- The supplied `.3` APK is a diagnostic baseline only. Prior live u/literatemice testing stopped at Reddit's mature-content gate. No gate or account restriction is bypassed by the tests.

### S26 Ultra / Android 17 acceptance check

The user will perform this comparison. **Device-level parity remains unverified until it passes.** Rebuild from the original 2026.40.0 APKM with `.4`, using the existing Manager signing key for an update.

1. Compare an ordinary profile with u/literatemice, in both Posts and Comments. Slowly drag from the expanded header into the feed, then fling through the same boundary. The header should collapse/expand continuously without an extra swipe.
2. Reverse direction mid-scroll and swipe horizontally between tabs. Return to each tab and confirm its own visible card and offset are retained.
3. Scroll through at least three pages. Let previews finish loading while stationary; the visible card should not jump. Check the loading footer, empty/error/Retry states, and the end state when available.
4. Open an original discussion and return. Switch profiles while loading, rotate the phone, and repeat at large text size. Confirm position retention, readable cards, and no cards from the previous profile.

The fixture validates the host lazy runtime and adapter/controller together. It does not establish signed-in activation, actual profile-shell nested scrolling, horizontal tab gestures, discussion Back navigation, rotation restoration, physical refresh-rate behavior, or exact S26 parity. These remain in the device checklist.

The sections below are historical verification for earlier bundles; their old instrumentation arguments are not supported by the .4 fixture.

## Layout fix .3 verification recorded on 2026-11-05

- Reproduced the indicator-only screen with six loaded posts: an unbounded vertical constraint caused Android's `ListView` to measure only its first child, leaving the feed **48 dp high**. The previous full-screen fixture supplied an exact height and missed this case.
- The feed now supplies a finite height based on the visible window when its parent supplies an unbounded height. Existing exact/maximum constraints remain authoritative. Rows stay virtualized and the indicator still scrolls with the list.
- Added an unbounded native host around Reddit's patched Compose renderer and assertions that actual cards or empty/error status are visible. The original implementation fails this regression; the fixed implementation passes Posts, Comments, empty, and error cases. The bounded light-theme fixture also passes.
- The reported profile's live archive lookup loaded **25 posts** in the unbounded fixture. Screenshot review confirmed visible cards and previews; six touch swipes moved through later posts and removed the indicator from view.
- Eligibility checks and **244 core assertions** pass. The fixture tests the real patched renderer with a simulated surrounding shell; signed-in profile-header/pager behavior and physical-device verification remain outside this check.
- `:patches:buildAndroid` and `:patches:checkStringResources` pass. The actual `.3` bundle loads **195 patches**, applies to the supported APKM, and passes DEX encoding/hierarchy checks with the same **30 stock optional-SDK findings** and no additional findings. The release build excludes the probe fixture.

Reproduce the layout regression with a probe APK:

```powershell
adb -s emulator-5580 shell am instrument -r -w `
  -e tab VISITOR_POST -e theme dark -e fixture design -e layout unbounded -e holdSeconds 8 `
  com.reddit.frontpage/app.morphe.test.ArchiveProbe
```

Omit `layout` for the original bounded fixture. Import the `.3` source and rebuild from the original supported APKM to install this fix; an existing `.2` installation does not change just by importing a source.

## Redesign .2 verification recorded on 2026-11-05

- Eligibility checks and **244 core assertions** pass. Added coverage checks optional counts/flair/content flags, preview sizing and URL validation, gallery ordering, video posters, missing/malformed metadata, bounded batch requests, response-ID validation, cancellation, and display formatting.
- Extension compilation, `:patches:buildAndroid`, and `:patches:checkStringResources` pass. The actual `.2` bundle loads **195 patches**, applies to the supplied 2026.40.0 APKM, and passes DEX checks with the same **30 stock optional-SDK findings** and no additional findings. Host fonts, colors, and icons are checked at patch time.
- A temporary read-only Pixel 10 Pro Fold emulator (API 37, x86_64 with ARM64 translation) passed light/dark/AMOLED Posts, dark Comments, empty, and error fixtures. The indicator is hidden for empty/error states; source and Retry controls are inside the list. A native scroll assertion confirms that the indicator leaves the screen when the list scrolls.
- Screenshot review covered native fonts/icons, text wrapping, image previews, gallery counts, video poster controls, and theme colors. A **1.6x font-scale** Comments fixture passed with compact action labels and the indicator visible at the top.
- A live archive lookup after rebinding the profile at 100 ms displayed only the new author's **25 posts**, including downloaded previews. Scrolling the live feed automatically loaded a second page for **50 records**.
- The release contains no probe activity or instrumentation. UI screenshots are isolated feed fixtures, not screenshots of an authenticated complete profile. No physical-device, TalkBack-session, or signed-in profile comparison is claimed. The older Patcher 2.2.0 compatibility check below belongs to `.1`; it was not repeated for `.2`.

Reproduce a deterministic native layout check with the probe APK:

```powershell
adb -s emulator-5580 shell am instrument -r -w `
  -e tab VISITOR_POST -e theme dark -e fixture design -e holdSeconds 8 `
  com.reddit.frontpage/app.morphe.test.ArchiveProbe
```

`theme` accepts `light`, `dark`, or `amoled`; `fixture` accepts `design`, `empty`, or `error`. Omit `fixture` and supply `username` for real archive requests. The instrumentation saves top/scrolled PNGs in the app's external files directory. The fixture supplies system-bar insets; production keeps Reddit's existing profile shell in charge of its insets and header.

## Original .1 verification recorded on 2026-11-05

- The supplied APKM's base APK identifies as Reddit 2026.40.0/2640091 and passes APK signature verification with Reddit's expected signing certificate.
- `:patches:buildAndroid` and `:patches:checkStringResources`: passed.
- Eligibility tests and **207 core assertions**: passed. Coverage includes both endpoint routes, input/response validation, safe discussion URLs, 40 records sharing a page-boundary timestamp, deduplication, explicit timestamp-cap handling, empty archives, rollback after an unsuccessful page, cancellation, transient retries, socket timeouts, and rate-limit headers.
- The packaged `.mpp` loads **195 patches**, including this patch, and applies successfully to the supplied APKM. SDK DEX encoding checks pass. Its 30 optional-SDK reference findings exactly match stock Reddit; no introduced finding is accepted.
- The unchanged release `.mpp` also applies successfully with **Patcher 2.2.0** (`84b4990d8fee9041d21811a84bef51fe239c437c`), using its declared smali `ac700e1942d26a7374166dce35f6da049a10e1a5` and ARSCLib `4695b583ef51890ce283f3f2e0f6f63dff230ef0` sources. The duplicate-source diagnostic reproduces the signature fingerprint failure after the first signature dependency changes `FrontpageApplication`'s superclass. This check uses only the custom bundle for patching; the phone's full 30-patch mixed-source selection was not replayed.
- Native tests used a temporary read-only **Pixel 10 Pro Fold emulator, Android API 37, x86_64 with ARM64 translation**. Both real renderer branches displayed native cards, scrolled, handed navigation to Reddit, and removed their UI on Back. Real Arctic Shift post and comment responses also rendered successfully.
- Native disabled/exclusion checks passed against Reddit's actual presentation model and the patched runtime bridge.
- A real two-page comment lookup returned exactly **50 records**. An emulator airplane-mode check displayed a connection error and a working Retry control without crashing.
- Restoring networking and pressing Retry recovered to the native empty-archive state for a test username with no results.
- Rebinding the native view to another username after 100 ms loaded only the new profile's records; the profile-switch check passed.
- No physical Android device, signed-in live profile, or other Reddit version has been validated. The standalone fixture does not prove every constraint imposed by the real profile pager.

The validation harness checks stock optional-SDK issues against the modified APK rather than suppressing broad namespaces. Successful SDK checks for identical DEX content are cached locally by SHA-256 and SDK build-tools version.

Original input SHA-256 values (the APKs are not redistributed):

```text
APKM: 2973c2f225f0953c75526cec8697c4753ab9c7f14363a102c06b67541744abce
Base: 4662aaead006c6a8457dfa60315f5fa9a8210e742cb4769febcf3457863f8e69
Reddit signer: 970b91143813b4c9d5f3634f672c9fcaa5621b4efaaedafd6c235cbbb869736f
```

The source bootstrap records exact revisions for the Morphe Gradle plugin, patcher, patch/extension libraries, smali, and ARSCLib, plus the small Windows/authentication fixes required for local builds. `LICENSE` and `NOTICE` are retained from upstream and apply to this derivative.
