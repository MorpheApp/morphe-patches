/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3114
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.series;

import android.content.Context;
import android.preference.SwitchPreference;
import android.util.AttributeSet;
import android.view.View;
import android.widget.CompoundButton;

import app.morphe.extension.shared.Utils;

/** Explicit consent; enabling from a settings import cannot bind consent to another account. */
@SuppressWarnings({"unused", "deprecation"})
public final class SyncPreference extends SwitchPreference {
    public SyncPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setPersistent(false);
    }

    // Morphe synchronizes SwitchPreference values after any settings write. The
    // consent state is authoritative, including while its dialog is committing.
    @Override
    public boolean isChecked() {
        return RecordingPrivacy.allowsSync();
    }

    @Override
    protected void onBindView(View view) {
        setChecked(RecordingPrivacy.allowsSync());
        super.onBindView(view);
        // SwitchPreference's widget listener bypasses onClick(). Route widget taps
        // through the same consent action as row/accessibility clicks.
        View widget = view.findViewById(android.R.id.switch_widget);
        if (widget instanceof CompoundButton toggle) {
            toggle.setOnCheckedChangeListener(null);
            toggle.setOnClickListener(v -> {
                toggle.setChecked(RecordingPrivacy.allowsSync());
                onClick();
            });
        }
    }

    @Override
    protected void onClick() {
        if (RecordingPrivacy.allowsSync()) {
            RecordingPrivacy.disableSync();
            setChecked(false);
            return;
        }
        requestEnable(getContext(), () -> setChecked(RecordingPrivacy.allowsSync()));
    }

    static void requestEnable(Context context, Runnable done) {
        if (!RecordingPrivacy.canEnable()) {
            Utils.showToastLong(UiText.get("morphe_series_tracker_identity_unavailable"));
            return;
        }
        long consentGeneration = RecordingPrivacy.generation();
        NativeSheet.confirm(
                context,
                "morphe_series_tracker_youtube_progress_title",
                "morphe_series_tracker_sync_consent",
                "morphe_series_tracker_enable_short",
                () -> {
                    boolean enabled = RecordingPrivacy.enableSync(consentGeneration);
                    if (!enabled)
                        Utils.showToastLong(UiText.get("morphe_series_tracker_identity_unavailable"));
                    if (enabled) TrackerService.get(context).syncYouTube(true, () -> {});
                    done.run();
                });
    }
}
