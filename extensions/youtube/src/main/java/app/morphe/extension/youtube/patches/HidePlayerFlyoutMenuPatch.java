package app.morphe.extension.youtube.patches;

import android.support.v7.widget.RecyclerView;
import android.view.View;
import android.view.ViewGroup;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class HidePlayerFlyoutMenuPatch {

    public static volatile boolean isQualityMenuVisible = false;
    public static volatile boolean isCaptionsMenuVisible = false;

    private HidePlayerFlyoutMenuPatch() {}

    /**
     * Injection point.
     */
    public static void onFlyoutMenuCreate(RecyclerView recyclerView) {
        recyclerView.getViewTreeObserver().addOnDrawListener(() -> {
            try {
                if (!isQualityMenuVisible && !isCaptionsMenuVisible) {
                    return;
                }

                if (recyclerView.getChildCount() == 0) {
                    return;
                }

                View sheetContent = recyclerView.getChildAt(0);
                if (!(sheetContent instanceof ViewGroup viewGroup)) {
                    return;
                }

                int childCount = viewGroup.getChildCount();

                if (childCount > 0) {
                    if ((isQualityMenuVisible && Settings.HIDE_PLAYER_FLYOUT_QUALITY_FOOTER.get()) ||
                            (isCaptionsMenuVisible && Settings.HIDE_PLAYER_FLYOUT_CAPTIONS_FOOTER.get())) {

                        View footer = viewGroup.getChildAt(childCount - 1);
                        if (footer != null) {
                            footer.setVisibility(View.GONE);
                            footer.setPadding(0, 0, 0, 0);

                            ViewGroup.LayoutParams params = footer.getLayoutParams();
                            if (params != null) {
                                params.height = 0;
                                params.width = 0;
                                if (params instanceof ViewGroup.MarginLayoutParams marginParams) {
                                    marginParams.setMargins(0, 0, 0, 0);
                                }
                                footer.setLayoutParams(params);
                            }
                        }
                    }

                    if (isQualityMenuVisible && Settings.HIDE_PLAYER_FLYOUT_QUALITY_HEADER.get()) {
                        View header = viewGroup.getChildAt(0);
                        if (header != null) {
                            header.setVisibility(View.GONE);
                            header.setPadding(0, 0, 0, 0);

                            ViewGroup.LayoutParams params = header.getLayoutParams();
                            if (params != null) {
                                params.height = 0;
                                params.width = 0;
                                if (params instanceof ViewGroup.MarginLayoutParams marginParams) {
                                    marginParams.setMargins(0, 0, 0, 0);
                                }
                                header.setLayoutParams(params);
                            }
                        }
                    }
                }

                isQualityMenuVisible = false;
                isCaptionsMenuVisible = false;

            } catch (Exception ex) {
                Logger.printException(() -> "HidePlayerFlyoutMenuPatch failure", ex);
            }
        });
    }
}