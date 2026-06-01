# YouTube floating overlay proposal

This draft summarizes a local proof of concept for a YouTube floating video overlay
that can be converted into a Morphe patch.

It intentionally does not include APKs, signing keys, device logs, screenshots, or
any user data. The local proof of concept was implemented against YouTube
`20.47.62`, but the upstream patch must be implemented using Morphe's Kotlin patch
and extension architecture before it is submitted as a pull request.

## Contribution target

- Repository: `MorpheApp/morphe-patches`
- Base branch: `dev`
- App: YouTube
- Supported version validated locally: `20.47.62`
- Suggested patch name: `Floating overlay`
- Suggested category: YouTube player / background playback
- Related existing patch: `Remove background playback restrictions`
- Related public issues found during duplicate check:
  - `#994`: Picture in Picture / background playback mode bug.
  - `#444`: Background playback is audio only.
  - `#1196`: Background playback and PiP not functioning properly.

## Feature summary

Add an optional floating overlay for regular YouTube videos when playback continues
after leaving the app. The overlay behaves like a small movable video window and is
intended for users who want visible video playback outside YouTube when background
playback is active.

The root problem this feature addresses is that the expected floating player/PiP
does not appear on some devices even though the media session and background audio
remain active. Users can hear playback after leaving YouTube, but they do not get a
visible floating video surface to continue watching.

The local proof of concept includes:

- Floating `APPLICATION_OVERLAY` window.
- Live video surface reparenting into the overlay.
- Manual close button.
- Button to return to YouTube.
- Play/pause button using the active media session.
- Resize control with persisted size.
- Drag movement with persisted position.
- Auto-hide for overlay controls.
- Shorts/Reels guard to avoid creating a black overlay for vertical Shorts.
- Minimal dark vector controls with no emoji font dependency.
- Free movement after drag release, while keeping resize clamped.

## Local validation summary

Validated locally on an Android 15 device:

- Regular YouTube video opened by external URL: overlay appears.
- Regular YouTube video opened from the YouTube app/search UI: overlay appears.
- Real Short opened from the YouTube home Shorts shelf: overlay does not appear.
- Overlay controls auto-hide and reappear on touch.
- Close button stops the overlay.
- Return button brings YouTube back to foreground.
- Play/pause button toggles the active media session between paused and playing.
- Resize changes the overlay size and persists it.
- Drag movement persists without snapping back to screen edges.

No private account identifiers, media titles, screenshots, keystores, API keys, or
device-specific logs should be included in a PR.

## Implementation notes from the proof of concept

The proof of concept was initially built as direct smali edits against a decompiled
APK. For upstream, these changes should be ported into Morphe's normal structure:

- Kotlin bytecode patch in `patches/src/main/kotlin/app/morphe/patches/youtube/...`
- Java/Kotlin extension code in `extensions/youtube/src/main/java/app/morphe/extension/youtube/...`
- Patch settings under the existing YouTube settings system.
- Resource and manifest changes through Morphe patch APIs, not by committing
  decompiled app files.

### Runtime classes from the proof of concept

The local version used these concepts:

- `FloatingOverlayStarter`
  - Checks active playback.
  - Starts the overlay service on `onUserLeaveHint`.
  - Stops or suppresses overlay when Shorts UI is detected.
- `FloatingOverlayService`
  - Owns the `WindowManager` overlay.
  - Adds/removes the floating view.
  - Persists position and size.
  - Hosts close, return, play/pause, and resize controls.
- `FloatingIconButton`
  - Draws minimal dark circular controls directly with `Canvas`.
  - Avoids emoji rendering differences for play/pause icons.
- `FloatingOverlayDragTouchListener`
  - Handles drag.
  - Clamps inside screen bounds.
  - Saves position on release without edge snapping.
- `FloatingOverlayResizeTouchListener`
  - Handles resize presets and bounds.
- `FloatingOverlayHideControlsRunnable`
  - Hides tagged controls after a short delay.

Class names above are intentionally generic. Final upstream names should follow the
existing extension package style, for example:

`app.morphe.extension.youtube.patches.FloatingOverlayPatch`

## Hooking strategy

The proof of concept hooked `onUserLeaveHint()` of the main YouTube watch activity.
For upstream, this should be implemented by fingerprinting the equivalent method
and inserting a static extension call.

The upstream patch should avoid hardcoding a single obfuscated class name where a
fingerprint can be used instead. The local proof of concept found that current
YouTube versions can use the same activity class for both regular watch pages and
Shorts, so activity-class filtering alone is not reliable.

## Shorts guard

The Shorts/Reels case is the main correctness risk.

Observed behavior:

- Regular videos and Shorts can both appear under the same app activity.
- URL-based filtering such as `/shorts/` is insufficient because real Shorts can be
  opened directly from the app without exposing that URL shape to the hook.
- Activity-class filtering is too broad and blocks regular videos opened from the
  YouTube app UI.

The local proof of concept uses the visible player surface orientation as the more
reliable guard:

- Regular videos use a horizontal surface and can create the overlay.
- Shorts use a vertical/tall surface and should suppress the overlay.

Upstream implementation should prefer existing Morphe player state helpers if they
can distinguish Shorts reliably, such as `ShortsPlayerState`, before falling back
to view/surface inspection.

## Privacy and security review

This feature must not:

- Upload APKs, logs, screenshots, or personal data.
- Include local keystores or signing material.
- Include API keys copied from a decompiled APK.
- Read account information, email addresses, cookies, or tokens.
- Bypass Android permission prompts silently.
- Create a hidden overlay for phishing or input interception.
- Keep an overlay visible for Shorts if the result is a black or stale frame.

Required constraints:

- Use only the Android overlay permission path that is already visible to the user.
- Overlay window must be non-focusable.
- Overlay controls must be explicit and visible when active.
- Close control must always be available when controls are visible.
- Feature should be optional behind a setting.

## Suggested settings

Initial minimum setting:

- `morphe_floating_overlay_enabled`

Possible later settings:

- `morphe_floating_overlay_default_size`
- `morphe_floating_overlay_auto_hide_controls`
- `morphe_floating_overlay_allow_shorts`

The initial PR should keep the option surface small and conservative.

## Suggested issue text

Title:

`feat(YouTube): add optional floating overlay for background video playback`

Feature description:

Add an optional floating overlay for regular YouTube videos when background
playback is active and the user leaves the app. The overlay would show the active
video in a small movable window, with controls to close it, return to YouTube,
resize it, and toggle play/pause through the active media session. Shorts should be
excluded by default to avoid black or stale overlays.

This is meant to address devices where YouTube background playback continues but
the expected floating player/PiP window does not appear.

Motivation:

On affected phones, leaving YouTube can leave the media session active while no
floating video player appears. The result is audio-only behavior for users who
expected a PiP-like visible player. This feature gives users explicit control over
a small video window without touching account data or network behavior. It also
avoids Shorts by default because they use a different vertical player surface and
caused black-frame overlays during local testing.

Acknowledgements:

- This is a feature request for YouTube.
- Existing feature requests were checked. Related issues: `#994`, `#444`, `#1196`.

## Suggested PR description

### Description

Adds an optional YouTube floating overlay for regular background video playback.
The overlay is shown when a regular video is playing and the user leaves YouTube.
It provides visible controls for close, return to YouTube, resize, and media
play/pause. Shorts are excluded by default.

This is intended to fix the case where background playback remains active on some
devices but the expected floating player/PiP window does not appear.

Related to #994, #444, and #1196.

### Additional context

The implementation must avoid committing local APKs, keystores, screenshots,
private logs, or decompiled app API keys. The local proof of concept validated the
behavior on YouTube `20.47.62`, but the patch should be tested through Morphe's
normal patch pipeline.

### Test results

- [ ] Tested on both the minimum and maximum supported versions
- [ ] Tested on experimental supported versions (Optional)
- [ ] Regular video opened from YouTube UI shows overlay after HOME
- [ ] Regular video opened from external URL shows overlay after HOME
- [ ] Real Short opened from the YouTube home Shorts shelf does not show overlay
- [ ] Close control stops overlay
- [ ] Return control restores YouTube
- [ ] Play/pause control toggles active media session
- [ ] Drag and resize persist position and size

## Current status

This is a sanitized contribution draft. It is not yet a complete upstream patch.
The next step is to port the proof of concept from local smali edits into Morphe's
Kotlin bytecode patch plus YouTube extension classes, then run the repository build
and tests before opening a PR.
