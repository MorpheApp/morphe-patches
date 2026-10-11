/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Pair;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;

/**
 * Description of a settings screen. The screen toolbar gets an info button that shows
 * the preferences of this category in a dialog, and the category is left out of the list.
 */
@SuppressWarnings({"unused", "deprecation"})
public class ScreenInfoCategory extends PreferenceCategory {

    public ScreenInfoCategory(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public ScreenInfoCategory(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public ScreenInfoCategory(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ScreenInfoCategory(Context context) {
        super(context);
    }

    public void showDialog(Context context, CharSequence title) {
        // A preference that opens a link, such as the website of an API, becomes a button.
        Preference link = null;
        for (int i = 0, count = getPreferenceCount(); i < count; i++) {
            if (getPreference(i).getOnPreferenceClickListener() != null) {
                link = getPreference(i);
                break;
            }
        }
        Preference linkPreference = link;

        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(context, title, null,
                null, null, () -> {}, null,
                linkPreference == null ? null : str("gms_core_dialog_open_website_text"),
                linkPreference == null ? null : () -> linkPreference
                        .getOnPreferenceClickListener().onPreferenceClick(linkPreference),
                true);
        Dialog dialog = dialogPair.first;
        LinearLayout mainLayout = dialogPair.second;

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0, count = getPreferenceCount(); i < count; i++) {
            View section = createSection(context, getPreference(i));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) {
                params.topMargin = Dim.dp16;
            }
            content.addView(section, params);
        }

        ScrollView scrollView = new ScrollView(context);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scrollView.addView(content);

        // Between the title and the buttons, which are the last child.
        mainLayout.addView(scrollView, mainLayout.getChildCount() - 1,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));

        dialog.show();
    }

    private static View createSection(Context context, Preference preference) {
        CharSequence title = preference.getTitle();
        CharSequence summary = preference.getSummary();
        boolean hasTitle = !TextUtils.isEmpty(title);

        LinearLayout section = new LinearLayout(context);
        section.setOrientation(LinearLayout.VERTICAL);

        if (hasTitle) {
            TextView titleView = new TextView(context);
            titleView.setText(title);
            titleView.setTypeface(Typeface.DEFAULT_BOLD);
            titleView.setTextSize(16);
            titleView.setTextColor(ThemeUtils.getAppForegroundColor());
            section.addView(titleView);
        }

        if (!TextUtils.isEmpty(summary)) {
            TextView summaryView = new TextView(context);
            summaryView.setText(summary);
            summaryView.setTextSize(14);
            summaryView.setTextColor(ThemeUtils.getAppForegroundColor());
            if (hasTitle) {
                summaryView.setPadding(0, Dim.dp4, 0, 0);
            }
            section.addView(summaryView);
        }

        return section;
    }
}
