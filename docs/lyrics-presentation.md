# Lyrics presentation

The options below live in Morphe → Lyrics. They only change displayed text; provider
results, cached lyrics and track metadata used for lookups are left alone.

## Filters

- **Force-capitalize first letter:** capitalize the first cased letter of each line.
- **Strip trailing punctuation:** remove terminal full stops, commas, colons and
  semicolons using Unicode properties. Keep paired punctuation, questions,
  exclamations and ellipses. Unknown punctuation stays as-is.

Both default to off. They run after the existing lyric formatting, including
translation and romanization, and keep the word-highlight offsets aligned.

## MiniPlayer and MediaSession

These options also affect the notification and lock-screen text:

- **Format ‘Title - Artist’ label:** `{t} - {a}` by default. The old Artist-first
  setting is migrated without replacing an explicitly saved template.
- **Encapsulate lyrics:** `{lrc} :(x{n}):` by default. `{lrc}` is required;
  `{n}` counts consecutive identical source lines, not ticker updates. Text inside
  unescaped `:...:` appears only when the count is above 1. `\:` is a literal colon.
- **Char limit per line:** 0 disables splitting. Above the chosen threshold, split
  into two halves by words, or graphemes if there are no word boundaries. The first
  half gets the extra item. This is a threshold, not a hard cap on either half.
- **Fill in instrumental gaps:** defaults to three lines: `♪`, `♪♪`, `♪♪♪`.
  Each input line is one frame, with an equal share of the gap's duration.
  `{t}`, `{a}` and `{lrc}` insert title, artist and the last displayed lyric.
  `{lrc}` alone holds the lyric. Empty input intentionally shows blank text.
- **Next-line offset (ms):** 0 by default, range -10000 to 10000. A negative value
  looks ahead: -600 publishes the next line 600 ms early. It doesn't change playback
  or the full lyrics panel.

Word timestamps drive split transitions when available; otherwise timing is
proportional to the line interval. Complete enclosing punctuation is restored on
both halves using Unicode 17 BidiBrackets and CLDR 48 quotation delimiters.
Nested shells are supported; independently balanced phrases aren't wrapped again.
Unknown or unbalanced delimiters aren't guessed. See the provenance JSON and
Unicode license alongside this document.

Gap timing reuses the panel's `SectionDotsMotion` classifier. Explicit note-only
provider lines also count as markers. Active vocals aren't replaced by gap text;
ordinary pauses keep the last lyric. Missing/unsynced lyrics aren't evidence of an
instrumental break. Nothing here analyzes the audio.

MiniPlayer and MediaSession share selection and the existing playback ticker.
Seeking recomputes the frame, pause holds its position, and track changes clear the
cached presentation. There's no additional timer. The notification refresh keeps
its existing ID, session token, channel, controls and artwork.

## Tests

```sh
./gradlew :extensions:music:testDebugUnitTest :extensions:music:lintDebug :patches:checkStringResources
./gradlew :patches:buildAndroid
```

Run Android packaging last. Device testing should cover natural playback, seeking,
pause/resume, track changes and both notification/lock-screen surfaces; unit tests
alone don't establish how a particular SystemUI renders media metadata.
