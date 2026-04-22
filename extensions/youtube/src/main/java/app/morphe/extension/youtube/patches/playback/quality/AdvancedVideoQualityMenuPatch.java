package app.morphe.extension.youtube.patches.playback.quality;

import android.support.v7.widget.RecyclerView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.components.AdvancedVideoQualityMenuFilter;
import app.morphe.extension.youtube.settings.Settings;

/**
 * This patch contains the logic to always open the advanced video quality menu.
 */
@SuppressWarnings("unused")
public final class AdvancedVideoQualityMenuPatch {

    /**
     * Injection point.  Regular videos.
     */
    public static void onFlyoutMenuCreate(RecyclerView recyclerView) {
        if (!Settings.ADVANCED_VIDEO_QUALITY_MENU.get()) return;

        recyclerView.getViewTreeObserver().addOnDrawListener(() -> {
            try {
                // Check if the current view is the quality menu.
                if (!AdvancedVideoQualityMenuFilter.isVideoQualityMenuVisible || recyclerView.getChildCount() == 0) {
                    return;
                }
                AdvancedVideoQualityMenuFilter.isVideoQualityMenuVisible = false;

                if (!(Utils.getParentView(recyclerView, 3) instanceof ViewGroup quickQualityViewParent)) {
                    return;
                }

                if (!(recyclerView.getChildAt(0) instanceof ViewGroup firstChildGroup)) {
                    return;
                }

                if (firstChildGroup.getChildCount() < 4) {
                    return;
                }

                if (!(firstChildGroup.getChildAt(3) instanceof ViewGroup advancedQualityView)) {
                    return;
                }

                quickQualityViewParent.setVisibility(View.GONE);

                // Click the "Advanced" quality menu to show the "old" quality menu.
                advancedQualityView.callOnClick();
            } catch (Exception ex) {
                Logger.printException(() -> "onFlyoutMenuCreate failure", ex);
            }
        });
    }

    /**
     * Injection point.
     * <p>
     * Shorts video quality flyout.
     */
    public static void addVideoQualityListMenuListener(ListView listView) {
        if (!Settings.ADVANCED_VIDEO_QUALITY_MENU.get()) return;

        listView.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
            @Override
            public void onChildViewAdded(View parent, View child) {
                try {
                    parent.setVisibility(View.GONE);

                    final var indexOfAdvancedQualityMenuItem = 4;
                    if (listView.indexOfChild(child) != indexOfAdvancedQualityMenuItem) return;

                    listView.setSoundEffectsEnabled(false);
                    final var qualityItemMenuPosition = 4;
                    listView.performItemClick(null, qualityItemMenuPosition, 0);
                } catch (Exception ex) {
                    Logger.printException(() -> "showAdvancedVideoQualityMenu failure", ex);
                }
            }

            @Override
            public void onChildViewRemoved(View parent, View child) {
            }
        });
    }

    /**
     * Injection point.
     * <p>
     * Used to force the creation of the advanced menu item for the Shorts quality flyout.
     */
    public static boolean forceAdvancedVideoQualityMenuCreation(boolean original) {
        return Settings.ADVANCED_VIDEO_QUALITY_MENU.get() || original;
    }
}