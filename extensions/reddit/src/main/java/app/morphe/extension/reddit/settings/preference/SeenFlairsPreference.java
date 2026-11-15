/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4868
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.preference.Preference;
import android.util.Pair;
import android.util.SparseBooleanArray;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckedTextView;
import android.widget.LinearLayout;
import android.widget.ListView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import app.morphe.extension.reddit.patches.FlairFiltersPatch;
import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;

/**
 * Chooses flair filters from the flairs seen in feeds, and from the current flair filters.
 */
@SuppressWarnings("deprecation")
public final class SeenFlairsPreference extends Preference {
    public SeenFlairsPreference(Context context) {
        super(context);

        setTitle(str("morphe_flair_filters_seen_title"));
        setSummary(str("morphe_flair_filters_seen_summary"));
        setOnPreferenceClickListener(preference -> {
            showDialog();
            return true;
        });
    }

    private void showDialog() {
        Context context = getContext();
        List<String> rules = FlairFiltersPatch.getRules(Settings.FLAIR_FILTERS.get());

        // The current flair filters first, then the seen flairs that are not filtered.
        List<String> choices = new ArrayList<>(rules);
        Set<String> filtered = new HashSet<>();
        for (String rule : rules) {
            filtered.add(FlairFiltersPatch.parseRule(rule));
        }
        List<String> seenFlairs = FlairFiltersPatch.getSeenFlairs();
        seenFlairs.sort(String.CASE_INSENSITIVE_ORDER);
        for (String flair : seenFlairs) {
            if (filtered.add(FlairFiltersPatch.parseRule(flair))) {
                choices.add(flair);
            }
        }

        if (choices.isEmpty()) {
            CustomDialog.create(
                    context,
                    str("morphe_flair_filters_seen_title"),
                    str("morphe_flair_filters_seen_empty"),
                    null,
                    null,
                    () -> {},
                    null,
                    null,
                    null,
                    false
            ).first.show();
            return;
        }

        ListView listView = new ListView(context);
        listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        listView.setDivider(null);
        listView.setAdapter(new ArrayAdapter<>(context, 0, choices) {
            @Override
            public View getView(int position, @Nullable View convertView, ViewGroup parent) {
                CheckedTextView row = convertView instanceof CheckedTextView checkedTextView
                        ? checkedTextView
                        : createRow(context);
                row.setText(choices.get(position));
                return row;
            }
        });
        for (int i = 0, size = rules.size(); i < size; i++) {
            listView.setItemChecked(i, true);
        }

        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                context,
                str("morphe_flair_filters_seen_title"),
                null,
                null,
                str("morphe_settings_save"),
                () -> {
                    SparseBooleanArray checked = listView.getCheckedItemPositions();
                    List<String> selected = new ArrayList<>();
                    for (int i = 0, size = choices.size(); i < size; i++) {
                        if (checked.get(i)) {
                            selected.add(choices.get(i));
                        }
                    }
                    if (!Objects.equals(selected, rules)) {
                        FlairFiltersPreference.saveRules(selected);
                    }
                },
                () -> {},
                null,
                null,
                false
        );

        LinearLayout mainLayout = dialogPair.second;
        mainLayout.addView(listView, mainLayout.getChildCount() - 1,
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        dialogPair.first.show();
    }

    private static CheckedTextView createRow(Context context) {
        CheckedTextView row = new CheckedTextView(context);
        row.setTextSize(16);
        row.setTextColor(ThemeUtils.getAppForegroundColor());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Dim.dp8, Dim.dp12, Dim.dp8, Dim.dp12);

        TypedArray attributes = context.obtainStyledAttributes(
                new int[]{android.R.attr.listChoiceIndicatorMultiple});
        row.setCheckMarkDrawable(attributes.getDrawable(0));
        attributes.recycle();
        row.setCheckMarkTintList(ColorStateList.valueOf(ThemeUtils.getAppForegroundColor()));
        return row;
    }
}
