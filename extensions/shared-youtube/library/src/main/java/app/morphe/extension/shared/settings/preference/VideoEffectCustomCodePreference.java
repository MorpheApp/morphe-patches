/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5137
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.widget.EditText;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.VideoEffectsPatch;
import app.morphe.extension.shared.ui.CustomDialog;

/**
 * Code of the custom video effect. The code is checked when it is saved, and the compiler
 * errors are shown so they can be given back to the AI that wrote the code.
 */
@SuppressWarnings({"unused", "deprecation"})
public class VideoEffectCustomCodePreference extends ResettableEditTextPreference {

    public VideoEffectCustomCodePreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    public VideoEffectCustomCodePreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public VideoEffectCustomCodePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public VideoEffectCustomCodePreference(Context context) {
        super(context);
        init();
    }

    private void init() {
        EditText editText = getEditText();
        editText.setTypeface(Typeface.MONOSPACE);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        // Code, so no keyboard suggestions or autocorrect.
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);

        setOnPreferenceChangeListener((preference, newValue) -> {
            try {
                String error = VideoEffectsPatch.findCustomEffectError(newValue.toString());
                if (error != null) {
                    // After the edit dialog closes.
                    Utils.runOnMainThread(() -> showError(error));
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Could not check the custom video effect", ex);
            }
            // Saved even with an error, so the code is not lost and can be fixed.
            // The video is drawn without the custom effect until then.
            return true;
        });
    }

    private void showError(String error) {
        try {
            CustomDialog.create(
                    getContext(),
                    str("morphe_video_effect_custom_code_error_title"),
                    str("morphe_video_effect_custom_code_error_message", error),
                    null,
                    null,
                    () -> {},
                    null,
                    str("morphe_video_effect_custom_code_copy_error"),
                    () -> Utils.setClipboard(error),
                    true
            ).first.show();
        } catch (Exception ex) {
            Logger.printException(() -> "showError failure", ex);
        }
    }
}
