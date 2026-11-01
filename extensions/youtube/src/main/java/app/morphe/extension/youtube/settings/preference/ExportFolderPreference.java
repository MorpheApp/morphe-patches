/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4211
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.net.Uri;
import android.preference.Preference;
import android.util.AttributeSet;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.youtube.patches.OfflineVideoExporter;

/**
 * Explains how saved videos are exported, and picks the folder they are exported to.
 */
@SuppressWarnings({"unused", "deprecation"})
public final class ExportFolderPreference extends Preference {

    public ExportFolderPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setPersistent(false);
        updateSummary();
    }

    private void updateSummary() {
        setSummary(str("morphe_export_download_folder_summary", OfflineVideoExporter.getFolderName()));
    }

    @Override
    protected void onClick() {
        AbstractPreferenceFragment fragment = AbstractPreferenceFragment.instance.get();
        if (fragment == null || !fragment.isAdded()) {
            return;
        }

        CustomDialog.create(
                getContext(),
                str("morphe_export_download_folder_dialog_title"),
                OfflineVideoExporter.getFolderName(),
                null,
                str("morphe_export_download_folder_change"),
                () -> fragment.pickFolderActivity(this::setFolder),
                () -> {},
                str("morphe_settings_reset"),
                () -> setFolder(null),
                true
        ).first.show();
    }

    /**
     * @param folder The tree uri of the folder, or null to use the default folder.
     */
    private void setFolder(@Nullable Uri folder) {
        try {
            OfflineVideoExporter.setFolder(folder);
            Utils.showSnackbarShort(str(folder == null
                    ? "morphe_export_download_folder_reset"
                    : "morphe_export_download_folder_changed"));
            updateSummary();
        } catch (Exception ex) {
            Logger.printException(() -> "setFolder failure", ex);
        }
    }
}
