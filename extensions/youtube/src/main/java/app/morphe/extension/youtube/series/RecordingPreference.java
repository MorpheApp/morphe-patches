package app.morphe.extension.youtube.series;

import android.content.Context;
import android.preference.SwitchPreference;
import android.util.AttributeSet;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.Toast;

/** Explicit consent; enabling from a settings import cannot bind consent to another account. */
public final class RecordingPreference extends SwitchPreference {
    public RecordingPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setPersistent(false);
    }

    // Morphe synchronizes SwitchPreference values after any settings write. The
    // consent state is authoritative, including while its dialog is committing.
    @Override
    public boolean isChecked() {
        return RecordingPrivacy.allowsRecording();
    }

    @Override
    protected void onBindView(View view) {
        setChecked(RecordingPrivacy.allowsRecording());
        super.onBindView(view);
        // SwitchPreference's widget listener bypasses onClick(). Route widget taps
        // through the same consent action as row/accessibility clicks.
        int switchId = getContext().getResources().getIdentifier("switch_widget", "id", "android");
        View widget = view.findViewById(switchId);
        if (widget instanceof CompoundButton) {
            CompoundButton toggle = (CompoundButton) widget;
            toggle.setOnCheckedChangeListener(null);
            toggle.setOnClickListener(
                    v -> {
                        toggle.setChecked(RecordingPrivacy.allowsRecording());
                        onClick();
                    });
        }
    }

    @Override
    protected void onClick() {
        if (RecordingPrivacy.allowsRecording()) {
            RecordingPrivacy.disable();
            setChecked(false);
            return;
        }
        requestEnable(getContext(), () -> setChecked(RecordingPrivacy.allowsRecording()));
    }

    static void requestEnable(Context context, Runnable done) {
        if (!RecordingPrivacy.canEnable()) {
            Toast.makeText(
                            context,
                            UiText.get(context, "series_tracker_identity_unavailable"),
                            Toast.LENGTH_LONG)
                    .show();
            return;
        }
        long consentGeneration = RecordingPrivacy.generation();
        NativeSheet.confirm(
                context,
                "series_tracker_record_title",
                "series_tracker_record_consent",
                "series_tracker_enable",
                () -> {
                    boolean enabled = RecordingPrivacy.enable(consentGeneration);
                    if (!enabled)
                        Toast.makeText(
                                        context,
                                        UiText.get(context, "series_tracker_identity_unavailable"),
                                        Toast.LENGTH_LONG)
                                .show();
                    done.run();
                });
    }
}
