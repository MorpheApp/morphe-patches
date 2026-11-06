# Automatic recovery .7 — signed-in device acceptance

Status: pending. Run on the Galaxy S26 Ultra / Android 17 using Reddit 2026.40.0, signed in with your own account. Emulator fixtures are reported separately and do not establish signed-in feed/discussion parity.

Install `patches/build/libs/patches-2.0.0-profile-archive.7.mpp` through the existing Morphe Manager source/signing workflow and rebuild from the clean supported APKM. Keep **Archived public profile history** selected and enabled. Importing the source alone does not update the installed app.

Use known archived deleted and moderator-removed posts/comments, an ordinary visitor profile, and a hidden-history profile. A missing archive must leave native content usable and receive no success tag.

- [ ] In Home and subreddit feeds, deleted/removed titles, text previews and authors update automatically without a tap or refresh. Readable live fields remain unchanged. Empty image posts do not trigger recovery by themselves.
- [ ] Open a deleted post from a direct link before visiting its author/profile. Title, full body and username recover independently. Check paragraphs, bold/italic, links, quotes, lists and code. No stale rich-text representation hides the recovery.
- [ ] Open a direct comment link. Recover a nested comment and its author; verify indentation, collapse/expand state, scores, votes, reply/menu/moderation actions and focused-comment navigation. Collapse while loading, then expand.
- [ ] Each successful native recovery has exactly one subdued **Undeleted** metadata tag. The marker is absent from the actual title/body/username and missing archives. Check partial recovery with only one available field.
- [ ] Native profile placeholders remain native cards, including in Top or another non-New sort. Archive-only entries and hidden history still load immediately, in their existing order and pages. Missing-from-list entries show **Archived**; confirmed deleted/removed entries show **Undeleted**.
- [ ] Tap a recovered native card and an archive-only card. Both open Reddit's normal discussion. Back returns to the same item/offset; existing voting, replying, sharing, menus and media navigation still work.
- [ ] Scroll at least three pages, fling and reverse direction while responses arrive. Check no jumps, duplicates, blank gaps or changes to pinned ordering. Collapse/expand the real profile header and switch tabs with taps and horizontal swipes.
- [ ] Pull to refresh, rapidly change profiles/discussions and leave while recovery is delayed. No stale text enters another item/screen. Disconnect the network or exercise a rate limit; native content and actions remain usable.
- [ ] Disable the existing setting while recovery is pending and after it succeeds. Pending callbacks do not restore content; visible native overlays/tags disappear. Re-enable and verify automatic recovery resumes.
- [ ] Check light/dark themes, large system text and TalkBack. Metadata remains readable without crowding native actions. Existing media and spoiler/NSFW state remain unchanged.

Record the app/bundle version, screen, sort, item ID, failing step and whether the item was native or archive-only. Include the Manager patch log for patch failures. Do not share account credentials. Device verification remains pending until these checks are exercised on the signed-in phone.
