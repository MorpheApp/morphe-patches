# Reddit content-filter infrastructure

The content-filter hooks support `com.reddit.frontpage` **2026.45.0 / 2645001**.
Other versions are not advertised or silently accepted. The reviewed base APK
SHA-256 is `fa1fb56cc85a52dcf76906f952587c6e7fea1013ff30d9a4bacbae1d49fbf67f`.

Each independently selectable filter registers a `ContentFilterPatch.Filter`
through a call injected into `registerFilters()`. The shared engine and settings
category have no references to concrete filter implementations. A standalone
filter can therefore compile and patch without another filter's source files.
Both filters use the same unnamed dependency when selected together, so native
hooks are installed once. Registration does not read models or fetch posts.

Fingerprints resolve 19 native models from diagnostic strings. Generated
`patch_filter...` accessors read reviewed fields directly. R8 outlines some model
diagnostics into static StringBuilder helpers; `ModelFields.kt` follows those DEX
helpers at patch time to recover field roles without obfuscated identifiers.
The extension does not reflect over Reddit classes.

The hooks filter `Listing.getChildren()` and the common modern-feed post list.
They preserve list identity when nothing matches, copy only when removing posts,
and never mutate a native immutable list. Unknown models remain visible. The
model walk is bounded and cycle-safe; full post metadata uses a synchronized,
bounded cache. New known text replaces old text, and explicit empty values clear
cached text after edits. Null partial data does not erase known metadata.

Rich-text bodies are decoded from `document` nodes to visible text. JSON keys,
URLs and media metadata are excluded. Flair data has its own field path and never
enters keyword text. Active filters can retain Reddit's native secondary fetch
when they need full post data. There are no additional public API requests or
network waits in the extension. Late metadata takes effect on the next mapping
or refresh. With every registered filter disabled, frequent hooks return before
reading models or doing background work.

Existing `redditplusplus_settings` rule values remain compatible. Default
preference values migrate only if the destination key does not exist. Filters
remain independently selectable and their saved rules are retained when absent.

## Verification

Original APKs, configuration splits, extracted DEX, SDK tools and patched outputs
are external fixtures and are not committed. Keep the original APKM splits when
building for installation. The base-only verifier outputs an unsigned APK.

```powershell
python patches/src/test/python/verify_reddit_filters.py <jdk-directory> <org-json-jar>
./gradlew.bat :patches:buildAndroid :patches:generatePatchesList :patches:checkStringResources
./gradlew.bat :patches:validateRedditFilters `
  -PredditApk=<original-base-apk> `
  -PredditFilterMode=<selection> `
  -PredditFilterBundle=<built-mpp> `
  -PredditFilterOutput=<output-directory>
```

Selections are `keywords`, `flairs`, `both`, `keywords-combined`, `flairs-combined`
and `combined`; use only selections whose feature is present in the checkout.
Combined modes add upstream home-flair display and ad hiding. Each invocation
uses a new JVM, loads the actual bundle, audits native fields, decoder calls,
feature registration and inclusion flags, rejects reflective filter calls,
rebuilds the APK and reloads every output DEX.

Combined modes additionally run SDK dexdump/D8 checks and compare the complete
cross-DEX hierarchy issue set with the original input. The reviewed original has
30 optional-library issues; the patched output must introduce none. Per-DEX
errors are never suppressed. `reject-missing`, `reject-ambiguous` and
`reject-already` generate deliberately invalid original-derived inputs and require
rejection before producing a deliverable.

Finish with `:patches:buildAndroid` after verification. Its JVM `jar` dependency
can otherwise rewrite the MPP without Android DEX. Host fixtures cover independent
registration, disabled hooks, immutable lists, rule edits, native text and flair
separation, rich text and malformed JSON, delayed metadata and bounded graph walks.
Device behavior remains a separate validation step.
