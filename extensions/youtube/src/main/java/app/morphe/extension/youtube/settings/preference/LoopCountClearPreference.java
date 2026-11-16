/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4516
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;
import android.view.WindowManager;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.youtube.patches.LoopCountPatch;

/**
 * Clears the loop count saved for each video of the playlists.
 */
@SuppressWarnings({"unused", "deprecation"})
public class LoopCountClearPreference extends Preference {

    public LoopCountClearPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    public LoopCountClearPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public LoopCountClearPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public LoopCountClearPreference(Context context) {
        super(context);
        init();
    }

    private void init() {
        // The loop counts are saved by their own setting, so this preference saves nothing.
        setPersistent(false);
        updateSummary();
    }

    private void updateSummary() {
        final int saved = LoopCountPatch.getSavedLoopCountsSize();
        // The saved loop counts can be cleared even if loop count is turned off.
        setEnabled(saved > 0);
        setSummary(saved > 0
                ? str("morphe_loop_count_clear_summary", String.valueOf(saved))
                : str("morphe_loop_count_clear_summary_none"));
    }

    @Override
    protected void onClick() {
        try {
            CustomDialog.create(getContext(),
                    str("morphe_loop_count_clear_dialog_title"),
                    str("morphe_loop_count_clear_dialog_message"),
                    null,
                    null,
                    () -> {
                        LoopCountPatch.clearSavedLoopCounts();
                        updateSummary();
                        Utils.showToastShort(str("morphe_loop_count_clear_done"));
                    },
                    () -> { },
                    null,
                    null,
                    true).first.show();
        } catch (WindowManager.BadTokenException ex) {
            Logger.printException(() -> "Could not show the clear loop counts dialog", ex);
        }
    }
}
