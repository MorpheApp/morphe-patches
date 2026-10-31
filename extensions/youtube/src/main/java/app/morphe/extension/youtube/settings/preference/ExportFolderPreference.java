/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4211
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.net.Uri;
import android.preference.Preference;
import android.util.AttributeSet;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.youtube.patches.OfflineVideoExporter;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Explains how saved videos are exported, and picks the folder they are exported to.
 */
@SuppressWarnings({"unused", "deprecation"})
public final class ExportFolderPreference extends Preference {

    private static final int FOLDER_PERMISSIONS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;

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
            ContentResolver resolver = getContext().getContentResolver();

            // Only one folder is used, so the access to the previous folder is no longer needed.
            String previous = Settings.EXPORT_DOWNLOAD_FOLDER.get();
            for (UriPermission permission : resolver.getPersistedUriPermissions()) {
                if (permission.getUri().toString().equals(previous)) {
                    resolver.releasePersistableUriPermission(permission.getUri(), FOLDER_PERMISSIONS);
                }
            }

            if (folder == null) {
                Settings.EXPORT_DOWNLOAD_FOLDER.resetToDefault();
                Utils.showSnackbarShort(str("morphe_export_download_folder_reset"));
            } else {
                resolver.takePersistableUriPermission(folder, FOLDER_PERMISSIONS);
                Settings.EXPORT_DOWNLOAD_FOLDER.save(folder.toString());
                Utils.showSnackbarShort(str("morphe_export_download_folder_changed"));
            }

            updateSummary();
        } catch (Exception ex) {
            Logger.printException(() -> "setFolder failure", ex);
        }
    }
}
