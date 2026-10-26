# Subscription channel groups

Adds a patch named **"Subscription channel groups"** to the YouTube
Morphe patches. It lets you organize your subscribed channels into
your own groups and filter the subscriptions screen by group.

> **Status: NOT verified on a real device or APK build.** This was
> written in an environment with no Android SDK, no YouTube APK and
> no Java, so it has only been checked statically (syntax balance,
> string/setting key consistency, XML well-formedness, and that the
> combined diff applies to a clean clone). The in-app attachment
> point is heuristic, like the danmaku overlay — see below.

## What it does

- A row of chips (buttons) is placed at the top of the Subscriptions
  screen: **All**, one chip per group you created, and
  **Manage groups**.
- Tapping a group chip filters the subscriptions feed so only items
  from channels in that group stay visible. Tapping **All** restores
  everything.
- The switch and the management entry live in Morphe settings →
  Feed: "Subscription channel groups" (off by default) and
  "Manage subscription groups".

## Creating groups and adding channels

1. Enable Morphe settings → Feed → **Subscription channel groups**.
2. Open **Manage subscription groups** (in the same settings screen,
   or the **Manage groups** chip on the subscriptions screen).
3. **Create group** and give it a name (a genre, a theme, anything).
4. The new group opens for editing: type the channel names it should
   contain, **one channel per line**.
5. Save. In the same editor you can also **Delete group**.

Channels are added **by channel display name, exactly as the name is
shown in the subscriptions feed** — including capitalization and
spacing as displayed (matching itself trims extra whitespace and
ignores latin letter case, but the name must otherwise be the same
text). There is no channel picker in v1: names are typed by hand.

Groups are stored in a single string setting
(`morphe_subscription_groups_data`) in the line-based format
documented in `SubscriptionGroupFilter.java`, following the same
one-StringSetting persistence pattern as the channel whitelist.

## How filtering works

- For each visible feed item, the patch reads the item's TextView
  texts. If one line **exactly matches** a channel name known from
  any group, that is the item's channel.
- If a specific group is selected and the identified channel is not
  in it, the item is hidden (`View.GONE`). If it is in the group,
  or **All** is selected, the item stays visible.
- **Items whose channel cannot be identified are never hidden.**
  This conservative rule is deliberate: v1 prefers showing an extra
  video over hiding one it could not attribute.
- Filtering applies to the items currently loaded in the feed list.
  It re-applies when a chip is tapped and when the chip bar attaches.

## Heuristic bar placement — and the debug log line

The subscriptions feed has no stable, version-independent hook for
"a feed list on this screen". The patch therefore reuses the
existing navigation bar hook (`hookNavigationButtonCreated` from
`navigationBarHookPatch`, the pivot-bar tab hook also used by
SeriesTrackerPatch) to learn when the Subscriptions tab exists, then
heuristically walks the view tree: it finds the largest RecyclerView
with children and inserts the chip bar into its parent, right above
the list.

On debug builds every plausible container is logged once, with lines
like:

```
Subscription groups bar candidate: com.google.android...RecyclerView id=.../...
```

If the chips do not appear, or filtering misbehaves on your YouTube
version, report that log line (plus your YouTube version) — it names
the container the heuristic saw, which is what is needed to pin the
attachment point for that version. Expect the attachment point to
need re-verification per YouTube version.

## Known limitations

- **Name matching is fragile.** If a channel renames itself, or the
  app shows a localized/translated channel name different from the
  one you typed, its videos will no longer be identified and will
  stay visible under every group (they are never hidden while
  unidentified).
- Matching is by exact line text: a video whose title, description
  or other line coincidentally equals a known channel name would be
  attributed to that channel.
- Hiding RecyclerView children fights view recycling: a recycled
  item can briefly keep another item's visibility until it rebinds.
- Only items already loaded in the feed are filtered; items loaded
  later (scrolling further) are filtered the next time a chip is
  tapped or the bar re-attaches.
- The subscriptions tab is detected through pivot bar tab creation;
  in layouts where the pivot bar differs (tablets, experiments) the
  bar may not attach at all.
- Group membership is by name only — two different channels with the
  same display name cannot be distinguished.
