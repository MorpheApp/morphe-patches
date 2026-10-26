/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.subscriptiongroups;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Adds an "Add to group" button on channel pages, next to the
 * subscribe button, so a channel can be filed into a subscription
 * group without typing its name in the management dialog.
 *
 * Channel pages have no stable hook in this patch set, so the page is
 * detected heuristically: a tab layout (the channel's own tabs) plus a
 * subscribe/subscribed button. The channel name is taken from the
 * largest text near that button and is logged once per name with the
 * prefix {@code Channel page candidate:} so misdetection can be
 * diagnosed from the debug log, the same approach as the danmaku
 * overlay and the subscriptions chip bar.
 */
public final class ChannelPageGroupButton {

    private static final long SCAN_INTERVAL_MS = 1500L;
    private static final String BUTTON_TAG = "morphe_channel_group_add_button";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** Landmark texts of the channel header subscribe button. */
    private static final String[] ANCHOR_TEXTS = {
            "チャンネル登録", "Subscribe", "登録済み", "Subscribed",
    };

    private static WeakReference<Activity> activityRef = new WeakReference<>(null);
    private static boolean started;
    private static Button injectedButton;
    private static String injectedChannelName;
    private static String lastLoggedCandidate;

    private ChannelPageGroupButton() {
    }

    /** Start (once) the periodic scan. Safe to call on every tab creation. */
    public static void start(Context context) {
        try {
            Activity activity = findActivity(context);
            if (activity != null) {
                activityRef = new WeakReference<>(activity);
            }
            if (!started) {
                started = true;
                MAIN.post(SCAN_RUNNABLE);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Channel page group button start failure", ex);
        }
    }

    private static final Runnable SCAN_RUNNABLE = new Runnable() {
        @Override
        public void run() {
            try {
                scan();
            } catch (Exception ex) {
                Logger.printException(() -> "Channel page scan failure", ex);
            }
            MAIN.postDelayed(this, SCAN_INTERVAL_MS);
        }
    };

    private static void scan() {
        if (!Settings.SUBSCRIPTION_GROUPS.get()) {
            return;
        }
        Activity activity = activityRef.get();
        if (activity == null || activity.isFinishing() || activity.getWindow() == null) {
            return;
        }
        View root = activity.getWindow().getDecorView();
        if (root == null || !containsTabLayout(root, new int[]{0})) {
            return; // Not a channel page (no channel tabs).
        }
        View anchor = findAnchorButton(root, new int[]{0});
        if (anchor == null) {
            return;
        }
        String channelName = extractChannelName(anchor);
        if (channelName == null || channelName.isEmpty()) {
            return;
        }
        if (!channelName.equals(lastLoggedCandidate)) {
            lastLoggedCandidate = channelName;
            final String name = channelName;
            Logger.printDebug(() -> "Channel page candidate: " + name);
        }
        injectButton(anchor, channelName);
    }

    private static Activity findActivity(Context context) {
        Context ctx = context;
        while (ctx instanceof ContextWrapper) {
            if (ctx instanceof Activity) {
                return (Activity) ctx;
            }
            ctx = ((ContextWrapper) ctx).getBaseContext();
        }
        return ctx instanceof Activity ? (Activity) ctx : null;
    }

    private static boolean containsTabLayout(View view, int[] budget) {
        if (budget[0]++ > 4000) {
            return false;
        }
        if (view.getClass().getName().contains("TabLayout") && view.isShown()) {
            return true;
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsTabLayout(group.getChildAt(i), budget)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static View findAnchorButton(View view, int[] budget) {
        if (budget[0]++ > 4000) {
            return null;
        }
        if (view instanceof TextView textView && view.isShown()) {
            CharSequence text = textView.getText();
            if (text != null) {
                for (String anchorText : ANCHOR_TEXTS) {
                    if (anchorText.equals(text.toString().trim())) {
                        return view;
                    }
                }
            }
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findAnchorButton(group.getChildAt(i), budget);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean isAnchorText(String text) {
        for (String anchorText : ANCHOR_TEXTS) {
            if (anchorText.equals(text)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The channel name is the largest visible text in the header area
     * around the subscribe button, excluding button labels, the
     * @handle, and subscriber/video counts.
     */
    private static String extractChannelName(View anchor) {
        View current = anchor;
        for (int level = 0; level < 5; level++) {
            if (!(current.getParent() instanceof View parent)) {
                return null;
            }
            current = parent;
            List<TextView> texts = new ArrayList<>();
            collectTextViews(current, texts, new int[]{0});
            String best = null;
            float bestSize = 0;
            for (TextView textView : texts) {
                if (!textView.isShown() || textView == anchor) {
                    continue;
                }
                CharSequence raw = textView.getText();
                if (raw == null) {
                    continue;
                }
                String text = raw.toString().trim();
                if (text.isEmpty() || isAnchorText(text) || text.startsWith("@")
                        || text.contains("登録者数") || text.contains("subscribers")
                        || text.contains("本の動画") || text.contains("videos")
                        || text.equals("メンバーになる") || text.equals("Join")) {
                    continue;
                }
                if (textView.getTextSize() > bestSize) {
                    bestSize = textView.getTextSize();
                    best = text;
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    private static void collectTextViews(View view, List<TextView> out, int[] budget) {
        if (budget[0]++ > 2000) {
            return;
        }
        if (view instanceof TextView textView) {
            out.add(textView);
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                collectTextViews(group.getChildAt(i), out, budget);
            }
        }
    }

    private static void injectButton(View anchor, String channelName) {
        if (!(anchor.getParent() instanceof ViewGroup parent)) {
            return;
        }
        if (injectedButton != null && injectedButton.getParent() == parent
                && channelName.equals(injectedChannelName)) {
            return; // Already in place for this channel.
        }
        // Reuse a button left in this container by an earlier scan.
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (BUTTON_TAG.equals(child.getTag()) && child instanceof Button existing) {
                injectedButton = existing;
                injectedChannelName = channelName;
                existing.setOnClickListener(
                        view -> showGroupPicker(view.getContext(), channelName));
                return;
            }
        }
        if (injectedButton != null && injectedButton.getParent() instanceof ViewGroup oldParent) {
            oldParent.removeView(injectedButton);
        }
        Button button = new Button(anchor.getContext());
        button.setTag(BUTTON_TAG);
        button.setText(str("morphe_subscription_groups_add_to_group"));
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setOnClickListener(view -> showGroupPicker(view.getContext(), channelName));
        int index = parent.indexOfChild(anchor);
        parent.addView(button, index >= 0 ? index + 1 : parent.getChildCount(),
                new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
        injectedButton = button;
        injectedChannelName = channelName;
    }

    private static void showGroupPicker(Context context, String channelName) {
        try {
            LinkedHashMap<String, LinkedHashSet<String>> groups = SubscriptionGroups.loadGroups();
            if (groups.isEmpty()) {
                Toast.makeText(context, str("morphe_subscription_groups_dialog_empty"),
                        Toast.LENGTH_LONG).show();
                SubscriptionGroupsDialog.show(context);
                return;
            }
            List<String> groupNames = new ArrayList<>(groups.keySet());
            List<String> items = new ArrayList<>(groupNames);
            items.add(str("morphe_subscription_groups_create"));
            new AlertDialog.Builder(context)
                    .setTitle(str("morphe_subscription_groups_add_to_group"))
                    .setItems(items.toArray(new String[0]), (dialog, which) -> {
                        if (which >= groupNames.size()) {
                            SubscriptionGroupsDialog.show(context);
                            return;
                        }
                        LinkedHashMap<String, LinkedHashSet<String>> current =
                                SubscriptionGroups.loadGroups();
                        LinkedHashSet<String> members = current.get(groupNames.get(which));
                        if (members == null) {
                            return;
                        }
                        members.add(channelName);
                        SubscriptionGroups.saveGroups(current);
                        Toast.makeText(context, str("morphe_subscription_groups_saved"),
                                Toast.LENGTH_SHORT).show();
                    })
                    .show();
        } catch (Exception ex) {
            Logger.printException(() -> "Add to group picker failure", ex);
        }
    }
}
