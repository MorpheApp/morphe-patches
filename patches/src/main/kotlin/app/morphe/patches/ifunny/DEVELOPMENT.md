# iFunny Patches — Development Guide

## Tested against

**iFunny 10.39.11** (`mobi.ifunny`, minSdk 26). Fingerprints were authored and verified against
this version. Use `--force` with morphe-cli to test on adjacent versions; re-verify fingerprints
before bumping `Constants.kt`.

---

## Patches

| Patch | Package | DEX class | Target method | Effect |
|-------|---------|-----------|---------------|--------|
| Hide ads | `ad/` | `Lmobi/ifunny/ads/criterions/AdsDisableManagerImpl;` | `g()` | Always return `true` |
| Unlock premium | `premium/` | `Lmobi/ifunny/rest/content/User;` | `isUserPremium()` | Always return `true` |
| Save videos | `video/` | `Lmobi/ifunny/social/share/video/model/SaveContentCriterion;` | `a()` | Always return `true` |

---

## Prerequisites

| Tool | Notes |
|------|-------|
| Java 11+ | On PATH |
| Android SDK | `ANDROID_HOME` set, `build-tools` installed |
| ADB | On PATH |
| morphe-cli JAR | [Latest release](https://github.com/MorpheApp/morphe-cli/releases) |
| APKEditor JAR | [Latest release](https://github.com/REAndroid/APKEditor/releases) — needed for single-APK distribution only |
| jadx | For re-decompiling on version bumps |

**`~/.gradle/gradle.properties`** — GitHub PAT with `read:packages` to resolve the Morphe plugin:
```properties
gpr.user=<github-username>
gpr.key=<github-pat>
```

**`local.properties`** at repo root:
```properties
sdk.dir=/path/to/Android/Sdk
```

---

## Build

```
gradlew :patches:build
```
Output: `patches/build/libs/patches-<version>.mpp`

---

## Signing keystore

morphe-cli's built-in signing uses BouncyCastle, which is **incompatible with keystores generated
by JDK 9+**. Always patch with `--unsigned` and sign with `apksigner` instead.

Create a PKCS12 keystore once:
```
keytool -genkeypair -keystore morphe.p12 -storetype PKCS12 \
  -alias morphe -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass morphe123 -keypass morphe123 \
  -dname "CN=Morphe, O=Morphe, C=US"
```

The certificate contains no PII — only the anonymous DN above and a creation timestamp.

---

## APK prep — pulling from a device (recommended)

iFunny from the Play Store installs as split APKs. Pulling from a connected device guarantees
the correct ABI.

```
adb shell pm path mobi.ifunny          # lists all split paths on device
adb pull <path>/base.apk
adb pull <path>/split_config.arm64_v8a.apk
adb pull <path>/split_config.xxxhdpi.apk
```

## APK prep — extracting from XAPK

An XAPK is a ZIP file. Rename `.xapk` → `.zip` and extract. You'll find `mobi.ifunny.apk`
(base) plus ABI and density splits inside. **Check the ABI split matches your test device**
before continuing — a mismatch causes `INSTALL_FAILED_NO_MATCHING_ABIS`.

---

## Development testing (split install)

```
# 1. Patch unsigned — morphe-cli aligns automatically
java -jar morphe-cli.jar patch -p patches.mpp \
  --exclusive --force --unsigned \
  -e "Hide ads" -e "Unlock premium" -e "Save videos" \
  -o base-patched.apk base.apk

# 2. Sign base
apksigner sign --ks morphe.p12 --ks-pass pass:morphe123 \
  --ks-key-alias morphe --key-pass pass:morphe123 \
  --out base-signed.apk base-patched.apk

# 3. Re-sign splits with the same certificate (all splits must share one cert)
cp split_config.arm64_v8a.apk  split_arm64_signed.apk
cp split_config.xxxhdpi.apk    split_xxxhdpi_signed.apk
apksigner sign --ks morphe.p12 --ks-pass pass:morphe123 \
  --ks-key-alias morphe --key-pass pass:morphe123 split_arm64_signed.apk
apksigner sign --ks morphe.p12 --ks-pass pass:morphe123 \
  --ks-key-alias morphe --key-pass pass:morphe123 split_xxxhdpi_signed.apk

# 4. Install (uninstall original iFunny first if signature changes)
adb install-multiple base-signed.apk split_arm64_signed.apk split_xxxhdpi_signed.apk
```

---

## Distribution — single merged APK

```
# 1. Merge signed splits into one APK
java -jar APKEditor.jar m -i <dir-containing-all-signed-apks> -o universal.apk -f

# 2. Re-sign — APKEditor invalidates the existing signature
apksigner sign --ks morphe.p12 --ks-pass pass:morphe123 \
  --ks-key-alias morphe --key-pass pass:morphe123 \
  --out universal-signed.apk universal.apk

# 3. Install
adb install -r universal-signed.apk
```

Recipients must **uninstall the Play Store version of iFunny first** (signature mismatch).

---

## Fingerprint design

All DEX class names are taken from the `d2` array of the `@Metadata` Kotlin annotation embedded
in each class — **not** from jadx's decompiled package names, which differ (`mobi.content.*` vs
the real `mobi.ifunny.*`). This makes them stable across jadx versions and deobfuscation settings.

### `IsUserPremiumFingerprint`
`isUserPremium` is an unobfuscated method name preserved by the Kotlin compiler in the DEX.
Fingerprint matches by name alone — no structural filters needed. Most stable of the three.

### `IsAdsDisabledFingerprint`
`AdsDisableManagerImpl` has several public no-arg boolean methods. The target `g()` is uniquely
identified by two filters:
- `methodCall(INVOKE_STATIC, Ljava/lang/Boolean;, valueOf)` — calls `Boolean.valueOf()`
- `fieldAccess(IPUT_OBJECT, type = Ljava/lang/Boolean;)` — *writes* the cached field

Method `a()` in the same class also calls `Boolean.valueOf` but only *reads* (IGET) the field,
so the IPUT filter excludes it. `accessFlags = PUBLIC` only — the class is `final` but individual
methods do not carry `ACC_FINAL` in the DEX.

### `CanSaveVideoFingerprint`
`SaveContentCriterion.a()` is identified by the hard-coded string `"getUserInfo(...)"` passed
to `Intrinsics.checkNotNullExpressionValue`. This string literal is unique within the class and
unlikely to change as it reflects a Kotlin compiler convention.

---

## Future-proofing

- **Class names**: sourced from `@Metadata` — stable as long as the class exists and isn't
  renamed in source. Verify the `d2` array after each version bump.
- **Method names**: only `isUserPremium` relies on a name; the others use structural filters
  that survive method renaming across R8/ProGuard runs.
- **Structural filters**: `Boolean.valueOf` + `IPUT_OBJECT` and `"getUserInfo(...)"` are
  tied to Kotlin compiler output patterns, not obfuscation — highly stable.

To verify after a version bump, re-decompile and check each class:
```
jadx --deobf --deobf-min 3 -d ifunny_decompiled base.apk
```
Check `@Metadata` `d2` arrays match the class descriptors in `Fingerprints.kt`.
Update `Constants.kt` with the new version string and app cert SHA-256:
```
apksigner verify --print-certs base.apk
```
