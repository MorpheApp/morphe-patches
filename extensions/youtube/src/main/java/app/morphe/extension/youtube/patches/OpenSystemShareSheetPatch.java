/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.Utils.getContext;

import android.content.Intent;
import android.os.SystemClock;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;

import java.lang.ref.WeakReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.patches.SanitizeSharingLinksPatch;

@SuppressWarnings("unused")
public final class OpenSystemShareSheetPatch {

    public static final Pattern rawVideoURLRegex = Pattern.compile(
            "ANDROID_SYSTEM_SHARE_DIALOG.*?android\\.intent\\.extra\\.TEXT.*?❙([^❙]+)❙"
    );
    public static boolean systemSheetOpened;
    public static WeakReference<RecyclerView> flyoutMenuRecyclerView = new WeakReference<>(null);

    /**
     * Injection point.
     */
    public static void onFlyoutMenuCreate(final RecyclerView recyclerView) {
        flyoutMenuRecyclerView = new WeakReference<>(recyclerView);
    }

    public static boolean openSystemShareSheet(String asciiBuffer) {
        if (asciiBuffer.startsWith("Eshare_sheet_share_targets_third_party_segment.e")) {
            Matcher matcher = rawVideoURLRegex.matcher(asciiBuffer);

            if (matcher.find()) {
                systemSheetOpened = true;
                RecyclerView shareSheetRecyclerView = flyoutMenuRecyclerView.get();
                if (shareSheetRecyclerView != null) {
                    performClickOutsidePanel(shareSheetRecyclerView.getRootView());
                    final String rawVideoURL = matcher.group(1);
                    if (!TextUtils.isEmpty(rawVideoURL)) {
                        int urlIndex = rawVideoURL.indexOf("http");
                        if (urlIndex >= 0) {
                            final String sanitizedVideoURL = SanitizeSharingLinksPatch
                                    .sanitize(rawVideoURL.substring(urlIndex));
                            Intent shareIntent = new Intent(Intent.ACTION_SEND);
                            shareIntent.setType("text/plain");
                            shareIntent.putExtra(Intent.EXTRA_TEXT, sanitizedVideoURL);
                            Intent chooserIntent = Intent.createChooser(shareIntent, "");
                            chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
                            try {
                                getContext().startActivity(chooserIntent);
                            } catch (Exception ex) {
                                Logger.printException(() -> "Can not open System Share panel delayed: " + sanitizedVideoURL, ex);
                            }
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    // To close the Share sheet panel, a touch event sent through decorView is needed.
    public static void performClickOutsidePanel(View decorView) {
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
    }
}
