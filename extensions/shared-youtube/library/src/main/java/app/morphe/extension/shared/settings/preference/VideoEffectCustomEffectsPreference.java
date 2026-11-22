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
import android.preference.Preference;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.Pair;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.VideoEffectsPatch;
import app.morphe.extension.shared.patches.VideoEffectsPatch.CustomEffect;
import app.morphe.extension.shared.patches.VideoEffectsPatch.VideoEffect;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;

/**
 * Adds, changes and deletes the custom video effects. The code of an effect is checked when it
 * is saved, and the compiler errors are shown so they can be given back to the AI that wrote it.
 */
@SuppressWarnings({"unused", "deprecation"})
public class VideoEffectCustomEffectsPreference extends Preference {

    private static final int DRAWABLE_DELETE = ResourceUtils.getIdentifierOrThrow(
            ResourceType.DRAWABLE, "morphe_settings_search_remove");
    private static final int DRAWABLE_DELETE_BOLD = ResourceUtils.getIdentifierOrThrow(
            ResourceType.DRAWABLE, "morphe_settings_search_remove_bold");

    public VideoEffectCustomEffectsPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public VideoEffectCustomEffectsPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public VideoEffectCustomEffectsPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public VideoEffectCustomEffectsPreference(Context context) {
        super(context);
    }

    @Override
    protected void onClick() {
        showEffectsDialog();
    }

    private void showEffectsDialog() {
        try {
            Context context = getContext();
            List<CustomEffect> missingDefaults = findMissingDefaultEffects();
            Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                    context,
                    getTitle() != null ? getTitle().toString() : "",
                    null,
                    null,
                    str("morphe_video_effects_custom_add"),
                    () -> showEditDialog(null, "", VideoEffectsPatch.CUSTOM_EFFECT_TEMPLATE),
                    () -> {},
                    missingDefaults.isEmpty() ? null : str("morphe_video_effects_custom_restore"),
                    () -> restoreDefaultEffects(missingDefaults),
                    true
            );
            Dialog dialog = dialogPair.first;
            LinearLayout mainLayout = dialogPair.second;

            LinearLayout rows = new LinearLayout(context);
            rows.setOrientation(LinearLayout.VERTICAL);
            ScrollView scrollView = new ScrollView(context);
            scrollView.addView(rows);
            addEffectRows(context, dialog, rows);

            // Before the dialog buttons.
            mainLayout.addView(scrollView, mainLayout.getChildCount() - 1,
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));

            dialog.show();
        } catch (Exception ex) {
            Logger.printException(() -> "showEffectsDialog failure", ex);
        }
    }

    private void addEffectRows(Context context, Dialog dialog, LinearLayout rows) {
        rows.removeAllViews();

        List<CustomEffect> effects = VideoEffectsPatch.getCustomEffects();
        if (effects.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText(str("morphe_video_effects_custom_empty"));
            empty.setTextSize(16);
            empty.setTextColor(ThemeUtils.getDialogTextColor());
            rows.addView(empty);
            return;
        }

        for (CustomEffect effect : effects) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setOnClickListener(v -> {
                dialog.dismiss();
                showEditDialog(effect.name, effect.name, effect.code);
            });

            TextView name = new TextView(context);
            name.setText(effect.name);
            ThemeUtils.setThemeTextStyle(name, android.R.attr.textAppearanceListItem,
                    android.R.attr.textColorPrimary);
            name.setTextColor(ThemeUtils.getDialogTextColor());
            name.setPadding(0, Dim.dp12, Dim.dp8, Dim.dp12);
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

            ImageView delete = new ImageView(context);
            delete.setImageResource(Utils.appIsUsingBoldIcons() ? DRAWABLE_DELETE_BOLD : DRAWABLE_DELETE);
            delete.setColorFilter(ThemeUtils.getDialogTextColor());
            delete.setContentDescription(str("morphe_video_effects_custom_delete"));
            delete.setPadding(Dim.dp8, Dim.dp8, Dim.dp8, Dim.dp8);
            delete.setOnClickListener(v -> confirmDelete(context, effect.name,
                    () -> addEffectRows(context, dialog, rows)));
            row.addView(delete, new LinearLayout.LayoutParams(Dim.dp40, Dim.dp40));

            rows.addView(row);
        }
    }

    /**
     * @return The default effects that were deleted. An effect with the name of a default effect
     *         counts as that effect, even if the code was changed.
     */
    private static List<CustomEffect> findMissingDefaultEffects() {
        List<CustomEffect> missing = new ArrayList<>();
        for (CustomEffect effect : VideoEffectsPatch.decodeCustomEffects(
                SharedYouTubeSettings.VIDEO_EFFECTS_CUSTOM.defaultValue)) {
            if (VideoEffectsPatch.findCustomEffect(effect.name) == null) missing.add(effect);
        }
        return missing;
    }

    private void restoreDefaultEffects(List<CustomEffect> missingDefaults) {
        try {
            List<CustomEffect> effects = new ArrayList<>(VideoEffectsPatch.getCustomEffects());
            effects.addAll(missingDefaults);
            saveEffects(effects);
            showEffectsDialog();
        } catch (Exception ex) {
            Logger.printException(() -> "restoreDefaultEffects failure", ex);
        }
    }

    /**
     * Saves the effects sorted by name, which is their order in the effect lists.
     */
    private void saveEffects(List<CustomEffect> effects) {
        effects.sort((first, second) -> first.name.compareToIgnoreCase(second.name));
        if (isDefault(effects)) {
            // Not in exported settings.
            SharedYouTubeSettings.VIDEO_EFFECTS_CUSTOM.resetToDefault();
        } else {
            SharedYouTubeSettings.VIDEO_EFFECTS_CUSTOM.save(VideoEffectsPatch.encodeCustomEffects(effects));
        }
        updateEffectListPreference();
    }

    private static boolean isDefault(List<CustomEffect> effects) {
        List<CustomEffect> defaults = VideoEffectsPatch.decodeCustomEffects(
                SharedYouTubeSettings.VIDEO_EFFECTS_CUSTOM.defaultValue);
        if (effects.size() != defaults.size()) return false;
        for (int i = 0, size = effects.size(); i < size; i++) {
            CustomEffect effect = effects.get(i);
            CustomEffect defaultEffect = defaults.get(i);
            if (!effect.name.equals(defaultEffect.name) || !effect.code.equals(defaultEffect.code)) return false;
        }
        return true;
    }

    private void confirmDelete(Context context, String name, Runnable onDeleted) {
        CustomDialog.create(
                context,
                str("morphe_video_effects_custom_delete_title"),
                str("morphe_video_effects_custom_delete_message", name),
                null,
                str("morphe_video_effects_custom_delete"),
                () -> {
                    try {
                        deleteEffect(name);
                        onDeleted.run();
                    } catch (Exception ex) {
                        Logger.printException(() -> "Could not delete custom video effect", ex);
                    }
                },
                () -> {},
                null,
                null,
                false
        ).first.show();
    }

    private void deleteEffect(String name) {
        if (isSelected(name)) {
            SharedYouTubeSettings.VIDEO_EFFECT.save(VideoEffect.NONE);
            SharedYouTubeSettings.VIDEO_EFFECT_CUSTOM_NAME.resetToDefault();
        }

        List<CustomEffect> effects = new ArrayList<>(VideoEffectsPatch.getCustomEffects());
        effects.removeIf(effect -> effect.name.equals(name));
        saveEffects(effects);
    }

    private static boolean isSelected(String name) {
        return SharedYouTubeSettings.VIDEO_EFFECT.get() == VideoEffect.CUSTOM
                && SharedYouTubeSettings.VIDEO_EFFECT_CUSTOM_NAME.get().equals(name);
    }

    /**
     * @param originalName The name of the effect that is changed, or null for a new effect.
     */
    private void showEditDialog(@Nullable String originalName, String name, String code) {
        try {
            Context context = getContext();

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
                    () -> saveEffect(originalName, nameText.getText().toString(),
                            codeText.getText().toString(), false),
                    this::showEffectsDialog,
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
    private void saveEffect(@Nullable String originalName, String name, String code, boolean ignoreError) {
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
                showEditDialog(originalName, name, code);
                return;
            }

            if (!ignoreError) {
                String error = findError(code);
                if (error != null) {
                    showError(originalName, name, code, error);
                    return;
                }
            }

            List<CustomEffect> effects = new ArrayList<>(VideoEffectsPatch.getCustomEffects());
            CustomEffect effect = new CustomEffect(name, code);
            int index = -1;
            for (int i = 0, size = effects.size(); i < size; i++) {
                if (effects.get(i).name.equals(originalName)) index = i;
            }
            if (index >= 0) {
                effects.set(index, effect);
            } else {
                effects.add(effect);
            }

            // The name first, so the selected effect is never missing.
            if (originalName != null && isSelected(originalName)) {
                SharedYouTubeSettings.VIDEO_EFFECT_CUSTOM_NAME.save(name);
            }
            saveEffects(effects);

            showEffectsDialog();
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

    private void showError(@Nullable String originalName, String name, String code, String error) {
        Dialog dialog = CustomDialog.create(
                getContext(),
                str("morphe_video_effects_custom_error_title"),
                str("morphe_video_effects_custom_error_message", error),
                null,
                str("morphe_video_effects_custom_save_anyway"),
                () -> saveEffect(originalName, name, code, true),
                // Back to the code.
                () -> showEditDialog(originalName, name, code),
                str("morphe_video_effects_custom_copy_error"),
                () -> Utils.setClipboard(error),
                false
        ).first;
        dialog.setCancelable(false);
        dialog.show();
    }

    /**
     * Shows the changed custom effects in the effect list.
     */
    private void updateEffectListPreference() {
        if (findPreferenceInHierarchy(SharedYouTubeSettings.VIDEO_EFFECT.key)
                instanceof SettingPreference preference) {
            preference.updateFromSetting();
        }
    }
}
