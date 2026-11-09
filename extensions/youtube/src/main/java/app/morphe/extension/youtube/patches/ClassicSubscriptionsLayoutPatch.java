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
import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.FormattedString;
import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.PivotBarIconOnlyItemRendererBytes;
import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.PivotBarItemBytes;
import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.PivotBarItemRenderer;
import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.PivotBarItemRendererBytes;
import app.morphe.extension.youtube.innertube.GuideResponseOuterClass.TextRun;
import app.morphe.extension.youtube.innertube.IconOuterClass.YTIconType;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Disables the A/B layout that moves Subscriptions from the navigation bar to a tab of the Home feed,
 * and the A/B layout with navigation buttons without labels.
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
        /**
         * Method is added during patching.
         *
         * @return The PivotBarItemRenderer proto, or null if the item is a different renderer.
         */
        @Nullable
        MessageLite patch_getPivotBarItemRenderer();

        /**
         * Method is added during patching.
         *
         * @return The PivotBarIconOnlyItemRenderer proto, or null if the item is a different renderer.
         */
        @Nullable
        MessageLite patch_getPivotBarIconOnlyItemRenderer();
    }

    // Pivot identifiers of the navigation buttons.
    public static final String HOME_BROWSE_ID = "FEwhat_to_watch";
    public static final String SHORTS_BROWSE_ID = "FEshorts";
    public static final String CREATE_BROWSE_ID = "FEuploads";
    public static final String SUBSCRIPTIONS_BROWSE_ID = "FEsubscriptions";
    public static final String NOTIFICATIONS_BROWSE_ID = "FEactivity";
    public static final String LIBRARY_BROWSE_ID = "FElibrary";

    /**
     * Pivot identifier and label of a navigation button.
     */
    record PivotBarItemInfo(String id, String label) {}

    private static final boolean DISABLE_ICON_ONLY_NAVIGATION_BUTTONS =
            Settings.DISABLE_ICON_ONLY_NAVIGATION_BUTTONS.get();

    private static final boolean DISABLE_TOOLBAR_NAVIGATION_TABS =
            Settings.DISABLE_TOOLBAR_NAVIGATION_TABS.get();

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
        if (!DISABLE_TOOLBAR_NAVIGATION_TABS || tabs == null || tabs.size() <= 1) return tabs;

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

    /**
     * Injection point.
     * The A/B layout without labels uses icon only navigation buttons, that are created with
     * a different method and are ignored by the other navigation bar patches.
     *
     * @param pivotBarItem Navigation bar item proto.
     * @return The item converted to a button with a label, or null if the item is not icon only.
     */
    @Nullable
    public static byte[] convertIconOnlyPivotBarItem(MessageLite pivotBarItem) {
        if (!DISABLE_ICON_ONLY_NAVIGATION_BUTTONS) return null;

        try {
            PivotBarItemBytes item = PivotBarItemBytes.parseFrom(pivotBarItem.toByteArray());
            if (!item.hasPivotBarIconOnlyItemRenderer()) return null;

            PivotBarIconOnlyItemRendererBytes iconOnly =
                    PivotBarIconOnlyItemRendererBytes.parseFrom(item.getPivotBarIconOnlyItemRenderer());
            // The label of icon only buttons is only the accessibility label.
            String label = iconOnly.getAccessibility().getRuns().getText();

            PivotBarItemRendererBytes renderer = PivotBarItemRendererBytes.newBuilder()
                    .setPivotIdentifier(iconOnly.getPivotIdentifier())
                    .setNavigationEndpoint(iconOnly.getNavigationEndpoint())
                    .setTitle(FormattedString.newBuilder().addRuns(TextRun.newBuilder().setText(label)))
                    .setIcon(iconOnly.getIcon())
                    .setTrackingParams(iconOnly.getTrackingParams())
                    .setTargetId(iconOnly.getTargetId())
                    .setNavigationType(iconOnly.getNavigationType())
                    .setThumbnail(iconOnly.getThumbnail())
                    .build();

            Logger.printDebug(() -> "Converting icon only navigation button: " + iconOnly.getPivotIdentifier());
            return PivotBarItemBytes.newBuilder()
                    .setPivotBarItemRenderer(renderer.toByteString())
                    .build()
                    .toByteArray();
        } catch (Exception ex) {
            Logger.printException(() -> "convertIconOnlyPivotBarItem failure", ex);
        }

        return null;
    }

    @Nullable
    private static PivotBarItemRenderer getPivotBarItemRenderer(Object pivotBarItem) {
        try {
            if (!(pivotBarItem instanceof PivotBarItemInterface renderInterface)) {
                if (Settings.DEBUG.get()) {
                    Logger.printException(() -> "Debug: Unknown pivot bar class: " + pivotBarItem.getClass());
                }
            } else {
                MessageLite messageLite = renderInterface.patch_getPivotBarItemRenderer();
                if (messageLite != null) {
                    return PivotBarItemRenderer.parseFrom(messageLite.toByteArray());
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to parse PivotBarItemRenderer", ex);
        }
        return null;
    }

    @Nullable
    static PivotBarItemInfo getPivotBarItemInfo(Object pivotBarItem) {
        PivotBarItemRenderer renderer = getPivotBarItemRenderer(pivotBarItem);
        if (renderer != null) {
            // The Search and Settings buttons of Morphe only change the accessibility label of the Home button.
            String label = renderer.getAccessibility().getAccessibilityData().getLabel();
            return new PivotBarItemInfo(renderer.getPivotIdentifier(),
                    label.isEmpty() ? renderer.getTitle().getRuns().getText() : label);
        }

        // Navigation buttons without labels, if they are not converted to buttons with labels.
        if (pivotBarItem instanceof PivotBarItemInterface renderInterface) {
            MessageLite iconOnly = renderInterface.patch_getPivotBarIconOnlyItemRenderer();
            if (iconOnly != null) {
                try {
                    var iconOnlyRenderer = PivotBarIconOnlyItemRendererBytes.parseFrom(iconOnly.toByteArray());
                    return new PivotBarItemInfo(iconOnlyRenderer.getPivotIdentifier(),
                            iconOnlyRenderer.getAccessibility().getRuns().getText());
                } catch (Exception ex) {
                    Logger.printException(() -> "Failed to parse PivotBarIconOnlyItemRenderer", ex);
                }
            }
        }
        return null;
    }

    /**
     * @return Index of the You button, or the size of the list if it's missing.
     */
    static int indexOfLibraryButton(List<Object> list) {
        for (int i = 0, size = list.size(); i < size; i++) {
            PivotBarItemInfo info = getPivotBarItemInfo(list.get(i));
            if (info != null && LIBRARY_BROWSE_ID.equals(info.id())) {
                return i;
            }
        }
        return list.size();
    }

    /**
     * Injection point.
     *
     * @param list Navigation bar items.
     * @return If the Subscriptions button is missing.
     */
    public static boolean needsSubscriptionsPivotBarItem(List<Object> list) {
        if (!DISABLE_TOOLBAR_NAVIGATION_TABS || list == null || list.isEmpty()) return false;

        PivotBarItemRenderer homeRenderer = null;
        for (Object item : list) {
            PivotBarItemInfo info = getPivotBarItemInfo(item);
            String pivotIdentifier = info == null ? null : info.id();
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

        // Classic layout: Subscriptions is before You (after Create, if the server sends it),
        // or the last button if You is missing.
        List<Object> newList = new ArrayList<>(list);
        newList.add(indexOfLibraryButton(list), subscriptionsItem);
        return newList;
    }

    /**
     * Injection point.
     * Navigation bar used before the guide response is loaded.
     */
    public static boolean hideOfflineSubscriptionsButton(boolean original) {
        return !DISABLE_TOOLBAR_NAVIGATION_TABS && original;
    }
}
