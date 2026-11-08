/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;

@SuppressWarnings("unused")
public class RemoveViewerDiscretionDialogPatch {

    /**
     * Injection point.
     */
    public static boolean hideViewDiscretionDialog(boolean originalValue) {
        return Logger.traceOverride("RemoveViewerDiscretionDialogPatch.hideViewDiscretionDialog", originalValue,
                SharedYouTubeSettings.REMOVE_VIEWER_DISCRETION_DIALOG.get() || originalValue);
    }
}
