/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */

package app.morphe.extension.youtube.patches.components;

import app.morphe.extension.youtube.patches.OpenSystemShareSheetPatch;
import app.morphe.extension.youtube.settings.Settings;

/**
 * LithoFilter for {@link OpenSystemShareSheetPatch}.
 */
public final class OpenSystemShareSheetFilter extends Filter {

    public static volatile boolean isShareSheetVisible;

    public OpenSystemShareSheetFilter() {
        addPathCallbacks(new StringFilterGroup(
                Settings.OPEN_SYSTEM_SHARE_SHEET,
                "share_sheet_container."
        ));
    }

    @Override
    boolean isFiltered(String identifier,
                       String accessibility,
                       String path,
                       byte[] buffer,
                       StringFilterGroup matchedGroup,
                       FilterContentType contentType,
                       int contentIndex) {

        isShareSheetVisible = true;
        return false;
    }
}