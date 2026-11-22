/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5137
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.Pair;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.VideoEffectsPatch;
import app.morphe.extension.shared.patches.VideoEffectsPatch.CustomEffect;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;

/**
 * Adds and changes custom video effects. The code of an effect is checked when it is saved,
 * and the compiler errors are shown so they can be given back to the AI that wrote it.
 */
public final class VideoEffectEditor {

    private VideoEffectEditor() {
    }

    /**
     * Shows the name and code of an effect to change them.
     *
     * @param effect   The effect to change, or null to add a new effect.
     * @param onClosed Called after the effect is saved or the editor is closed with its button.
     */
    public static void show(Context context, @Nullable CustomEffect effect, Runnable onClosed) {
        if (effect == null) {
            showEditDialog(context, null, "", VideoEffectsPatch.CUSTOM_EFFECT_TEMPLATE, onClosed);
        } else {
            showEditDialog(context, effect.name, effect.name, effect.code, onClosed);
        }
    }

    static List<CustomEffect> getDefaultEffects() {
        return VideoEffectsPatch.decodeCustomEffects(SharedYouTubeSettings.VIDEO_EFFECTS_CUSTOM.defaultValue);
    }

    static int indexOf(List<CustomEffect> effects, @Nullable String name) {
        for (int i = 0, size = effects.size(); i < size; i++) {
            if (effects.get(i).name.equals(name)) return i;
        }
        return -1;
    }

    static boolean isSelected(@Nullable String name) {
        return SharedYouTubeSettings.VIDEO_EFFECT.get().equals(name);
    }

    /**
     * Saves the effects in the order of the effect lists.
     */
    static void saveEffects(List<CustomEffect> effects) {
        if (isDefault(effects)) {
            // Not in exported settings.
            SharedYouTubeSettings.VIDEO_EFFECTS_CUSTOM.resetToDefault();
        } else {
            SharedYouTubeSettings.VIDEO_EFFECTS_CUSTOM.save(VideoEffectsPatch.encodeCustomEffects(effects));
        }
    }

    private static boolean isDefault(List<CustomEffect> effects) {
        List<CustomEffect> defaults = getDefaultEffects();
        if (effects.size() != defaults.size()) return false;
        for (int i = 0, size = effects.size(); i < size; i++) {
            CustomEffect effect = effects.get(i);
            CustomEffect defaultEffect = defaults.get(i);
            if (!effect.name.equals(defaultEffect.name) || !effect.code.equals(defaultEffect.code)) return false;
        }
        return true;
    }

    /**
     * @param originalName The name of the effect that is changed, or null for a new effect.
     */
    @SuppressWarnings("deprecation")
    private static void showEditDialog(Context context, @Nullable String originalName,
                                       String name, String code, Runnable onClosed) {
        try {
            EditText nameText = CustomDialog.createEditText(context);
            nameText.setSingleLine(true);
            nameText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            nameText.setHint(str("morphe_video_effects_custom_name_hint"));
            nameText.setText(name);

            EditText codeText = new EditText(context);
            codeText.setTypeface(Typeface.MONOSPACE);
            codeText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            codeText.setGravity(Gravity.TOP | Gravity.START);
            // Code, so no keyboard suggestions or autocorrect.
            codeText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            codeText.setText(code);

            Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                    context,
                    str(originalName == null
                            ? "morphe_video_effects_custom_add_title"
                            : "morphe_video_effects_custom_edit_title"),
                    null,
                    codeText,
                    str("morphe_settings_save"),
                    () -> saveEffect(context, originalName, nameText.getText().toString(),
                            codeText.getText().toString(), false, onClosed),
                    onClosed,
                    str("morphe_video_effects_custom_copy_code"),
                    () -> Utils.setClipboard(codeText.getText().toString()),
                    false
            );
            Dialog dialog = dialogPair.first;
            // A tap next to the dialog would lose the code.
            dialog.setCanceledOnTouchOutside(false);

            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nameParams.setMargins(0, 0, 0, Dim.dp8);
            // After the title.
            dialogPair.second.addView(nameText, 1, nameParams);

            dialog.show();

            Window window = dialog.getWindow();
            if (window != null) {
                WindowManager.LayoutParams params = window.getAttributes();
                params.height = WindowManager.LayoutParams.MATCH_PARENT;
                window.setAttributes(params);
                // Keep the buttons above the keyboard.
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "showEditDialog failure", ex);
        }
    }

    /**
     * @param ignoreError If the code is saved even if it has an error.
     */
    private static void saveEffect(Context context, @Nullable String originalName, String name,
                                   String code, boolean ignoreError, Runnable onClosed) {
        try {
            name = name.trim();
            code = VideoEffectsPatch.removeCustomEffectInstructions(code);

            String nameError = null;
            if (name.isEmpty()) {
                nameError = "morphe_video_effects_custom_name_empty";
            } else if (!name.equals(originalName) && VideoEffectsPatch.findCustomEffect(name) != null) {
                nameError = "morphe_video_effects_custom_name_exists";
            }
            if (nameError != null) {
                Utils.showToastShort(str(nameError));
                showEditDialog(context, originalName, name, code, onClosed);
                return;
            }

            if (!ignoreError) {
                String error = findError(code);
                if (error != null) {
                    if (VideoEffectsPatch.isCustomEffectCodeCutOff(code)) {
                        showError(context, originalName, name, code, str("morphe_video_effects_custom_cut_off_message",
                                VideoEffectsPatch.PASTED_TEXT_LENGTH_LIMIT), null, onClosed);
                    } else {
                        showError(context, originalName, name, code,
                                str("morphe_video_effects_custom_error_message", error), error, onClosed);
                    }
                    return;
                }
            }

            List<CustomEffect> effects = new ArrayList<>(VideoEffectsPatch.getCustomEffects());
            CustomEffect effect = new CustomEffect(name, code);
            int index = indexOf(effects, originalName);
            if (index >= 0) {
                effects.set(index, effect);
            } else {
                effects.add(effect);
            }

            // The name first, so the selected effect is never missing.
            if (isSelected(originalName)) {
                SharedYouTubeSettings.VIDEO_EFFECT.save(name);
            }
            saveEffects(effects);

            onClosed.run();
        } catch (Exception ex) {
            Logger.printException(() -> "saveEffect failure", ex);
        }
    }

    @Nullable
    private static String findError(String code) {
        try {
            return VideoEffectsPatch.findCustomEffectError(code);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not check the custom video effect", ex);
            return ex.toString();
        }
    }

    /**
     * @param error The compiler errors to copy, or null for no copy button.
     */
    private static void showError(Context context, @Nullable String originalName, String name,
                                  String code, String message, @Nullable String error, Runnable onClosed) {
        Dialog dialog = CustomDialog.create(
                context,
                str("morphe_video_effects_custom_error_title"),
                message,
                null,
                str("morphe_video_effects_custom_save_anyway"),
                () -> saveEffect(context, originalName, name, code, true, onClosed),
                // Back to the code.
                () -> showEditDialog(context, originalName, name, code, onClosed),
                error == null ? null : str("morphe_video_effects_custom_copy_error"),
                error == null ? null : () -> Utils.setClipboard(error),
                false
        ).first;
        dialog.setCancelable(false);
        dialog.show();
    }
}
