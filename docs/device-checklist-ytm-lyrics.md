# Device Checklist — YTM Third-party Lyrics (ee1cbbad × upstream/dev merge)

This checklist tracks the lyrics-panel features that were merged from the local branch
`ee1cbbad` (baseline) together with upstream `dev` (`035718bf9`), and the fixes applied to
resolve merge regressions. Use it to verify behaviour on a real device before releasing.

Branch: `feat/ytm-lyrics`
Base merge: `upstream/dev` @ `035718bf9` + local `ee1cbbad`

---

## 1. Language picker from the footer

**Feature:** Tapping "Translated by X" / "Romanized by Y" in the footer opens the target
language dialog for that side. "Source: X" opens the source link.

**How it works:**
- `LyricsViewSupport.sourceText(...)` builds a `SpannableStringBuilder` with
  `LanguageSpan` (one per side) and a `ClickableSpan` for the source line.
- `LyricsPanelView.updateFooter()` passes `() -> onTargetLanguageChanged(true/false)`.
- `footerView` is wired with `LinkMovementMethod` so the spans are tappable.

**Verify on device:**
- [ ] Footer shows "Source: <provider>" and "Translated by <provider>" when translation is on.
- [ ] Tapping "Translated by X" opens the translation language picker; picking a language re-runs the translation.
- [ ] Tapping "Romanized by Y" opens the romanization language picker; picking a language re-runs the romanization.
- [ ] Tapping "Source: X" opens the provider/source link.
- [ ] Language pickers work whether the line is in normal mode or only-mode (translation/romanization alone).

**Regression fixed in this merge:**
- `onTargetLanguageChanged`, `requestNormalTranslate`, `requestNormalRomanize`,
  `setRimeProviderEnabled` were lost in the merge (call sites existed, definitions missing)
  → restored from `ee1cbbad`.
- `footerView` was missing `LinkMovementMethod` → span taps did nothing → added.

---

## 2. Show background vocals separately (折叠背景/和声行)

**Setting:** `LYRICS_SPLIT_BACKGROUND` (`morphe_music_lyrics_split_background`, default ON)

**Feature:** When ON, background/harmony vocals fold open around their lead line.
When OFF, no folding is applied at all — every line is shown inline.

**Global guard:** the switch is checked in two places so it controls all folding:
- `LyricsPanelView.shouldBgLineBeVisible()` — returns `true` for all bg lines when OFF.
- `LyricsLineView.animateSecondaryExpansion()` — returns early when OFF (no open/close animation).

**Verify on device:**
- [ ] With the switch ON: background/duet/harmony lines fold open only around their lead line.
- [ ] With the switch OFF: background lines are shown inline, never folded/collapsed, even for synced lyrics.
- [ ] Toggling the switch live updates the panel without a crash.

**Regression fixed in this merge:** the guard was missing → the switch did not globally
disable folding.

---

## 3. Always center lyrics (整体水平居中)

**Setting:** `LYRICS_FORCE_CENTER` (`morphe_music_lyrics_force_center`, default OFF)

**Feature:** When ON, every line is horizontally centered as a whole (not just text-internal
alignment). Implemented in `LyricsSpanBuilder.resolveAlignment()` returning
`Layout.Alignment.ALIGN_CENTER`.

**Verify on device:**
- [ ] With the switch ON: all lyric lines, translations, romanizations, and bracketed
      tags (e.g. `[v1]`, `[chorus]`, the `v1000`/`v2000`/合唱 markers) are centered.
- [ ] RTL songs still center correctly (no right-padding bias).
- [ ] With the switch OFF: alignment follows the natural LTR/RTL direction and duet sides.

---

## 4. Shrink inactive lines

**Setting:** `LYRICS_LINE_SCALE` (`morphe_music_lyrics_line_scale`, default ON)

**Feature:** Inactive lines shrink slightly on top of dimming.
`LyricsPanelView.applyLineScale()` uses `LyricsViewSupport.ACTIVE_LINE_SCALE` (1.0f) /
`INACTIVE_LINE_SCALE` (0.95f).

**Verify on device:**
- [ ] Inactive lines are slightly smaller than the active line.
- [ ] Toggling the switch live rescales without rebuild.

---

## 5. Duet (对唱) indent

**Feature:** The second singer in a duet is pushed to the opposite side; the indent is
`max(dp(24), screenWidth × 0.09)` (9%), down from the 18% originally proposed in #3877
(see discussion_r4346196856 — "too large on small screens, try 9%").

**Verify on device:**
- [ ] On a narrow phone, the duet indent is modest (≈9%), not the aggressive 18%.
- [ ] Duet sides flip correctly for RTL songs (line-level, not whole-song).

---

## 6. Debug failure toast (仅调试开启时显示)

**Feature:** Auto-failure toasts (translate/romanize/transcribe/search/SB-video) are shown
only when `BaseSettings.DEBUG_TOAST_ON_ERROR` is ON. `LyricsPanelView.toastError()` is the
single entry point; unconditional `Utils.showToastShort` failure toasts were replaced with it.

**Verify on device:**
- [ ] With `DEBUG_TOAST_ON_ERROR` OFF: a failed translation/romanization shows nothing (or only the
      normal UI state), not a toast.
- [ ] With it ON: the failure toast appears.
- [ ] The `BaseSettings` import is present (no `package does not exist` build error).

**Regression fixed in this merge:** the `BaseSettings` import was dropped → build break;
failure toasts were unconditional → now gated by the debug switch.

---

## 7. RTL / word-sync alignment (from #3877, preserved)

**Feature:** RTL reversal and phonetic alignment are evaluated per line, not per whole song,
so a single stray RTL character does not flip an entire line (see discussion_r4346163866).
`LyricsPanelView` computes `lineRightAligned[]` line-by-line; `LyricsSpanBuilder.resolveAlignment`
and `isRtlText` support it.

**Verify on device:**
- [ ] A mostly-LTR song with one Hebrew/Arabic word does not flip the whole line.
- [ ] A genuinely RTL song aligns right; its duet partner aligns left.

---

## 8. Romanization fallback order (from #3877, preserved)

**Order:** embedded reading → Local/Rime → AI (OpenAI-compatible) → Google.
`LyricsRomanizer.romanizeWithProvider` walks the provider list; `getEmbeddedRomanization`
and `alignIfWordSynced` keep word-sync alignment intact.

**Verify on device:**
- [ ] Local dict (Rime/Pinyin/Kana) romanization works.
- [ ] AI romanization via an OpenAI-compatible endpoint works.
- [ ] Google fallback works when AI/Local are off.
- [ ] Empty/blank AI returns fall back instead of caching empty text.

---

## Migration / cleanup notes

- `v1.48.0` migration code (`migrateFrom148`, `migrateLyricsProvidersFrom148`,
  `migrateLyricsRenamedProviders`) was removed as an upstream breaking change decision.
  The upstream-only `migrateLyricsArtistFirstToTrackFormat` is kept.
- `GeminiClient` was folded into the OpenAI-compatible provider (OpenRouter/OpenAI/Gemini
  all speak the OpenAI format); no separate Gemini client remains.
- `sanitizeDegradedRomaji` / `isProviderRomajiDegraded` were removed by the upstream
  `LyricsRomanizer` refactor (replaced by the `ProviderResult` structure).

---

## Known defaults to confirm before release

- `LYRICS_TEXT_SIZE` default is 28 sp (reviewer suggested 26 in #3877 discussion_r4346212825 —
  consider lowering if content density is a concern).
- `DEFAULT_LYRICS_ORDER` keeps community providers disabled except `LRCLIB` (per
  discussion_r4346206626).
- Apple lyric effects (spring/glow/floating/dynamic background) are NOT added as defaults
  (per discussion_r4346212825) — low-end devices stay smooth.
