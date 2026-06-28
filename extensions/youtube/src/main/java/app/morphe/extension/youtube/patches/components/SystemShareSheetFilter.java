/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.components;

import static app.morphe.extension.youtube.patches.OpenSystemShareSheetPatch.closeLithoAppShareSheet;

import app.morphe.extension.youtube.patches.components.LithoFilterPatch.BufferAsciiStrings;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.ConversionContext.ContextInterface;

@SuppressWarnings("unused")
public final class SystemShareSheetFilter extends Filter {

    public SystemShareSheetFilter() {
        addPathCallbacks(new StringFilterGroup(
                Settings.OPEN_SYSTEM_SHARE_SHEET,
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
                       BufferAsciiStrings asciiStrings,
                       StringFilterGroup matchedGroup,
                       FilterContentType contentType,
                       int contentIndex) {
        closeLithoAppShareSheet();

        return true;
    }
}