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
import android.preference.Preference;
import android.util.AttributeSet;
import android.util.Pair;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
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
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;

/**
 * Lists the custom video effects to change or delete them, and adds new effects.
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
            Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                    context,
                    getTitle() != null ? getTitle().toString() : "",
                    null,
                    null,
                    str("morphe_settings_close"),
                    () -> {},
                    null,
                    str("morphe_video_effects_custom_add"),
                    () -> showEditDialog(null),
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

            if (isDefaultEffectMissing()) {
                Button restore = CustomDialog.createButton(context, dialog,
                        str("morphe_video_effects_custom_restore"),
                        this::restoreDefaultEffects, false, true);
                LinearLayout.LayoutParams restoreParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Dim.dp36);
                restoreParams.setMargins(0, Dim.dp8, 0, 0);
                mainLayout.addView(restore, mainLayout.getChildCount() - 1, restoreParams);
            }

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
                showEditDialog(effect);
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
            delete.setContentDescription(str("morphe_settings_delete"));
            delete.setPadding(Dim.dp8, Dim.dp8, Dim.dp8, Dim.dp8);
            delete.setOnClickListener(v -> confirmDelete(context, effect.name,
                    () -> addEffectRows(context, dialog, rows)));
            row.addView(delete, new LinearLayout.LayoutParams(Dim.dp40, Dim.dp40));

            rows.addView(row);
        }
    }

    /**
     * @return If a default effect was deleted. An effect with the name of a default effect
     *         counts as that effect, even if the code was changed.
     */
    private static boolean isDefaultEffectMissing() {
        for (CustomEffect effect : VideoEffectEditor.getDefaultEffects()) {
            if (VideoEffectsPatch.findCustomEffect(effect.name) == null) return true;
        }
        return false;
    }

    /**
     * Adds the deleted default effects. Each one goes after the default effect before it,
     * so the default effects keep their order.
     */
    private void restoreDefaultEffects() {
        try {
            List<CustomEffect> effects = new ArrayList<>(VideoEffectsPatch.getCustomEffects());
            int insertIndex = 0;
            for (CustomEffect defaultEffect : VideoEffectEditor.getDefaultEffects()) {
                int index = VideoEffectEditor.indexOf(effects, defaultEffect.name);
                if (index >= 0) {
                    insertIndex = index + 1;
                } else {
                    effects.add(insertIndex++, defaultEffect);
                }
            }
            saveEffects(effects);
            showEffectsDialog();
        } catch (Exception ex) {
            Logger.printException(() -> "restoreDefaultEffects failure", ex);
        }
    }

    private void saveEffects(List<CustomEffect> effects) {
        VideoEffectEditor.saveEffects(effects);
        updateEffectListPreference();
    }

    private void confirmDelete(Context context, String name, Runnable onDeleted) {
        CustomDialog.create(
                context,
                str("morphe_video_effects_custom_delete_title"),
                str("morphe_video_effects_custom_delete_message", name),
                null,
                str("morphe_settings_delete"),
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
        if (VideoEffectEditor.isSelected(name)) {
            SharedYouTubeSettings.VIDEO_EFFECT.resetToDefault();
        }

        List<CustomEffect> effects = new ArrayList<>(VideoEffectsPatch.getCustomEffects());
        effects.removeIf(effect -> effect.name.equals(name));
        saveEffects(effects);
    }

    /**
     * @param effect The effect to change, or null to add a new effect.
     */
    private void showEditDialog(@Nullable CustomEffect effect) {
        VideoEffectEditor.show(getContext(), effect, () -> {
            updateEffectListPreference();
            showEffectsDialog();
        });
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
