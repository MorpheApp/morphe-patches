# Reddit 2026.45.0 host validation

Input: user-supplied arm64 APKM, version code 2645001.
Base APK SHA-256: `4aa18754615c3058a55d32e920e98a698e25dedb7c5d299cf911d77e52e21821`.

- **Passed:** 15 host fixture tests.
- **Passed:** five required original-APK anchors each match exactly once.
- **Passed:** all 25 network API descriptors referenced by the new runtime exist exactly in the supplied host APK.
- **Passed:** Morphe Desktop 2.1.1 with Patcher 2.3.0, `--validate --exclusive`, selecting only `Permanently disable NSFW (experimental)` and its dependencies.
- **Passed:** modified DEX compilation and resource rebuild; unsigned APK generated successfully.
- **Not passed:** the earlier strict SDK verifier attempt. It reported 33 cross-DEX issues, including three calls into obfuscated Okio introduced by the first interceptor implementation and missing stock AppsFlyer/re2j classes. The direct Okio calls were removed and the new runtime's exact network descriptors then passed the APK ABI check. Full strict SDK verification was not repeated, and is not claimed as passing.
- **Not performed:** device execution, traffic captures, offline/persisted model testing, or complete search/direct-navigation coverage. No USB debugging or device installation was used.

The bundle used for the successful patching run was compiled from the feature source against the pinned public Morphe 2.2.0 release. The upstream Gradle build was attempted earlier but did not resolve its plugin anonymously; the focused local build avoids those registry credentials.

This evidence establishes host patching compatibility for the supplied build. It does not establish complete content-blocking coverage.
