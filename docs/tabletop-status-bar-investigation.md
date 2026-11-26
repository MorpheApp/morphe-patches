# Tabletop status bar investigation

Target APK: YouTube 21.30.216 (1561187872). The APK itself is not included.

## Static bytecode observations

- classes4.dex: `Lgsl;` initializes FLAT and HALF_OPENED state objects.
- classes2.dex: `Lafmv;->a(Ljava/lang/Object;)Ljava/lang/Object;` includes a mapping of a single half-open folding feature: zero-height bounds produce a horizontal clamshell state using Rect.top; zero-width bounds produce a book state using Rect.left. Other inputs fall back to other posture states.
- classes5.dex: `Lafrn;`, `Lafrm;`, and `Lafro;` implement `Lafrk;`. Their toString methods contain `ClamshellState{foldPositionY=`, `BookState{foldPositionX=`, and `FlatState{}` respectively.
- classes.dex: `Losl;->q(Lafrk;)V` and `Lpah;->q(Lafrk;)V` both save the result of an instanceof ClamshellState check to a boolean field, compare it to the prior value, and call an update method on change.
- classes.dex: `Lrgh;->a` also checks ClamshellState in a path that updates `Lrhb;`. That class contains the string `tabletop-controls`, but its observed callers appear to build event payloads, so it is not the preferred UI hook.

## Probe

The first diagnostic build of the Hide status bar patch included an injection which resolves the clamshell class by its string and matches the two callback shapes. The injection logged the boolean result and the existing PlayerType without changing status bar visibility or adding a setting.

Test on the N6 with Morphe debug logging enabled, the global hide status bar setting disabled, and a regular video active. Enter tabletop, leave tabletop, and repeat using Fold Switcher. Also check the home feed, miniplayer and normal fullscreen. Look for `Tabletop probe:` logs and record player states. Confirm callbacks track the UI rather than just the device posture.

## Implementation design

Keep the existing global setting and its saved value compatible. Add a tabletop-only mode after verifying the probe. Hide only the main player's status bar, restore it on leaving the tabletop player, and preserve stock fullscreen and navigation-bar handling. The existing status bar layout fix caches edge-to-edge flags for the session; do not switch those feature flags dynamically. Validate inset and display cutout handling separately.

The diagnostic build passed CI and was tested on the target APK. The functional implementation removes its temporary fingerprints.

## Device validation (26 November 2026)

Five open/close cycles on OPPO Find N6 matched `ClamshellState` with `EngagementPanel open/closed: tabletop-controls`. The panel events preceded the posture callbacks in two cycles. The functional prototype therefore removes the diagnostic fingerprints and listens to the existing engagement panel and player type hooks. It retains the original global switch and adds a default-off, restart-required tabletop-only switch, with global hide taking precedence. With the layout fix enabled, the edge-to-edge flag is disabled for the entire session in either hide mode, as upstream already does for global hide; this can change the normal page layout and must be tested. No change to stock fullscreen or navigation bar is intentionally requested by the new controller. The functional build passed CI at commit `15a784a` (run `45743764820`). No device logs or APK are committed.

### Functional device results

Device: OPPO Find N6, Android 17. Tested build: `15a784a`. The user reported the following results on 26 November 2026, with global hiding disabled and tabletop-only hiding enabled.

| Test | Observed result |
| --- | --- |
| Home feed | Status bar visible |
| Normal video player | Status bar visible |
| Physical half-folded tabletop player | Status bar hidden |
| Tabletop forced with Fold Switcher | Status bar hidden |
| Leaving tabletop | Status bar restored |
| Normal fullscreen | Unchanged |
| Video layout in tabletop | Video occupies the former status bar space |
| Minimize video while still half-folded | Status bar restored |

YouTube returns to the normal player after unfolding from tabletop, even with tabletop-only hiding disabled. The user confirmed this is stock behaviour. Fullscreen restoration is outside the scope of this change.

The later description-only commits explain the half-folded video and controls layout. They do not change runtime behaviour.

## Pre-rebase review

Keep the existing engagement panel dependency and the maximized-player guard. They distinguish the actual tabletop UI from physical device posture and prevent hiding the status bar in the miniplayer. Preserve the existing global hide setting and its priority. Do not toggle the cached edge-to-edge flag during playback.

The current implementation has not been tested on other devices, with the layout fix disabled, with global hiding enabled, through all activity recreation paths, or in split-screen/PiP. The session-wide layout flag also affects the rest of YouTube when tabletop-only hiding is enabled; verify normal browsing and navigation-bar layout in the final regression test.

Upstream dev at `59bdab6551474d69b82171057669d9bbf77831fe` adds a display-cutout avoidance option to the existing global status bar patch. Reconcile that option and the inset logic during rebase rather than replacing the upstream implementation. Retest the final rebased build.

## Rebase onto v2.4.0-dev.15

The cutout-avoidance preference is available when either status-bar hide mode is enabled. The global hide mode retains priority. Both inset hooks respect cutout avoidance while the tabletop controller owns the status bar. The default remains off, preserving the previously tested layout. Re-run the eight device tests with avoidance off, then check the protected tabletop layout and unchanged global behavior with avoidance on. These combined behaviors are not yet device-tested.


### Post-rebase build and device validation (26 November 2026)

Tested code: `582b7833fad3018762495e9db07d8fc1a732d09d`, built on `feature/tabletop-status-bar` ([CI run 45780935471](https://github.com/ioneng/morphe-patches/actions/runs/45780935471)). Automated tests, Android patch bundle compilation and artifact upload passed. The initial build on the separate rebase branch failed resolving Gradle plugin 2.1.2 before compilation; rebuilding the same code on the original feature branch succeeded. Cache differences are consistent with branch-scoped dependency caches, but the exact cause of the registry resolution failure was not established.

Device: OPPO Find N6, Android 17. User-reported results with tabletop-only hiding enabled and global hiding disabled:

| Scenario | Observed result |
| --- | --- |
| Physical tabletop, Keep player below display cutout on | Status bar hidden; empty space above video retained |
| Unfold with cutout avoidance on | Normal player; status bar restored |
| Physical tabletop, cutout avoidance off | Status bar hidden; video occupies full height of upper half |
| Unfold with cutout avoidance off | Normal player; status bar restored |
| Fold Switcher half-open while physically flat, cutout avoidance off | Tabletop layout displayed; status bar hidden |
| Fold Switcher open while physically flat | Normal player; status bar restored |

Returning to normal rather than fullscreen is the previously established stock behavior, not part of this change. The earlier eight functional results apply to the pre-rebase build; the full matrix was not repeated on the rebased build. Global hiding, layout fix disabled, other devices/Android versions, multi-window/PiP, activity recreation and normal browsing/navigation-bar regressions remain unverified on the final build.

### Final static review

Compared with the tested upstream merge base `4bba711f`, the change is limited to the status-bar implementation, the existing engagement-panel hook, preference registration/strings and this document. Upstream dev at review time (`d38d9d84`) is seven commits ahead of that merge base and does not modify any of these files.

The Java status-bar implementation has eight `catch (Exception)` blocks versus seven in upstream. The only new catch is at the tabletop controller update boundary, following the existing pattern of logging unexpected UI callback failures. No explicit throws or catch-driven normal control flow were found in the added logic. The minimum SDK is 26, and the newer insets-controller APIs are guarded by SDK checks. Retain the existing upstream exception handlers; do not remove them merely to reduce their count.

Potential recovery edge case from static review: the controller clears its applied flag before restoring system bars and background views. If the insets controller is temporarily null, or a restoration operation throws, restoration may not be retried on the next update. This has not been reproduced on the device; review recovery behavior before declaring all lifecycle paths safe. The supplied diagnostic logs are from the earlier probe, not the final build, so they cannot establish that the rebased controller is exception-free.

No runtime code was changed by this documentation update.

## Restoration hardening and final settings validation (26 November 2026)

Commit `25c3e1c9250a03dd80cb23ea444d0fbdd1cc0cb3` retains the pending restoration state if the window insets controller is unavailable or restoration throws, allowing another attempt on an existing layout, focus or player-state update. It introduces no new exception handlers, timers or polling. [CI run 45783214367](https://github.com/ioneng/morphe-patches/actions/runs/45783214367) passed automated tests, Android bundle compilation and artifact upload.

The user reported testing all combinations of Hide status bar, Hide status bar in tabletop mode and Keep player below display cutout on the OPPO Find N6, using both physical folding and simulated folding through Fold Switcher. All combinations behaved as expected. This supersedes the earlier settings-combination coverage gap; the cutout preference is unavailable in the UI when both hide modes are off. These are user-reported functional results, not an injected failure test. Other devices, multi-window/PiP, activity recreation, layout-fix-disabled behavior and the exceptional restoration path remain unverified. Fullscreen restoration after unfolding remains outside the patch scope.
