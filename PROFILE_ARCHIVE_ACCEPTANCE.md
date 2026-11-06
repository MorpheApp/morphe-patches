# Profile archive .6 — signed-in device acceptance

Status: pending. Run on the Galaxy S26 Ultra / Android 17 using Reddit 2026.40.0, signed in with your own account. No account credentials or private data need to be shared.

Install `patches/build/libs/patches-2.0.0-profile-archive.6.mpp` through your existing Morphe Manager source/signing workflow and rebuild from the clean supported APKM. Keep **Archived public profile history** selected and enabled. Importing the source alone does not update the app.

Use an ordinary visitor profile with known archived history and a hidden-history visitor profile (including the previously reported u/literatemice). Archive coverage varies; failure to recover a particular item does not by itself establish a merge defect.

- [ ] Ordinary Posts opens in New. Live cards are immediately usable while archived cards load. Recovered records blend into the same list, without duplicates or a hidden-history banner.
- [ ] Comments also combines live and archived records. Native cards retain their usual menu, voting/reply actions, and discussion navigation.
- [ ] Pinned posts remain at the top. Recoveries carry a small Archived label unless Reddit explicitly confirms Deleted or Removed. Text and spacing look consistent next to native cards.
- [ ] Scroll at least three mixed pages in each tab. Fling, then reverse direction. Verify no jumps, repeated pages, stalled loading, or blank gaps as archived images arrive.
- [ ] Collapse and expand the real profile header. Switch tabs with both taps and horizontal swipes. Each tab retains its independent position.
- [ ] Open a recovered post and comment. Check full selectable text, source/status, image/gallery previews, unavailable states, and masked NSFW/spoiler previews. Rotate the reader and press Back; the same profile item and offset should remain visible.
- [ ] **Open original discussion**, voting, and replying hand off to Reddit. Video playback uses Reddit; the reader displays a poster only.
- [ ] Change Posts to Top or another sort. Only native content remains alongside the New action. Switching back restores recovery. Explicit sort changes are not immediately overridden, and leaving the profile does not force global preferences to New.
- [ ] Pull to refresh. Switch profiles/tabs while archives load. No old username's results appear in the new profile. An archive connection failure leaves native content usable; Retry works after recovery.
- [ ] Check light and dark themes, large system text, TalkBack metadata/status labels, and touch targets. Hidden-history profiles retain their existing notice; owner/saved/flair-filtered views remain excluded.

Record the failing step, tab/sort, approximate item position, whether the card was native or archived, and a screenshot or short screen recording if anything differs. Include the Manager patch log if installation or patching fails. Signed-in full-profile parity is not claimed until these checks pass.
