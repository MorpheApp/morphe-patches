/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2691
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.Pair;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;

/**
 * Preset names to pick from, with a text field for a name of the user's own.
 * Presets store their entry value, and a name the user typed is stored as itself.
 */
@SuppressWarnings({"unused", "deprecation"})
public class SettingsNamePreference extends CustomDialogListPreference {

    /**
     * Entry value of the row that shows the text field. Any name that is not a preset
     * matches no entry value, so that row is what shows it.
     */
    private static final String CUSTOM_ENTRY_VALUE = "CUSTOM";

    public SettingsNamePreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public SettingsNamePreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public SettingsNamePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SettingsNamePreference(Context context) {
        super(context);
    }

    private String currentValue() {
        String value = getValue();
        return value == null ? "" : value;
    }

    @Override
    protected void showDialog(@Nullable Bundle state) {
        Context context = getContext();
        CharSequence[] entryValues = getEntryValues();

        // A name that matches no row is a name the user typed, so the custom row shows it.
        final boolean usingCustomName = findIndexOfValue(currentValue()) < 0;
        // In an array so the dialog callbacks can reassign it.
        final String[] selectedValue = {
                usingCustomName ? CUSTOM_ENTRY_VALUE : currentValue()
        };

        EditText editText = CustomDialog.createEditText(context);
        editText.setHint(str("morphe_settings_name_custom_item_hint"));
        editText.setSingleLine(true);

        ListView listView = new ListView(context);
        listView.setId(android.R.id.list);
        listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        ListPreferenceArrayAdapter adapter = new ListPreferenceArrayAdapter(
                context,
                LAYOUT_MORPHE_CUSTOM_LIST_ITEM_CHECKED,
                getEntries(),
                entryValues,
                selectedValue[0]
        );
        listView.setAdapter(adapter);

        // Only the custom row has a name of its own to show, and the preset rows
        // already show theirs in the list.
        Runnable applySelection = () -> {
            final boolean isCustom = selectedValue[0].equals(CUSTOM_ENTRY_VALUE);
            if (isCustom) {
                String text = usingCustomName ? currentValue() : "";
                editText.setText(text);
                editText.setSelection(text.length());
            }
            editText.setVisibility(isCustom ? View.VISIBLE : View.GONE);

            for (int i = 0, length = entryValues.length; i < length; i++) {
                if (entryValues[i].toString().equals(selectedValue[0])) {
                    listView.setItemChecked(i, true);
                    listView.setSelection(i);
                    break;
                }
            }
            adapter.setSelectedValue(selectedValue[0]);
            adapter.notifyDataSetChanged();
        };
        applySelection.run();

        listView.setOnItemClickListener((parent, view, position, id) -> {
            selectedValue[0] = entryValues[position].toString();
            applySelection.run();

            if (editText.getVisibility() == View.VISIBLE) {
                editText.requestFocus();
            }
        });

        LinearLayout contentLayout = new LinearLayout(context);
        contentLayout.setOrientation(LinearLayout.VERTICAL);

        contentLayout.addView(listView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        // The spacing belongs to the text field, so it goes away with it when hidden.
        LinearLayout.LayoutParams editTextParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        editTextParams.topMargin = Dim.dp16;
        contentLayout.addView(editText, editTextParams);

        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                context,
                getTitle() != null ? getTitle().toString() : "",
                null,
                null,
                null,
                () -> {
                    String newValue = selectedValue[0];
                    if (newValue.equals(CUSTOM_ENTRY_VALUE)) {
                        newValue = editText.getText().toString().trim();

                        if (newValue.isEmpty()) {
                            CustomDialog.create(
                                    context,
                                    str("morphe_settings_name_title"),
                                    str("morphe_settings_name_empty_warning"),
                                    null,
                                    null,
                                    () -> {}, // OK button does nothing (dismiss only).
                                    null,
                                    null,
                                    null,
                                    false
                            ).first.show();
                            return;
                        }
                    }

                    if (callChangeListener(newValue)) {
                        setValue(newValue);
                    }
                },
                () -> {}, // Cancel button action (dismiss only).
                str("morphe_settings_reset"),
                () -> { // Reset action.
                    selectedValue[0] = entryValues[0].toString(); // Default is the first row.
                    applySelection.run();
                },
                false
        );

        LinearLayout mainLayout = dialogPair.second;
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f);

        // Insert content before the dialog button row.
        mainLayout.addView(contentLayout, mainLayout.getChildCount() - 1, contentParams);

        dialogPair.first.show();
    }
}
