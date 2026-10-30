# YouTube package structure

This package should follow a feature-first architecture instead of a type-first layout.

## Target layout

```text
patches/src/main/kotlin/app/morphe/patches/youtube/
├─ core/
│  ├─ compatibility/
│  ├─ bootstrap/
│  └─ config/
├─ platform/
│  ├─ litho/
│  ├─ protobuf/
│  ├─ hooks/
│  └─ resources/
├─ shared/
│  ├─ utils/
│  ├─ settings/
│  ├─ models/
│  └─ analytics/
├─ features/
│  ├─ ads/
│  ├─ captions/
│  ├─ comments/
│  ├─ feed/
│  ├─ player/
│  ├─ search/
│  ├─ subscriptions/
│  ├─ interaction/
│  └─ layout/
└─ YoutubePatchBundle.kt
```

## Rule of thumb

- Put all user-visible feature logic under `features/`
- Put platform-level infrastructure under `platform/`
- Put shared helpers under `shared/`
- Put compatibility and bootstrap code under `core/`
- Avoid `misc` for feature-specific modules

## Recommended naming

- `*Patch.kt` for main patch classes
- `*Fingerprint.kt` for fingerprints
- `*Hook.kt` for hooking helpers
- `*Settings.kt` for settings/state definitions
- `*Utils.kt` for shared utility code
- `*Registry.kt` for patch registration or wiring

## Why this is better

- Better organization by feature rather than by arbitrary folders
- Easier to scale as YouTube changes
- Reduces cross-feature coupling
- Makes the repo easier to navigate by new contributors
- Keeps platform concerns separate from app features

## Migration strategy

1. Create the new structure
2. Move feature modules gradually
3. Update imports after each move
4. Remove `misc` and legacy vanity folders only after the build stays green

This is the preferred long-term architecture for the repo.
