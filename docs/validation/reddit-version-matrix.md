# Reddit version validation matrix

The three APKMs were supplied locally by the user. No phone control, installation or USB debugging was used.

| Version | Base APK SHA-256 | Host result |
| --- | --- | --- |
| 2026.10.0 | `361c687a3690e5d85e919a3a9207e92bf399646dd7349ff7c0599ef6eb7ada01` | Forced `-f --validate` fails: the existing settings dependency matches two methods. Both native adult-setting row anchors are absent. Unsupported. |
| 2026.40.0 | `6f7ef02022f867c4f9381057be70e2b4b1062b8744ebab4b74e1768b458533d1` | Forced execution of the preceding patch passes. The CI-built revision with native community/search hooks also passes `--validate` and rebuilds DEX/resources. |
| 2026.45.0 | `4aa18754615c3058a55d32e920e98a698e25dedb7c5d299cf911d77e52e21821` | The CI-built revision with native community/search hooks passes `--validate` and rebuilds DEX/resources. |

The native-hook candidate is the full Gradle artifact from test-source run `43734612498`, source revision `9f9a3a43c`. GitHub's raw artifact download expands the MPP archive; its files were repacked for local CLI validation. The final release archive is verified separately before recommending phone testing.

No gated no-op is counted as compatibility evidence. No claim is made that 2026.40.0 is the earliest possible supported version; intervening versions are untested. Only 2026.40.0 and 2026.45.0 are advertised.

Eighteen synthetic host checks pass. Hosted full Gradle tests and Android packaging pass. These results establish build/patching compatibility, not functional device coverage.

Phone testing of releases through `v2.3.0-nsfw.5` found actual content exposure in anonymous browsing, direct community routes and previously enabled content. Native view gating, anonymous search control removal and saved bypass clearing are revisions responding to those failures. They require the same phone regressions to pass before the feature can be considered verified. Full profile/post navigation, alternate transports, persisted models and media-request prevention remain open.

## Final published archive

The downloaded `v2.3.0-nsfw.6` MPP archive also passed `--validate`, patch application and DEX/resource rebuilding on both 2026.40.0 and 2026.45.0. All 25 network API method descriptors used by its runtime match both original APKs exactly. No strict SDK verifier or device pass is claimed.

Archive SHA-256: `aa9cecc8e19ec647507999e91dbd276cf30603b4e313b99f74eb4c40a2ac8c43`.

Its manifest identifies it as `Reddit SFW Patches (PR testing)`. Both Manager channel endpoints resolve to this test release. Functional phone regression testing remains required; no prior device failure is considered closed solely by these host results.
