# YouTube package structure

This package follows a feature-first architecture with a staged migration strategy.

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
├─ YoutubePatchBundle.kt
├─ YouTubePackageDocs.kt
└─ MIGRATION_STATUS.md
```

## Current migration status

The project is currently in the safe bridge-first migration state:

- new feature entry points exist under `features/`
- legacy modules remain available during migration
- imports are gradually redirected to the feature-first package names
- legacy package folders are removed only after the build is green

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
2. Create bridge entry points for legacy modules
3. Update imports to the feature-first package names
4. Validate the build after import updates
5. Remove legacy `misc` and vanity folders only after the build is green

This is the preferred long-term architecture for the repo.
