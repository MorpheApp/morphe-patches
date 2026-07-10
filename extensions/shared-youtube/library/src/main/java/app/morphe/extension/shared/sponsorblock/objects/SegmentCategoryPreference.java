/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */
package app.morphe.extension.shared.sponsorblock.objects;

import static app.morphe.extension.shared.StringRef.str;
import static app.morphe.extension.shared.sponsorblock.SponsorBlockHelpers.migrateOldColorString;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.AttributeSet;
import android.view.View;
import android.widget.RadioButton;
import android.widget.RadioGroup;

import androidx.annotation.Nullable;

import java.util.Objects;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.preference.ColorPickerPreference;
import app.morphe.extension.shared.sponsorblock.SponsorBlockApi;
import app.morphe.extension.shared.ui.ColorDot;
import app.morphe.extension.shared.ui.Dim;

/**
 * Per-category SponsorBlock preference: a color picker whose dialog also exposes the category's
 * behavior selection. The available behavior set is supplied by
 * {@link SponsorBlockApi.Configuration#availableBehaviors(SegmentCategory)}.
 */
@SuppressWarnings({"unused", "deprecation"})
public class SegmentCategoryPreference extends ColorPickerPreference {
    /** Resolved on construction (programmatic) or in {@link #onAttachedToHierarchy} (XML). */
    @Nullable
    public SegmentCategory category;

    /**
     * View displaying a colored dot in the widget area.
     */
    private View widgetColorDot;

    // Fields to store dialog state for the OK button handler.
    private int selectedDialogEntryIndex;
    private CategoryBehaviour[] dialogBehaviors;

    // Re-evaluates the enabled / dim state when the master SB toggle (or any setting that
    // could change behaviorSetting().isAvailable()) is flipped from elsewhere in the UI.
    private final SharedPreferences.OnSharedPreferenceChangeListener prefChangeListener =
            (prefs, changedKey) -> Utils.runOnMainThread(this::updateUI);

    public SegmentCategoryPreference(Context context, SegmentCategory category) {
        super(context);
        bindCategory(Objects.requireNonNull(category));
    }

    public SegmentCategoryPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        applyCommonStyling();
    }

    public SegmentCategoryPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        applyCommonStyling();
    }

    @Override
    protected void onAttachedToHierarchy(PreferenceManager preferenceManager) {
        super.onAttachedToHierarchy(preferenceManager);
        if (category == null) {
            String key = getKey();
            SegmentCategory resolved = byColorSettingKey(key);
            if (resolved == null) {
                throw new IllegalStateException(
                        "SegmentCategoryPreference: no SegmentCategory matches key: " + key);
            }
            bindCategory(resolved);
        }
        Setting.preferences.preferences.registerOnSharedPreferenceChangeListener(prefChangeListener);
    }

    @Override
    protected void onPrepareForRemoval() {
        super.onPrepareForRemoval();
        Setting.preferences.preferences.unregisterOnSharedPreferenceChangeListener(prefChangeListener);
    }

    private void bindCategory(SegmentCategory category) {
        this.category = category;
        setKey(category.colorSetting().key);
        setTitle(category.title.toString());
        setSummary(category.description.toString());
        applyCommonStyling();
        // Sync initial color from category.
        setText(category.colorSetting().get());
        updateUI();
    }

    private void applyCommonStyling() {
        setOpacitySliderEnabled(true);
        setWidgetLayoutResource(LAYOUT_MORPHE_COLOR_DOT_WIDGET);
    }

    @Override
    protected boolean cancelDialogOnTouchOutside() {
        return true;
    }

    @Override
    public final void setText(String colorString) {
        try {
            // Migrate old data imported in the settings UI. This migration is needed here because
            // pasting into the settings immediately syncs the data with the preferences.
            colorString = migrateOldColorString(colorString, SegmentCategory.CATEGORY_DEFAULT_OPACITY);

            if (category == null) {
                return;
            }

            super.setText(colorString);
            category.setColorWithOpacity(colorString);
            updateUI();

            if (colorChangeListener != null) {
                colorChangeListener.onColorChanged(getKey(), category.getColorWithOpacity());
            }
        } catch (IllegalArgumentException ex) {
            Utils.showToastShort(str("morphe_settings_color_invalid"));
            if (category != null) {
                setText(category.colorSetting().defaultValue);
            }
        } catch (Exception ex) {
            String colorStringFinal = colorString;
            Logger.printException(() -> "setText failure: " + colorStringFinal, ex);
        }
    }

    @Nullable
    @Override
    protected View createExtraDialogContentView(Context context) {
        if (category == null) return null;
        dialogBehaviors = SponsorBlockApi.config().availableBehaviors(category);

        String currentBehavior = category.behaviorSetting().get();
        selectedDialogEntryIndex = -1;
        for (int i = 0; i < dialogBehaviors.length; i++) {
            if (dialogBehaviors[i].morpheKeyValue.equals(currentBehavior)) {
                selectedDialogEntryIndex = i;
                break;
            }
        }

        RadioGroup radioGroup = new RadioGroup(context);
        radioGroup.setOrientation(RadioGroup.VERTICAL);

        // Force explicit foreground color: dialog children do not always inherit the host
        // settings theme's text color, leaving labels unreadable on dark themes.
        final int foregroundColor = Utils.getAppForegroundColor();
        for (int i = 0; i < dialogBehaviors.length; i++) {
            RadioButton radioButton = new RadioButton(context);
            radioButton.setText(dialogBehaviors[i].description.toString());
            radioButton.setTextColor(foregroundColor);
            radioButton.setId(i);
            radioButton.setChecked(i == selectedDialogEntryIndex);
            radioGroup.addView(radioButton);
        }

        radioGroup.setOnCheckedChangeListener((group, checkedId) -> selectedDialogEntryIndex = checkedId);
        radioGroup.setPadding(Dim.dp10, 0, Dim.dp10, Dim.dp10);
        return radioGroup;
    }

    @Override
    protected void onDialogOkClicked() {
        if (category != null
                && selectedDialogEntryIndex >= 0
                && dialogBehaviors != null
                && selectedDialogEntryIndex < dialogBehaviors.length) {
            category.setBehaviour(dialogBehaviors[selectedDialogEntryIndex]);
            SegmentCategory.updateEnabledCategories();
        }
    }

    @Override
    protected void onDialogNeutralClicked() {
        try {
            if (category == null) return;
            final int defaultColor = category.getDefaultColorWithOpacity();
            dialogColorPickerView.setColor(defaultColor);
        } catch (Exception ex) {
            Logger.printException(() -> "Reset button failure", ex);
        }
    }

    public void updateUI() {
        try {
            if (category == null) return;
            setEnabled(category.behaviorSetting().isAvailable());

            updateWidgetColorDot();
        } catch (Exception ex) {
            Logger.printException(() -> "updateUI failure for category: "
                    + (category != null ? category.keyValue : "<unbound>"), ex);
        }
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);

        widgetColorDot = view.findViewById(ID_PREFERENCE_COLOR_DOT);
        updateWidgetColorDot();
    }

    private void updateWidgetColorDot() {
        if (widgetColorDot == null || category == null) return;

        ColorDot.applyColorDot(
                widgetColorDot,
                category.getColorWithOpacity(),
                isEnabled()
        );
    }

    @Nullable
    private static SegmentCategory byColorSettingKey(@Nullable String colorSettingKey) {
        if (colorSettingKey == null) return null;
        // activeCategories() respects Configuration.includesHighlight(); the full set would call
        // colorFor() on categories the host has not registered and throw.
        for (SegmentCategory category : SegmentCategory.activeCategories()) {
            if (colorSettingKey.equals(category.colorSetting().key)) {
                return category;
            }
        }
        return null;
    }
}
