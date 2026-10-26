/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.subscriptiongroups;

import static app.morphe.extension.shared.StringRef.str;

import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.NavigationBar;

/**
 * Injection points for the subscription channel groups patch.
 *
 * Wiring (see the Kotlin patch of the same name): the patch registers
 * {@link #navigationTabCreated} through the existing navigation bar
 * hook ({@code hookNavigationButtonCreated} of navigationBarHookPatch,
 * the same registration SeriesTrackerPatch uses). It fires when a
 * pivot bar tab view is created. The tab views exist from app start,
 * while the subscriptions feed content loads later, so attach attempts
 * are retried a few times on the main thread.
 *
 * Everything in here is heuristic, like danmaku v1: the feed list,
 * its parent container and the insertion point are located by walking
 * the view tree, not by a version-specific fingerprint. Debug logs
 * print every plausible container with the prefix
 * "Subscription groups bar candidate: " so the correct container can
 * be pinned if a YouTube version changes the tree. This has NOT been
 * verified on a real device or APK build.
 *
 * Filtering is conservative (see SubscriptionGroupFilter): an item is
 * hidden only when a TextView line of the item exactly matches a known
 * channel name that is not in the selected group. Items whose channel
 * cannot be identified are never hidden. Note that hiding direct
 * children of a RecyclerView fights its view recycling: a recycled
 * item can briefly keep the visibility of an earlier item until it is
 * rebound. Accepted for v1 and documented in the README.
 */
@SuppressWarnings("unused")
public final class SubscriptionGroupsPatch {

    private static final String BAR_TAG = "morphe_subscription_groups_bar";
    private static final long[] ATTACH_RETRY_DELAYS_MS = {0, 600, 1800, 3500};

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<String> loggedCandidates = new HashSet<>();

    /** Currently selected group name, or null for "All". In-memory only. */
    private static volatile String currentSelection;

    private SubscriptionGroupsPatch() {
    }

    public static String getCurrentSelection() {
        return currentSelection;
    }

    /**
     * Injection point, called for every pivot bar tab that is created.
     * Only the subscriptions tab is acted on.
     */
    public static void navigationTabCreated(NavigationBar.NavigationButton button, View tabView) {
        try {
            // The channel-page "add to group" watcher runs regardless of
            // the tab; it checks the feature setting itself on each scan.
            if (tabView != null) {
                ChannelPageGroupButton.start(tabView.getContext());
            }
            if (!Settings.SUBSCRIPTION_GROUPS.get()) {
                return;
            }
            if (button != NavigationBar.NavigationButton.SUBSCRIPTIONS || tabView == null) {
                return;
            }
            View root = tabView.getRootView();
            if (root instanceof ViewGroup) {
                for (long delay : ATTACH_RETRY_DELAYS_MS) {
                    MAIN.postDelayed(() -> onScreenRootAvailable(root), delay);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Subscription groups navigationTabCreated failure", ex);
        }
    }

    /**
     * Entry point usable by any future hook that can hand over a
     * top-level screen root view.
     */
    public static void onScreenRootAvailable(View root) {
        try {
            if (!Settings.SUBSCRIPTION_GROUPS.get() || !(root instanceof ViewGroup)) {
                return;
            }
            logContainerCandidates((ViewGroup) root);
            attachChipBar((ViewGroup) root);
        } catch (Exception ex) {
            Logger.printException(() -> "Subscription groups attach failure", ex);
        }
    }

    // region View tree walking

    private static void logContainerCandidates(ViewGroup root) {
        List<ViewGroup> groups = new ArrayList<>();
        collectViewGroups(root, groups);
        for (ViewGroup group : groups) {
            if (!isPlausibleContainer(group)) {
                continue;
            }
            String description = "Subscription groups bar candidate: "
                    + group.getClass().getName() + " id=" + resourceName(group);
            // Log each distinct candidate only once per process.
            if (loggedCandidates.add(description)) {
                Logger.printDebug(() -> description);
            }
        }
    }

    private static boolean isPlausibleContainer(View view) {
        String className = view.getClass().getName();
        if (className.contains("RecyclerView")) {
            return true;
        }
        String resName = resourceName(view).toLowerCase();
        return resName.contains("recycler") || resName.contains("feed")
                || resName.contains("browse") || resName.contains("container");
    }

    private static String resourceName(View view) {
        try {
            if (view.getId() != View.NO_ID && view.getResources() != null) {
                return view.getResources().getResourceName(view.getId());
            }
        } catch (Exception ignored) {
        }
        return "no-id";
    }

    private static void collectViewGroups(View view, List<ViewGroup> out) {
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            out.add(group);
            for (int i = 0; i < group.getChildCount(); i++) {
                collectViewGroups(group.getChildAt(i), out);
            }
        }
    }

    private static void collectTextLines(View view, List<String> out) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && text.length() > 0) {
                out.add(text.toString());
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectTextLines(group.getChildAt(i), out);
            }
        }
    }

    /**
     * The subscriptions feed list: the RecyclerView-ish ViewGroup with
     * the most visible area that already has children. Null if the feed
     * is not (yet) in the tree.
     */
    private static ViewGroup findFeedList(ViewGroup root) {
        List<ViewGroup> groups = new ArrayList<>();
        collectViewGroups(root, groups);
        ViewGroup best = null;
        long bestArea = -1;
        for (ViewGroup group : groups) {
            if (!group.getClass().getName().contains("RecyclerView")
                    || group.getChildCount() == 0) {
                continue;
            }
            long area = (long) group.getWidth() * group.getHeight();
            if (area > bestArea) {
                bestArea = area;
                best = group;
            }
        }
        return best;
    }

    // endregion

    // region Chip bar

    private static void attachChipBar(ViewGroup root) {
        ViewGroup feedList = findFeedList(root);
        if (feedList == null) {
            return; // Feed not loaded yet; a later retry (or tab creation) tries again.
        }
        ViewParent parent = feedList.getParent();
        if (!(parent instanceof ViewGroup)) {
            return;
        }
        ViewGroup container = (ViewGroup) parent;

        HorizontalScrollView existing = findAttachedBar(container);
        if (existing != null) {
            // Groups may have been edited in settings since the bar was built.
            rebuildChips(existing, root);
            applyFiltering(root);
            return;
        }

        HorizontalScrollView bar = new HorizontalScrollView(root.getContext());
        bar.setTag(BAR_TAG);
        bar.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(root.getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        bar.addView(row);
        rebuildChips(bar, root);

        int index = container.indexOfChild(feedList);
        container.addView(bar, index,
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
        applyFiltering(root);
    }

    private static HorizontalScrollView findAttachedBar(ViewGroup container) {
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child instanceof HorizontalScrollView && BAR_TAG.equals(child.getTag())) {
                return (HorizontalScrollView) child;
            }
        }
        return null;
    }

    private static void rebuildChips(HorizontalScrollView bar, View root) {
        LinearLayout row = (LinearLayout) bar.getChildAt(0);
        row.removeAllViews();

        LinkedHashMap<String, LinkedHashSet<String>> groups = SubscriptionGroups.loadGroups();
        row.addView(createChip(bar, str("morphe_subscription_groups_all"), null, root));
        for (String groupName : groups.keySet()) {
            row.addView(createChip(bar, groupName, groupName, root));
        }
        row.addView(createChip(bar, str("morphe_subscription_groups_chip_manage"), null, root,
                () -> SubscriptionGroupsDialog.show(bar.getContext())));
        updateChipStyles(row);
    }

    private static Button createChip(View parent, String label, String groupName, View root) {
        return createChip(parent, label, groupName, root, null);
    }

    private static Button createChip(View parent, String label, String groupName, View root,
                                     Runnable onClickOverride) {
        Button chip = new Button(parent.getContext());
        chip.setText(label);
        chip.setAllCaps(false);
        chip.setTextSize(14);
        float density = parent.getContext().getResources().getDisplayMetrics().density;
        int horizontalPadding = Math.round(12 * density);
        chip.setPadding(horizontalPadding, 0, horizontalPadding, 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, Math.round(40 * density));
        params.leftMargin = Math.round(4 * density);
        params.rightMargin = Math.round(4 * density);
        chip.setLayoutParams(params);
        // The group name (null for All / Manage) doubles as the chip tag.
        chip.setTag(groupName);
        chip.setOnClickListener(view -> {
            if (onClickOverride != null) {
                onClickOverride.run();
                return;
            }
            currentSelection = groupName;
            if (parent instanceof HorizontalScrollView
                    && ((HorizontalScrollView) parent).getChildAt(0) instanceof LinearLayout) {
                updateChipStyles((LinearLayout) ((HorizontalScrollView) parent).getChildAt(0));
            }
            applyFiltering(root);
        });
        return chip;
    }

    private static void updateChipStyles(LinearLayout row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (!(child instanceof Button)) {
                continue;
            }
            Button chip = (Button) child;
            Object tag = chip.getTag();
            // The Manage chip has no tag of its own and is never "selected".
            boolean isManage = chip.getText().toString()
                    .equals(str("morphe_subscription_groups_chip_manage"));
            boolean selected = !isManage
                    && ((tag == null && currentSelection == null)
                    || (tag != null && tag.equals(currentSelection)));
            chip.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
            chip.setAlpha(selected ? 1f : 0.65f);
        }
    }

    // endregion

    // region Filtering

    /**
     * Shows/hides the direct children of the feed list according to the
     * current selection. See the class comment for the conservative
     * unidentified-channel rule.
     */
    public static void applyFiltering(View root) {
        try {
            if (!Settings.SUBSCRIPTION_GROUPS.get() || !(root instanceof ViewGroup)) {
                return;
            }
            ViewGroup feedList = findFeedList((ViewGroup) root);
            if (feedList == null) {
                return;
            }
            LinkedHashMap<String, LinkedHashSet<String>> groups = SubscriptionGroups.loadGroups();
            LinkedHashSet<String> knownNames = SubscriptionGroupFilter.knownChannelNames(groups);

            for (int i = 0; i < feedList.getChildCount(); i++) {
                View item = feedList.getChildAt(i);
                String channelName = null;
                if (currentSelection != null && !knownNames.isEmpty()) {
                    List<String> textLines = new ArrayList<>();
                    collectTextLines(item, textLines);
                    channelName = SubscriptionGroupFilter.findChannelName(textLines, knownNames);
                }
                boolean show = SubscriptionGroupFilter.shouldShow(
                        currentSelection, channelName, groups);
                item.setVisibility(show ? View.VISIBLE : View.GONE);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Subscription groups filtering failure", ex);
        }
    }

    // endregion
}
