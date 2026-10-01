/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3433
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import androidx.annotation.Nullable;

import com.google.protobuf.MessageLite;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.youtube.innertube.BrowseResponseOuterClass.BrowseTab;
import app.morphe.extension.youtube.innertube.BrowseResponseOuterClass.TabRenderer;
import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.PivotBarItemRenderer;
import app.morphe.extension.youtube.innertube.IconOuterClass.YTIconType;

/**
 * Disables the A/B layout that moves Subscriptions from the navigation bar to a tab of the Home feed.
 * The layout is set by the server, so the classic layout is restored from the server responses.
 * Responses are only read and never serialized again,
 * because the protos would be parsed without extensions and lose most of their content.
 */
@SuppressWarnings("unused")
public final class ClassicSubscriptionsLayoutPatch {

    /**
     * Interface to use obfuscated methods.
     */
    public interface PivotBarItemInterface {
        // Method is added during patching.
        MessageLite patch_getPivotBarItemRenderer();
    }

    private static final String HOME_BROWSE_ID = "FEwhat_to_watch";
    private static final String SUBSCRIPTIONS_BROWSE_ID = "FEsubscriptions";
    private static final String SHORTS_BROWSE_ID = "FEshorts";
    private static final String LIBRARY_BROWSE_ID = "FElibrary";

    /**
     * Icon of the Subscriptions navigation button, with the same style of the Home button.
     */
    private static volatile YTIconType subscriptionsIconType = YTIconType.PIVOT_SUBSCRIPTIONS;

    private static boolean isFeedTab(TabRenderer tabRenderer) {
        String browseId = tabRenderer.getEndpoint().getBrowseEndpoint().getBrowseId();
        if (browseId.isEmpty()) {
            browseId = tabRenderer.getTabIdentifier();
        }
        return HOME_BROWSE_ID.equals(browseId) || SUBSCRIPTIONS_BROWSE_ID.equals(browseId);
    }

    /**
     * Injection point.
     *
     * @param tabs Tab protos of the browse response.
     * @return Only the selected tab, if the tabs are the Home and Subscriptions feeds.
     */
    public static List<Object> filterBrowseTabs(List<Object> tabs) {
        if (tabs == null || tabs.size() <= 1) return tabs;

        try {
            int selectedIndex = 0;
            boolean isFeed = false;
            for (int i = tabs.size() - 1; i >= 0; i--) {
                TabRenderer tabRenderer = BrowseTab.parseFrom(
                        ((MessageLite) tabs.get(i)).toByteArray()
                ).getTabRenderer();

                isFeed |= isFeedTab(tabRenderer);
                if (tabRenderer.getSelected()) {
                    selectedIndex = i;
                }
            }
            // Channels and other pages also use tabs.
            if (!isFeed) return tabs;

            List<Object> filtered = new ArrayList<>(1);
            filtered.add(tabs.get(selectedIndex));
            return filtered;
        } catch (Exception ex) {
            Logger.printException(() -> "filterBrowseTabs failure", ex);
        }

        return tabs;
    }

    @Nullable
    private static PivotBarItemRenderer getPivotBarItemRenderer(Object pivotBarItem) {
        try {
            MessageLite messageLite = ((PivotBarItemInterface) pivotBarItem).patch_getPivotBarItemRenderer();
            return PivotBarItemRenderer.parseFrom(messageLite.toByteArray());
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to parse PivotBarItemRenderer", ex);
        }
        return null;
    }

    @Nullable
    private static String getPivotIdentifier(Object pivotBarItem) {
        PivotBarItemRenderer renderer = getPivotBarItemRenderer(pivotBarItem);
        return renderer == null ? null : renderer.getPivotIdentifier();
    }

    /**
     * Injection point.
     *
     * @param list Navigation bar items.
     * @return If the Subscriptions button is missing.
     */
    public static boolean needsSubscriptionsPivotBarItem(List<Object> list) {
        if (list == null || list.isEmpty()) return false;

        PivotBarItemRenderer homeRenderer = null;
        for (Object item : list) {
            String pivotIdentifier = getPivotIdentifier(item);
            if (SUBSCRIPTIONS_BROWSE_ID.equals(pivotIdentifier)) {
                return false;
            }
            if (homeRenderer == null && HOME_BROWSE_ID.equals(pivotIdentifier)) {
                homeRenderer = getPivotBarItemRenderer(item);
            }
        }
        if (homeRenderer == null) return false;

        subscriptionsIconType = homeRenderer.getIcon().getYtIconType() == YTIconType.TAB_HOME_CAIRO
                ? YTIconType.TAB_SUBSCRIPTIONS_CAIRO
                : YTIconType.PIVOT_SUBSCRIPTIONS;
        return true;
    }

    /**
     * Injection point.
     * Called after {@link #needsSubscriptionsPivotBarItem(List)}.
     *
     * @return Icon type value of the Subscriptions navigation button.
     */
    public static int getSubscriptionsIconType() {
        return subscriptionsIconType.getNumber();
    }

    /**
     * Injection point.
     */
    public static String getSubscriptionsLabel() {
        return ResourceUtils.getString("subscriptions");
    }

    /**
     * Injection point.
     *
     * @param subscriptionsItem Subscriptions navigation bar item, or null.
     */
    public static List<Object> addSubscriptionsPivotBarItem(List<Object> list, @Nullable Object subscriptionsItem) {
        if (subscriptionsItem == null) return list;

        // Classic layout: Subscriptions is after Shorts, or before You if Shorts is missing.
        int index = list.size();
        for (int i = 0, size = list.size(); i < size; i++) {
            String pivotIdentifier = getPivotIdentifier(list.get(i));
            if (SHORTS_BROWSE_ID.equals(pivotIdentifier)) {
                index = i + 1;
                break;
            }
            if (LIBRARY_BROWSE_ID.equals(pivotIdentifier)) {
                index = i;
            }
        }

        List<Object> newList = new ArrayList<>(list);
        newList.add(index, subscriptionsItem);
        return newList;
    }

    /**
     * Injection point.
     * Navigation bar used before the guide response is loaded.
     */
    public static boolean hideOfflineSubscriptionsButton(boolean original) {
        return false;
    }
}
