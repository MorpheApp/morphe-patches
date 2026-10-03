package app.morphe.extension.youtube.series;

import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;
import android.widget.Toast;

import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;

/** Uses Morphe's existing file picker, exactly like settings and feature-flag exports. */
@SuppressWarnings("deprecation")
public final class BackupPreference extends Preference {
    public BackupPreference(Context context, AttributeSet attributes) {
        super(context, attributes);
        setPersistent(false);
    }

    @Override
    protected void onClick() {
        AbstractPreferenceFragment fragment = AbstractPreferenceFragment.instance.get();
        if (fragment == null || !fragment.isAdded()) return;
        TrackerService service = TrackerService.get(getContext());
        NativeSheet sheet = new NativeSheet(getContext(), "series_tracker_backup_title");
        sheet.message("series_tracker_backup_explanation");
        sheet.action(
                "series_tracker_backup_restore",
                () -> {
                    sheet.dialog.dismiss();
                    fragment.importTextActivity(
                            text ->
                                    service.previewBackup(
                                            text,
                                            (data, count) -> {
                                                if (!fragment.isAdded()
                                                        || fragment.getActivity().isFinishing())
                                                    return;
                                                NativeSheet preview =
                                                        new NativeSheet(
                                                                fragment.getActivity(),
                                                                "series_tracker_backup_restore");
                                                preview.message(
                                                        UiText.format(
                                                                fragment.getActivity(),
                                                                "series_tracker_backup_preview",
                                                                count,
                                                                data.series.size() - count));
                                                preview.action(
                                                        "series_tracker_ui_cancel",
                                                        preview.dialog::dismiss,
                                                        false);
                                                if (count > 0)
                                                    preview.action(
                                                            "series_tracker_backup_restore",
                                                            () -> {
                                                                preview.dialog.dismiss();
                                                                service.restoreBackup(
                                                                        data,
                                                                        added ->
                                                                                toast(
                                                                                        UiText
                                                                                                .format(
                                                                                                        getContext(),
                                                                                                        "series_tracker_backup_restored",
                                                                                                        added)),
                                                                        this::toast);
                                                            },
                                                            true);
                                                preview.show();
                                            },
                                            this::toast));
                },
                false);
        sheet.action(
                "series_tracker_backup_save",
                () -> {
                    sheet.dialog.dismiss();
                    service.exportBackup(
                            text -> {
                                if (!fragment.isAdded() || fragment.getActivity().isFinishing())
                                    return;
                                fragment.exportTextActivity(
                                        AbstractPreferenceFragment.exportFileName("Series_Tracker"),
                                        text,
                                        success ->
                                                toast(
                                                        success
                                                                ? "series_tracker_backup_saved"
                                                                : "series_tracker_error_backup_write"));
                            },
                            this::toast);
                },
                true);
        sheet.show();
    }

    private void toast(String text) {
        Toast.makeText(getContext(), UiText.get(getContext(), text), Toast.LENGTH_LONG).show();
    }
}
