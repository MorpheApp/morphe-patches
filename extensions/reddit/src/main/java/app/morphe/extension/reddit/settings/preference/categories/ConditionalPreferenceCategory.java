/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.settings.preference.categories;

import static app.morphe.extension.shared.StringRef.str;

import android.annotation.SuppressLint;
import android.content.Context;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import app.morphe.extension.reddit.settings.preference.RedditSettingsStyle;

@SuppressWarnings("deprecation")
public abstract class ConditionalPreferenceCategory extends PreferenceCategory {
    /**
     * Page that holds this category with the modern style, otherwise null.
     * Categories cannot be added to a category, so titled sections go on the page.
     */
    @Nullable
    protected PreferenceScreen page;

    public ConditionalPreferenceCategory(Context context, PreferenceScreen screen,
                                         String pageKey, String titleKey, String summaryKey) {
        super(context);

        if (getSettingsStatus()) {
            if (RedditSettingsStyle.isModernStyle()) {
                page = RedditSettingsStyle.createSubPage(
                        screen.getPreferenceManager(),
                        context,
                        pageKey,
                        str(titleKey),
                        str(summaryKey)
                );
                screen.addPreference(page);
                // Distinct layout resource prevents PreferenceGroupAdapter from recycling this zero-height view for titled categories.
                setLayoutResource(android.R.layout.simple_list_item_1);
                page.addPreference(this);
            } else {
                setTitle(str(titleKey));
                screen.addPreference(this);
            }
            addPreferences(context);
        }
    }

    @Override
    @SuppressLint("MissingSuperCall")
    protected View onCreateView(ViewGroup parent) {
        if (RedditSettingsStyle.isModernStyle()) {
            View view = new View(getContext());
            view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            return view;
        }
        return super.onCreateView(parent);
    }

    public abstract boolean getSettingsStatus();

    public abstract void addPreferences(Context context);

    /**
     * Adds a child setting of the preference above it, indented with the modern settings style.
     */
    protected static void addIndentedPreference(PreferenceGroup group, Preference preference) {
        RedditSettingsStyle.setIndented(preference);
        group.addPreference(preference);
    }
}
