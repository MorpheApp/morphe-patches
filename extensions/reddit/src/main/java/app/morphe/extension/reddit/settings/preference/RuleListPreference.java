/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4867
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.app.AlertDialog;
import android.content.Context;
import android.preference.Preference;
import android.widget.EditText;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Function;

import app.morphe.extension.reddit.patches.ContentFilterPatch;

/** Shared rule editor. Each independent filter supplies its own validation. */
@SuppressWarnings("deprecation")
public final class RuleListPreference extends Preference {
    private final String ruleKey;
    private final String dialogTitle;
    private final String hint;
    private final Function<String, String> validator;

    public RuleListPreference(Context context, String key, String title, String summary,
                              String hint, Function<String, String> validator) {
        super(context);
        this.ruleKey = key;
        this.dialogTitle = title;
        this.hint = hint;
        this.validator = validator;
        setTitle(title);
        setSummary(summary);
        setOnPreferenceClickListener(ignored -> { showRules(); return true; });
    }

    public static List<String> lines(String raw) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String line : raw.split("\\r?\\n")) if (!line.trim().isEmpty()) values.add(line.trim());
        return new ArrayList<>(values);
    }

    public static void save(Context context, String key, List<String> rules) {
        ContentFilterPatch.preferences(context).edit().putString(key,
                String.join("\n", new LinkedHashSet<>(rules))).apply();
        Toast.makeText(context, str("morphe_content_filters_saved"), Toast.LENGTH_SHORT).show();
    }

    private void showRules() {
        Context context = getContext();
        List<String> rules = lines(ContentFilterPatch.preferences(context).getString(ruleKey, ""));
        new AlertDialog.Builder(context).setTitle(dialogTitle)
                .setItems(rules.toArray(new String[0]), (dialog, index) ->
                        new AlertDialog.Builder(context).setTitle(rules.get(index))
                                .setItems(new String[]{str("morphe_content_filters_edit"), str("morphe_content_filters_remove")},
                                        (action, selected) -> {
                                            if (selected == 0) edit(rules, index);
                                            else { rules.remove(index); save(context, ruleKey, rules); showRules(); }
                                        }).show())
                .setPositiveButton(str("morphe_content_filters_add"), (dialog, which) -> edit(rules, -1))
                .setNegativeButton(str("morphe_content_filters_close"), null).show();
    }

    private void edit(List<String> rules, int index) {
        Context context = getContext();
        EditText input = new EditText(context);
        input.setSingleLine(true);
        input.setHint(hint);
        if (index >= 0) input.setText(rules.get(index));
        AlertDialog dialog = new AlertDialog.Builder(context).setTitle(dialogTitle).setView(input)
                .setPositiveButton(str("morphe_settings_save"), null)
                .setNegativeButton(android.R.string.cancel, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String value = input.getText().toString().trim();
            if (value.isEmpty()) { input.setError(str("morphe_content_filters_empty")); return; }
            String error = validator.apply(value);
            if (error != null) { input.setError(error); return; }
            if (index >= 0) rules.set(index, value); else rules.add(value);
            save(context, ruleKey, rules);
            dialog.dismiss();
            showRules();
        }));
        dialog.show();
    }
}
