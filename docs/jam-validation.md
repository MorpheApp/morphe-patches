# Jam compatibility and player-controls validation

## Scope

PR #3014 now includes the compatibility and player-control changes from
`AgentKosticka/Jam-Patches` commits `81820e6` and `d75c61a`.
The source and test files were compared with that tested implementation before
publication; only the new Jam string was merged into the shared string resources.
Custom-source release metadata and vendored dependency changes were not imported.

The patch declares `versionCheckPatch` and accepts exactly 9.15.51, 9.35.54,
9.36.50 and 9.37.54. Both Jam bytecode and resource execution warn and return
before making Jam changes on any other version. Shared dependency patches retain
their normal behavior. The three newer targets remain experimental.

The compact player's previous/next and both layouts' play/pause controls route
participant commands to the host. Both icons use the native renderer with host
clock state. Explicit pause/resume preserves the current item and validates its
identity. Repatch both host and participant; the Companion needs no update.

## Evidence

| Version (ARM64) | Patch, SDK DEX verification and APK build | Device tests |
| --- | --- | --- |
| 9.15.51 baseline | Passed in Jam-Patches | User reported passed, 2026-09-26 |
| 9.35.54 experimental | Passed in Jam-Patches | Pending |
| 9.36.50 experimental | Passed in Jam-Patches | Pending |
| 9.37.54 experimental | Passed in Jam-Patches | User reported passed, 2026-09-26 |

The device-test report covers the preceding player-control fixes. No new device
execution was performed during the PR port, and no broader device matrix is inferred.

The validated patch selection includes GmsCore support, Hide ads, Jam, Lyrics,
background playback and Miniplayer previous and next buttons. Seven regression
tests passed on 9.15.51 and 9.37.54; the final exact-version guard brought the suite
to eight passing tests on 9.37.54. The guarded 9.37.54 APK and Android patch bundle
also built successfully.

The standalone PR checkout's build attempt remains blocked by the previously
observed shared YouTube/dependency API mismatch: 12 Java errors involving
`CharSequence`/`String` and missing `Utils.indexOf`/`Utils.contains`. It did not
produce a fresh PR-checkout build. The successful compile evidence above comes
from the matching Jam-Patches source and its pinned dependencies. That environment
also contains fingerprint-cache lifecycle fixes in its vendored patcher, which
belong to the patcher dependency and are not part of this patches PR.

Local traces are retained in the workspace's `analysis` directory:

- `jam-controls-<version>-miniplayer.log`: four successful APK validations.
- `jam-controls-version-guard.log`: final guarded tests and APK validation.
- `jam-controls-final-catalog.log`: final catalog and Android bundle build.
- `pr3014-controls-build.log`: standalone PR-checkout build limitation.

Bulk/playlist/offline enqueue, exhaustive Doze/network stress, separate radio-loss
failover and physical camera validation remain outside the reported acceptance.
