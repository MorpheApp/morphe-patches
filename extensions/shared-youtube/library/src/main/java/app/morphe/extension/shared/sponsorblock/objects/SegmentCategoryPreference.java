/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.sponsorblock.objects;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.preference.PreferenceManager;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.Objects;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.preference.ColorPickerPreference;
import app.morphe.extension.shared.settings.preference.CustomDialogListPreference;
import app.morphe.extension.shared.sponsorblock.SponsorBlockApi;
import app.morphe.extension.shared.sponsorblock.SponsorBlockHelpers;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.Dim;

/**
 * Per-category SponsorBlock preference. The row shows the current skip behavior and opens a
 * behavior list, the color dot opens the color picker. The available behavior set is supplied by
 * {@link SponsorBlockApi.Configuration#availableBehaviors(SegmentCategory)}.
 */
@SuppressWarnings({"unused", "deprecation"})
public class SegmentCategoryPreference extends ColorPickerPreference {
    /** Resolved on construction (programmatic) or in {@link #onAttachedToHierarchy} (XML). */
    @Nullable
    public SegmentCategory category;

    private static final String DIVIDER_TAG = "morphe_sb_color_divider";

    // Re-evaluates the enabled / dim state when the master SB toggle (or any setting that
    // could change behaviorSetting().isAvailable()) is flipped from elsewhere in the UI.
    private final SharedPreferences.OnSharedPreferenceChangeListener prefChangeListener =
            (prefs, changedKey) -> Utils.runOnMainThread(this::updateUI);

    public SegmentCategoryPreference(Context context, SegmentCategory category) {
        super(context);
        setOpacitySliderEnabled(true);
        bindCategory(Objects.requireNonNull(category));
    }

    public SegmentCategoryPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOpacitySliderEnabled(true);
    }

    public SegmentCategoryPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setOpacitySliderEnabled(true);
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
        // Sync initial color from category.
        setText(category.colorSetting().get());
        updateUI();
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
            colorString = SponsorBlockHelpers.migrateOldColorString(colorString,
                    SegmentCategory.CATEGORY_DEFAULT_OPACITY);

            if (category == null) {
                return;
            }

            super.setText(colorString);
            category.setColorWithOpacity(colorString);
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

    /**
     * The row picks the skip behavior, the color dot opens the color picker.
     */
    @Override
    protected void onClick() {
        SegmentCategory category = this.category;
        if (category == null) return;
        CategoryBehaviour[] behaviors = SponsorBlockApi.config().availableBehaviors(category);

        CharSequence[] entries = new CharSequence[behaviors.length];
        CharSequence[] entryValues = new CharSequence[behaviors.length];
        for (int i = 0; i < behaviors.length; i++) {
            entries[i] = behaviors[i].description.toString();
            entryValues[i] = behaviors[i].morpheKeyValue;
        }

        CustomDialogListPreference.createListDialog(
                getContext(),
                category.title.toString(),
                entries,
                entryValues,
                savedBehaviour(category).morpheKeyValue,
                // prefChangeListener refreshes the summary once the setting is saved.
                position -> {
                    category.setBehaviour(behaviors[position]);
                    SegmentCategory.updateEnabledCategories();
                }
        ).show();
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
            updateSummary();
        } catch (Exception ex) {
            Logger.printException(() -> "updateUI failure for category: "
                    + (category != null ? category.keyValue : "<unbound>"), ex);
        }
    }

    private void updateSummary() {
        if (category == null) return;
        CategoryBehaviour behaviour = savedBehaviour(category);
        SpannableStringBuilder summary = new SpannableStringBuilder(behaviour.description.toString());
        // Accent shows the behavior is a changeable value, gray when it has no effect.
        if (isEnabled() && behaviour != CategoryBehaviour.IGNORE) {
            summary.setSpan(new ForegroundColorSpan(getAccentColor()), 0, summary.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        summary.append('\n').append(category.description.toString());
        setSummary(summary);
    }

    /**
     * {@link SegmentCategory#behaviour} is only loaded once a video plays, the setting is always current.
     */
    private static CategoryBehaviour savedBehaviour(SegmentCategory category) {
        CategoryBehaviour behaviour = CategoryBehaviour.byMorpheKeyValue(category.behaviorSetting().get());
        return behaviour != null ? behaviour : category.behaviour;
    }

    /**
     * The color of the settings switches. The accent of the settings theme is the text color.
     */
    private int getAccentColor() {
        Context context = getContext();
        TypedValue value = new TypedValue();
        final int attribute = ResourceUtils.getIdentifier(ResourceType.ATTR, "colorControlActivated");
        if (attribute != 0 && context.getTheme().resolveAttribute(attribute, value, true)) {
            if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return value.data;
            }
            if (value.resourceId != 0) {
                return context.getColorStateList(value.resourceId).getDefaultColor();
            }
        }

        context.getTheme().resolveAttribute(android.R.attr.colorAccent, value, true);
        return value.data;
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);

        // The default 4 line limit cuts the longer descriptions.
        TextView summaryView = view.findViewById(android.R.id.summary);
        if (summaryView != null) {
            summaryView.setMaxLines(Integer.MAX_VALUE);
        }

        View colorDot = view.findViewById(ID_PREFERENCE_COLOR_DOT);
        if (colorDot != null) {
            bindColorTarget(colorDot, this);
        }
    }

    /**
     * Makes the color dot a tap target separate from the row. The search results reuse a row for
     * other color preferences, so a null preference turns it back into a plain dot.
     */
    public static void bindColorTarget(View colorDot, @Nullable SegmentCategoryPreference preference) {
        if (!(colorDot.getParent() instanceof FrameLayout dotFrame)) return;
        View divider = dotFrame.findViewWithTag(DIVIDER_TAG);

        if (preference == null) {
            if (divider != null) {
                dotFrame.removeView(divider);
                dotFrame.setBackground(null);
                dotFrame.setContentDescription(null);
                dotFrame.setOnClickListener(null);
                dotFrame.setClickable(false);
            }
            return;
        }

        if (divider == null) {
            Context context = dotFrame.getContext();
            divider = new View(context);
            divider.setTag(DIVIDER_TAG);
            int foreground = ThemeUtils.getAppForegroundColor();
            divider.setBackgroundColor(Color.argb(0x33,
                    Color.red(foreground), Color.green(foreground), Color.blue(foreground)));
            dotFrame.addView(divider, new FrameLayout.LayoutParams(
                    Dim.dp1, Dim.dp32, Gravity.START | Gravity.CENTER_VERTICAL));

            dotFrame.setBackgroundResource(ThemeUtils.getThemeResourceId(
                    context, android.R.attr.selectableItemBackgroundBorderless));
            dotFrame.setContentDescription(str("morphe_sb_color_dot_label"));
        }
        // The search results do not disable a row, so the preference itself is checked.
        dotFrame.setOnClickListener(v -> {
            if (preference.isEnabled()) {
                preference.showDialog(null);
            }
        });
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
