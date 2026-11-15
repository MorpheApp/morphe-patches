package app.morphe.extension.reddit.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.app.AlertDialog;
import android.content.Context;
import android.preference.Preference;
import android.widget.Toast;

import app.morphe.extension.reddit.patches.PermanentNsfwPatch;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;

/** Confirmation is necessary because enabling the latch has no in-app undo. */
@SuppressWarnings("deprecation")
public final class PermanentNsfwPreference extends Preference {
    public PermanentNsfwPreference(Context context) {
        super(context);
        setPersistent(false);
        setKey("morphe_permanent_nsfw_lock");
        setTitle(str("morphe_permanent_nsfw_title"));
        refresh();
        setOnPreferenceClickListener(preference -> {
            new AlertDialog.Builder(context)
                    .setTitle(str("morphe_permanent_nsfw_confirm_title"))
                    .setMessage(str("morphe_permanent_nsfw_confirm_message"))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(str("morphe_permanent_nsfw_enable"), (dialog, which) -> {
                        if (!PermanentNsfwPatch.getLock().enable()) {
                            Toast.makeText(context, str("morphe_permanent_nsfw_save_failed"),
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        refresh();
                        AbstractPreferenceFragment.showRestartDialog(context);
                    })
                    .show();
            return true;
        });
    }

    private void refresh() {
        boolean locked = PermanentNsfwPatch.getLock().isEnabled();
        setEnabled(!locked);
        setSummary(str(locked ? "morphe_permanent_nsfw_locked" : "morphe_permanent_nsfw_summary"));
    }
}
