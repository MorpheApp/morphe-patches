/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3114
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.series;

import android.app.Activity;
import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;

/** Saves and restores the series library with the same file picker as the settings export. */
@SuppressWarnings({"unused", "deprecation"})
public final class BackupPreference extends Preference {
    public BackupPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setPersistent(false);
    }

    @Override
    protected void onClick() {
        AbstractPreferenceFragment fragment = AbstractPreferenceFragment.instance.get();
        if (fragment == null || !fragment.isAdded()) return;
        TrackerService service = TrackerService.get(getContext());
        NativeSheet sheet = new NativeSheet(getContext(), "morphe_series_tracker_backup_title");
        sheet.message("morphe_series_tracker_backup_explanation");
        sheet.action("morphe_series_tracker_backup_restore", () -> {
            sheet.dialog.dismiss();
            fragment.importTextActivity(text -> service.previewBackup(text,
                    (data, count) -> {
                        if (isShowing(fragment)) confirmRestore(fragment.getActivity(), service, data, count);
                    },
                    BackupPreference::toast));
        }, false);
        sheet.action("morphe_series_tracker_backup_save", () -> {
            sheet.dialog.dismiss();
            service.exportBackup(text -> {
                if (!isShowing(fragment)) return;
                fragment.exportTextActivity(
                        AbstractPreferenceFragment.exportFileName("Series_Tracker"),
                        text,
                        success -> toast(success
                                ? "morphe_series_tracker_backup_saved"
                                : "morphe_series_tracker_error_backup_write"));
            }, BackupPreference::toast);
        }, true);
        sheet.show();
    }

    private static void confirmRestore(
            Activity activity, TrackerService service, LibraryBackup.Data data, int count) {
        NativeSheet sheet = new NativeSheet(activity, "morphe_series_tracker_backup_restore");
        sheet.message(UiText.format("morphe_series_tracker_backup_preview",
                count, data.series().size() - count));
        sheet.action("morphe_series_tracker_ui_cancel", sheet.dialog::dismiss, false);
        if (count > 0) {
            sheet.action("morphe_series_tracker_backup_restore", () -> {
                sheet.dialog.dismiss();
                service.restoreBackup(data,
                        added -> Utils.showToastLong(
                                UiText.format("morphe_series_tracker_backup_restored", added)),
                        BackupPreference::toast);
            }, true);
        }
        sheet.show();
    }

    private static boolean isShowing(AbstractPreferenceFragment fragment) {
        return fragment.isAdded() && !fragment.getActivity().isFinishing();
    }

    private static void toast(String key) {
        Utils.showToastLong(UiText.get(key));
    }
}
