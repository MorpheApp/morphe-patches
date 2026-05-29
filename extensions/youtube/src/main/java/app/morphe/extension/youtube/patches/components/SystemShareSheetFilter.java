/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.components;

import static app.morphe.extension.shared.Utils.getContext;
import static app.morphe.extension.youtube.patches.OpenSystemShareSheetPatch.flyoutMenuRecyclerView;
import static app.morphe.extension.youtube.patches.OpenSystemShareSheetPatch.rawVideoURLRegex;
import static app.morphe.extension.youtube.patches.OpenSystemShareSheetPatch.systemSheetOpened;
import static app.morphe.extension.youtube.settings.Settings.OPEN_SYSTEM_SHARE_SHEET;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.support.v7.widget.RecyclerView;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.regex.Matcher;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.patches.SanitizeSharingLinksPatch;
import app.morphe.extension.youtube.shared.ConversionContext.ContextInterface;

@SuppressWarnings("unused")
public final class SystemShareSheetFilter extends Filter {

    public SystemShareSheetFilter() {
        addPathCallbacks(new StringFilterGroup(
                OPEN_SYSTEM_SHARE_SHEET,
                "share_sheet_container."
        ));
    }

    /**
     * Replaces YouTube's in-app share sheet with the system share sheet.
     */
    @Override
    boolean isFiltered(ContextInterface contextInterface,
                       String identifier,
                       String accessibility,
                       String path,
                       byte[] buffer,
                       String clearlyBuffer,
                       StringFilterGroup matchedGroup,
                       FilterContentType contentType,
                       int contentIndex) {
        if (systemSheetOpened) {
            return false;
        }
        if (clearlyBuffer.startsWith("Eshare_sheet_share_targets_third_party_segment.e")) {
            Matcher matcher = rawVideoURLRegex.matcher(clearlyBuffer);
            if (matcher.find()) {
                systemSheetOpened = true;
                performClickOutsidePanel(flyoutMenuRecyclerView.get().getRootView());
                final String rawVideoURL = matcher.group(1);
                if (!TextUtils.isEmpty(rawVideoURL)) {
                    int urlIndex = rawVideoURL.indexOf("http");
                    if (urlIndex > -1) {
                        final String sanitizedVideoURL =
                                SanitizeSharingLinksPatch.sanitize(rawVideoURL.substring(urlIndex));
                        try {
                            Intent shareIntent = new Intent(Intent.ACTION_SEND);
                            shareIntent.setType("text/plain");
                            shareIntent.putExtra(Intent.EXTRA_TEXT, sanitizedVideoURL);
                            Intent chooserIntent = Intent.createChooser(shareIntent, "");
                            chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            getContext().startActivity(chooserIntent);
                        } catch (Exception ex) {
                            Logger.printException(() -> "Can not open System Share panel: " + sanitizedVideoURL, ex);
                        }
                    }
                }
                systemSheetOpened = false;
            }
        }
        return true;
    }

    // Useful to send a touch event to close the YouTube sharing panel.
    private void performClickOutsidePanel(View shareSheetDecorView) {
        MotionEvent outsidePanelTap = MotionEvent.obtain(
                SystemClock.uptimeMillis(),
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_OUTSIDE,
                0.0f,
                0.0f,
                0
        );
        shareSheetDecorView.dispatchTouchEvent(outsidePanelTap);
        outsidePanelTap.recycle();
    }
}
