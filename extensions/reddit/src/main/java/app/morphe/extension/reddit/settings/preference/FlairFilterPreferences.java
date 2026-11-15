/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.settings.preference;

import android.app.AlertDialog;
import android.content.Context;
import android.preference.CheckBoxPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import app.morphe.extension.reddit.patches.ContentFilterPatch;
import app.morphe.extension.reddit.patches.FlairFilterPatch;
import static app.morphe.extension.shared.StringRef.str;

@SuppressWarnings("deprecation")
public final class FlairFilterPreferences {
    private FlairFilterPreferences() { }

    public static void addPreferences(Context context, PreferenceCategory category) {
        category.addPreference(new RuleListPreference(context, FlairFilterPatch.RULES,
                str("morphe_flair_filters_title"), str("morphe_flair_filters_summary"),
                str("morphe_flair_filters_hint"), value -> {
                    int split = value.indexOf("::");
                    String owner = split < 0 ? "" : value.substring(0, split).trim().replaceFirst("(?i)^r/", "");
                    return owner.isEmpty() || value.substring(split + 2).trim().isEmpty()
                            ? str("morphe_flair_filters_invalid") : null;
                }));
        CheckBoxPreference collect = new CheckBoxPreference(context);
        collect.setTitle(str("morphe_flair_filters_collect_title"));
        collect.setSummary(str("morphe_flair_filters_collect_summary"));
        collect.setPersistent(false);
        collect.setChecked(FlairFilterPatch.collectFlairs());
        collect.setOnPreferenceChangeListener((preference, value) -> {
            ContentFilterPatch.preferences(context).edit().putBoolean(FlairFilterPatch.COLLECT, (Boolean) value).apply();
            return true;
        });
        category.addPreference(collect);
        Preference observed = new Preference(context);
        observed.setTitle(str("morphe_flair_filters_observed_title"));
        observed.setSummary(str("morphe_flair_filters_observed_summary"));
        observed.setOnPreferenceClickListener(ignored -> { chooseFlairs(context); return true; });
        category.addPreference(observed);
    }

    private static void chooseFlairs(Context context) {
        List<String> blocked = RuleListPreference.lines(ContentFilterPatch.preferences(context).getString(FlairFilterPatch.RULES, ""));
        LinkedHashSet<String> choices = new LinkedHashSet<>(blocked);
        choices.addAll(FlairFilterPatch.observedFlairs());
        List<String> values = new ArrayList<>(choices);
        Collections.sort(values, String.CASE_INSENSITIVE_ORDER);
        if (values.isEmpty()) {
            new AlertDialog.Builder(context).setTitle(str("morphe_flair_filters_observed_title"))
                    .setMessage(str("morphe_flair_filters_observed_empty"))
                    .setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        boolean[] checked = new boolean[values.size()];
        for (int i = 0; i < values.size(); i++) {
            final String value = values.get(i);
            checked[i] = blocked.stream().anyMatch(rule -> rule.equalsIgnoreCase(value));
        }
        new AlertDialog.Builder(context).setTitle(str("morphe_flair_filters_blocked_title"))
                .setMultiChoiceItems(values.toArray(new String[0]), checked,
                        (dialog, index, selected) -> checked[index] = selected)
                .setPositiveButton(str("morphe_settings_save"), (dialog, which) -> {
                    List<String> selected = new ArrayList<>();
                    for (int i = 0; i < values.size(); i++) if (checked[i]) selected.add(values.get(i));
                    RuleListPreference.save(context, FlairFilterPatch.RULES, selected);
                }).setNegativeButton(android.R.string.cancel, null).show();
    }
}
