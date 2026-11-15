# Reddit keyword filters

The optional **Keyword filters** patch adds a rule editor under **Morphe > Filters**
for Reddit **2026.45.0**. It has no dependency on a flair-filter patch. One rule is
one case-insensitive Unicode regular expression. Titles, translated titles,
descriptions, native body/selftext and available previews are searched; flair
labels and rich-text JSON metadata are excluded. Empty rules do no model work.

Add, edit or remove rules and refresh the feed. Invalid new expressions stay in
the editor with an error. Invalid legacy expressions are logged and skipped
without disabling valid rules. Existing saved keyword rules are preserved.

The shared infrastructure and external-fixture verification commands are described
in [RedditContentFilters.md](RedditContentFilters.md). Run `keywords` and
`keywords-combined` against the original base APK, plus host tests and invalid-input
checks. The latter combined selection verifies composition with upstream ad hiding
and home-flair display. The minimum and maximum advertised target are both
2026.45.0; older-version support is not extended.

Local verification covers the standalone Android bundle, Desktop CLI patching
with `--validate`, native-field and registration audits, APK rebuilding and DEX
reload, host runtime regressions, and rejection of missing, ambiguous and
already-patched inputs. Combined-filter SDK checks introduce no hierarchy issues
beyond the original APK's baseline. Device testing of this modular registration
and settings implementation remains outstanding.
