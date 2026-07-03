/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.Utils.getContext;
import static app.morphe.extension.youtube.patches.AddToQueuePatch.disableDelayedFlyoutVideoIdReset;
import static app.morphe.extension.youtube.patches.AddToQueuePatch.getFlyoutVideoId;

import android.content.Intent;
import android.os.SystemClock;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import static app.morphe.extension.shared.settings.SharedYouTubeSettings.REPLACE_LINKS_WITH_SHORTENER;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class OpenSystemShareSheetPatch {

    public static WeakReference<RecyclerView> flyoutMenuRecyclerView = new WeakReference<>(null);
    private static boolean waitUntilClosingDone;

    /**
     * Injection point.
     */
    public static void onFlyoutMenuCreate(final RecyclerView recyclerView) {
        flyoutMenuRecyclerView = new WeakReference<>(recyclerView);
    }

    /**
     * Injection point.
     */
    public static void openSystemShareSheet() {
        if (!Settings.OPEN_SYSTEM_SHARE_SHEET.get()) {
            return;
        }

        final String videoURL =
                (REPLACE_LINKS_WITH_SHORTENER.get() ? "https://youtu.be/" : "https://www.youtube.com/watch?v=") +
                (!getFlyoutVideoId().isEmpty() ? getFlyoutVideoId() : VideoInformation.getVideoId());
        disableDelayedFlyoutVideoIdReset();

        if (!TextUtils.isEmpty(videoURL)) {
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_TEXT, videoURL);
            Intent chooserIntent = Intent.createChooser(shareIntent, "");
            chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);

            try {
                getContext().startActivity(chooserIntent);
            } catch (Exception ex) {
                Logger.printException(() -> "Can not open System Share panel: " + videoURL, ex);
            }
        }
    }

    public static void closeLithoAppShareSheet() {
        if (waitUntilClosingDone) {
            return;
        }
        waitUntilClosingDone = true;

        RecyclerView shareSheetRecyclerView = flyoutMenuRecyclerView.get();
        if (shareSheetRecyclerView != null) {
            View decorView = shareSheetRecyclerView.getRootView();

            if (decorView != null) {
                float clickX = decorView.getWidth() * 0.5f;
                float clickY = decorView.getHeight() * 0.25f;

                if (clickX <= 0 || clickY <= 0) {
                    clickX = clickY = 200.0f;
                }

                final long eventTime = SystemClock.uptimeMillis();

                for (int i = 0; i < 2; i++) {
                    final boolean firstIteration = i == 0;
                    MotionEvent touchEvent = MotionEvent.obtain(
                            eventTime,
                            firstIteration ? eventTime : eventTime + 10,
                            firstIteration ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_UP,
                            clickX,
                            clickY,
                            0
                    );
                    decorView.dispatchTouchEvent(touchEvent);
                    touchEvent.recycle();
                }

                // Given the speed of Litho's elements renders, disable 'waitUntilClosingDone'
                // after a 500ms delay to ensure that only a single filtered
                // Litho object triggers the panel's closure.
                Utils.runOnMainThreadDelayed(
                        () -> waitUntilClosingDone = false,
                        500
                );
            }
        }
    }
}
