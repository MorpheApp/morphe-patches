/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */

package app.morphe.extension.youtube.patches.components;

import app.morphe.extension.youtube.patches.ChangeShareSheetPatch;
import app.morphe.extension.youtube.settings.Settings;

/**
 * LithoFilter for {@link ChangeShareSheetPatch}.
 */
public final class ChangeShareSheetFilter extends Filter {

    public static volatile boolean isShareSheetVisible;

    public ChangeShareSheetFilter() {
        addPathCallbacks(new StringFilterGroup(
                Settings.CHANGE_SHARE_SHEET,
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