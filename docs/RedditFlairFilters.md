# Reddit flair filters

The optional **Flair filters** patch adds per-community rules and an observed-flair
picker under **Morphe > Filters** for Reddit **2026.45.0**. It has no dependency on
a keyword-filter patch. A rule such as `gaming::Discussion` blocks only that flair
in that community. Community names accept an `r/` prefix and ignore case; flair
matching ignores case, normalizes whitespace and decodes HTML entities.

Add, edit or remove rules manually, or select observed flairs in the picker.
Refresh the feed after saving. **Collect flair choices while browsing** preserves
the existing observed-flair behavior and can be turned off independently. With
collection off and no rules, the flair filter does no model or background work.
Collection alone never enables extra native post fetches. Existing rules remain
saved if the patch is removed or not selected.

Flair matching uses a separate data path from keyword text. Identical labels in
other communities remain visible. The patch does not alter native flair-chip
interactions or add a community flair row. Home-feed flair display remains its
own upstream patch and toggle. Missing native flair metadata fails open and is
used on the next mapping/refresh after the native fetch completes.

See [RedditContentFilters.md](RedditContentFilters.md) for native hooks and fixture
commands. Run `flairs`, `flairs-combined`, host tests and invalid-input checks. Both
advertised target bounds are 2026.45.0. Local verification covers the standalone
Android bundle, Desktop CLI patching with `--validate`, native-field and
registration audits, APK rebuilding and DEX reload, host runtime regressions,
and rejection of missing, ambiguous and already-patched inputs. Combined-filter
SDK checks introduce no hierarchy issues beyond the original APK's baseline.
Device testing of this modular registration and settings implementation remains
outstanding.
