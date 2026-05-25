# iFunny Patches — Development Guide

## Patches

| Patch | File | DEX class | Target method | Strategy |
|-------|------|-----------|---------------|----------|
| Hide ads | `ad/` | `Lmobi/ifunny/ads/criterions/AdsDisableManagerImpl;` | `g()` — public, no-arg boolean | `returnEarly(true)` |
| Unlock premium | `premium/` | `Lmobi/ifunny/rest/content/User;` | `isUserPremium()` — named, stable | `returnEarly(true)` |
| Save videos | `video/` | `Lmobi/ifunny/social/share/video/model/SaveContentCriterion;` | `a()` — contains string `"getUserInfo(...)"` | `returnEarly(true)` |

All three class names are **stable** — verified via the `@Metadata` annotation in the decompiled source.
`isUserPremium` is unobfuscated (Kotlin metadata preserves it). The other two are obfuscated
single-letter names, so fingerprints use structural filters rather than method names.

---

## Prerequisites

- **Java 11+** on PATH
- **Android SDK** — set `%ANDROID_HOME%` or `%LOCALAPPDATA%\Android\Sdk`
- **ADB** on PATH
- **morphe-cli JAR** — download from [morphe-cli releases](https://github.com/MorpheApp/morphe-cli/releases)
- **GitHub PAT** with `read:packages` scope to pull the Morphe Gradle plugin:

  `~/.gradle/gradle.properties`:
  ```properties
  gpr.user=<github-username>
  gpr.key=<github-pat>
  ```

- **`local.properties`** at the repo root pointing to your Android SDK:
  ```properties
  sdk.dir=C\:\\Users\\YourName\\AppData\\Local\\Android\\Sdk
  ```

---

## Build the patches bundle

```bat
gradlew :patches:build
```

Output: `patches/build/libs/patches-<version>.mpp`

---

## Signing note — IMPORTANT

morphe-cli's built-in signing uses BouncyCastle, which rejects keystores created by JDK 9+.
Always patch with `--unsigned` and sign separately with `apksigner` from Android build-tools.

---

## Install flow for split APKs (Play Store installs)

iFunny ships as split APKs. `adb install` alone fails with `INSTALL_FAILED_MISSING_SPLIT`.
All splits must be signed with the same certificate.

```
scripts\create-keystore.bat          # one-time setup
scripts\pull-apks-from-device.bat    # pull base + splits from connected device
scripts\patch-and-install.bat        # patch, sign all, adb install-multiple
```

Edit the variables at the top of `patch-and-install.bat` before running.

---

## Fingerprint design notes

### `IsAdsDisabledFingerprint`
`AdsDisableManagerImpl` has several public no-arg boolean methods. The target `g()` is the only
one that **writes** (IPUT_OBJECT) a `Ljava/lang/Boolean;` field, so the fingerprint uses:
- `accessFlags = PUBLIC` (class is final but method is not marked final in DEX)
- `methodCall(INVOKE_STATIC, Boolean.valueOf)`
- `fieldAccess(IPUT_OBJECT, type = "Ljava/lang/Boolean;")`

### `IsUserPremiumFingerprint`
Fully stable — method name `isUserPremium` is preserved by Kotlin metadata and unobfuscated
in the DEX. No structural filters needed.

### `CanSaveVideoFingerprint`
`SaveContentCriterion.a()` is identified by the hard-coded string `"getUserInfo(...)"` passed to
`Intrinsics.checkNotNullExpressionValue`. This string is unique to this method in the class.

---

## Re-decompiling for a new version

```bat
jadx --deobf --deobf-min 3 -d ifunny_decompiled base.apk
```

Check the `@Metadata` `d2` array in each target class to verify the DEX class descriptors
have not changed between versions. Update `Constants.kt` with the new version and SHA-256
certificate fingerprint (`apksigner verify --print-certs base.apk`).
