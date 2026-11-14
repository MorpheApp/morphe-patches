/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.PreferenceManager;
import android.util.AttributeSet;
import android.util.Pair;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Set;
import java.util.function.IntConsumer;

import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;

/**
 * A custom ListPreference that uses a styled custom dialog with a custom checkmark indicator,
 * supports a static summary and highlighted entries for search functionality.
 */
@SuppressWarnings({"unused", "deprecation"})
public class CustomDialogListPreference extends ListPreference implements SettingPreference {

    public static final int ID_MORPHE_CHECK_ICON = View.generateViewId();
    public static final int ID_MORPHE_CHECK_ICON_PLACEHOLDER = View.generateViewId();
    public static final int ID_MORPHE_ITEM_TEXT = View.generateViewId();
    public static final int DRAWABLE_CHECKMARK = ResourceUtils.getIdentifierOrThrow(
            ResourceType.DRAWABLE, "morphe_settings_custom_checkmark");
    public static final int DRAWABLE_CHECKMARK_BOLD = ResourceUtils.getIdentifierOrThrow(
            ResourceType.DRAWABLE, "morphe_settings_custom_checkmark_bold");

    @Nullable
    private Setting<?> setting;

    /**
     * If this preference saves the setting, which it leaves to a subclass that turned off persisting.
     */
    private boolean savesSetting;

    private CharSequence[] highlightedEntriesForDialog = null;
    @Nullable
    private Set<String> newEntryValues;
    @ColorInt
    private int newEntryColor;

    /**
     * Sets highlighted entries for display in the dialog.
     * These entries are used only for the current dialog and are automatically cleared.
     */
    public void setHighlightedEntriesForDialog(CharSequence[] highlightedEntries) {
        this.highlightedEntriesForDialog = highlightedEntries;
    }

    /**
     * Clears highlighted entries after the dialog is closed.
     */
    public void clearHighlightedEntriesForDialog() {
        this.highlightedEntriesForDialog = null;
    }

    /**
     * @return If an entry that was not shown before is a new option added by an update.
     *         False if the entries depend on the device, installed apps or user data.
     */
    public boolean tracksNewEntries() {
        return true;
    }

    /**
     * Sets the entry values that are shown with a dot in the dialog.
     */
    public void setNewEntryValues(@Nullable Set<String> values, @ColorInt int color) {
        this.newEntryValues = values;
        this.newEntryColor = color;
    }

    /**
     * Returns entries for display in the dialog.
     * If highlighted entries exist, they are used; otherwise, the original entries are returned.
     * New entries are shown with a dot.
     */
    protected CharSequence[] getEntriesForDialog() {
        CharSequence[] entries = highlightedEntriesForDialog != null ? highlightedEntriesForDialog : getEntries();
        CharSequence[] entryValues = getEntryValues();
        if (newEntryValues == null || entries == null || entryValues == null) {
            return entries;
        }

        // Entries can be a String[], which cannot hold the spans added here.
        CharSequence[] marked = Arrays.copyOf(entries, entries.length, CharSequence[].class);
        for (int i = 0, length = Math.min(entries.length, entryValues.length); i < length; i++) {
            if (newEntryValues.contains(entryValues[i].toString())) {
                marked[i] = NewIndicatorSpan.addTo(entries[i], newEntryColor);
            }
        }
        return marked;
    }

    /**
     * Creates a list row with a checkmark, a placeholder of the same size and a text.
     * Find the children with {@link #ID_MORPHE_CHECK_ICON},
     * {@link #ID_MORPHE_CHECK_ICON_PLACEHOLDER} and {@link #ID_MORPHE_ITEM_TEXT}.
     */
    public static LinearLayout createCheckedListItem(Context context) {
        return createCheckedListItem(context, Dim.dp12, Dim.dp24);
    }

    static LinearLayout createCheckedListItem(Context context, int verticalPadding, int checkMarginEnd) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(Dim.dp16, verticalPadding, Dim.dp16, verticalPadding);
        row.setGravity(Gravity.CENTER_VERTICAL);

        ImageView checkIcon = new ImageView(context);
        checkIcon.setId(ID_MORPHE_CHECK_ICON);
        checkIcon.setImageResource(Utils.appIsUsingBoldIcons() ? DRAWABLE_CHECKMARK_BOLD : DRAWABLE_CHECKMARK);
        checkIcon.setVisibility(View.GONE);
        row.addView(checkIcon, checkSizeParams(checkMarginEnd));

        View placeholder = new View(context);
        placeholder.setId(ID_MORPHE_CHECK_ICON_PLACEHOLDER);
        placeholder.setVisibility(View.INVISIBLE);
        row.addView(placeholder, checkSizeParams(checkMarginEnd));

        TextView itemText = new TextView(context);
        itemText.setId(ID_MORPHE_ITEM_TEXT);
        ThemeUtils.setThemeTextStyle(itemText, android.R.attr.textAppearanceListItem,
                android.R.attr.textColorPrimary);
        row.addView(itemText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return row;
    }

    private static LinearLayout.LayoutParams checkSizeParams(int marginEnd) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(Dim.dp24, Dim.dp24);
        params.setMarginEnd(marginEnd);
        return params;
    }

    /**
     * Custom ArrayAdapter to handle checkmark visibility.
     */
    public static class ListPreferenceArrayAdapter extends ArrayAdapter<CharSequence> {
        private static class SubViewDataContainer {
            ImageView checkIcon;
            View placeholder;
            TextView itemText;
        }

        final CharSequence[] entryValues;
        String selectedValue;

        public ListPreferenceArrayAdapter(Context context,
                                          CharSequence[] entries,
                                          CharSequence[] entryValues,
                                          String selectedValue) {
            super(context, 0, entries);
            this.entryValues = entryValues;
            this.selectedValue = selectedValue;
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            View view = convertView;
            SubViewDataContainer holder;

            if (view == null) {
                view = createCheckedListItem(getContext());
                holder = new SubViewDataContainer();
                holder.placeholder = view.findViewById(ID_MORPHE_CHECK_ICON_PLACEHOLDER);
                holder.itemText = view.findViewById(ID_MORPHE_ITEM_TEXT);
                holder.checkIcon = view.findViewById(ID_MORPHE_CHECK_ICON);
                view.setTag(holder);
            } else {
                holder = (SubViewDataContainer) view.getTag();
            }

            CharSequence itemText = getItem(position);
            holder.itemText.setText(itemText);
            holder.itemText.setTextColor(ThemeUtils.getDialogTextColor());

            // Show or hide checkmark and placeholder.
            String currentValue = entryValues[position].toString();
            boolean isSelected = currentValue.equals(selectedValue);
            holder.checkIcon.setVisibility(isSelected ? View.VISIBLE : View.GONE);
            holder.checkIcon.setColorFilter(ThemeUtils.getDialogTextColor());
            holder.placeholder.setVisibility(isSelected ? View.GONE : View.VISIBLE);

            return view;
        }

        public void setSelectedValue(String value) {
            this.selectedValue = value;
        }
    }

    public CustomDialogListPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        PreferenceTranslations.translateEntries(this, attrs);
    }

    public CustomDialogListPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        PreferenceTranslations.translateEntries(this, attrs);
    }

    public CustomDialogListPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        PreferenceTranslations.translateEntries(this, attrs);
    }

    public CustomDialogListPreference(Context context) {
        super(context);
    }

    /**
     * Uses the title and entry arrays declared for the setting key.
     */
    public CustomDialogListPreference(Context context, Setting<?> setting) {
        this(context);

        String key = setting.key;
        setKey(key);
        this.setting = setting;
        setTitle(str(key + "_title"));
        setEntries(ResourceUtils.getStringArray(key + "_entries"));
        setEntryValues(ResourceUtils.getStringArray(key + "_entry_values"));
    }

    @Nullable
    public Setting<?> getSetting() {
        return setting;
    }

    @Override
    protected void onAttachedToHierarchy(PreferenceManager preferenceManager) {
        if (setting == null) {
            setting = SettingChange.findSetting(this, Setting.class);
        }
        savesSetting = setting != null && isPersistent();
        if (savesSetting) {
            // Before attaching, which would read the value from the shared preferences.
            setPersistent(false);
        }

        super.onAttachedToHierarchy(preferenceManager);
        updateFromSetting();
    }

    @Override
    protected boolean persistString(String value) {
        if (!savesSetting) {
            return super.persistString(value);
        }

        SettingChange.save(this, setting, value);
        return true;
    }

    /**
     * Shows the value of the setting, with its entry as the summary.
     */
    @Override
    public void updateFromSetting() {
        if (setting == null) {
            return;
        }

        String value = setting.get().toString();
        setValue(value);
        final int entryIndex = findIndexOfValue(value);
        if (entryIndex >= 0) {
            setSummary(getEntries()[entryIndex]);
        } else {
            // Value is not an available option.
            // User manually edited import data, or options changed and current selection is no longer available.
            // Still show the value in the summary, so it's clear that something is selected.
            setSummary(value);
        }
        setEnabled(setting.isAvailable());
    }

    /**
     * Opens the selection dialog, as if the preference row itself was tapped.
     * Allows another preference to present this list on its behalf.
     */
    public void showSelectionDialog() {
        showDialog(null);
    }

    /**
     * Creates the styled single choice dialog without showing it, for any preference.
     * Picking an entry passes its index to {@code onSelected} and dismisses the dialog.
     */
    public static Dialog createListDialog(Context context, String title,
                                          CharSequence[] entries, CharSequence[] entryValues,
                                          @Nullable String selectedValue, IntConsumer onSelected) {
        return createListDialog(context, title, entries, entryValues, selectedValue, onSelected,
                null, null);
    }

    /**
     * @param neutralText  The text of an extra button, or null for none.
     * @param neutralClick What the extra button does.
     */
    public static Dialog createListDialog(Context context, String title,
                                          CharSequence[] entries, CharSequence[] entryValues,
                                          @Nullable String selectedValue, IntConsumer onSelected,
                                          @Nullable CharSequence neutralText,
                                          @Nullable Runnable neutralClick) {
        // Create ListView.
        ListView listView = new ListView(context);
        listView.setId(android.R.id.list);
        listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        listView.setDividerHeight(0);

        // Create custom adapter for the ListView.
        ListPreferenceArrayAdapter adapter = new ListPreferenceArrayAdapter(
                context,
                entries,
                entryValues,
                selectedValue
        );
        listView.setAdapter(adapter);

        // Set checked item.
        if (selectedValue != null) {
            for (int i = 0, length = entryValues.length; i < length; i++) {
                if (selectedValue.equals(entryValues[i].toString())) {
                    listView.setItemChecked(i, true);
                    listView.setSelection(i);
                    break;
                }
            }
        }

        // Create the custom dialog without OK button.
        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                context,
                title,
                null,
                null,
                null,
                null,
                () -> {}, // Cancel button action.
                neutralText,
                neutralClick,
                true
        );

        Dialog dialog = dialogPair.first;

        // Add the ListView to the main layout.
        LinearLayout mainLayout = dialogPair.second;
        LinearLayout.LayoutParams listViewParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
        );
        mainLayout.addView(listView, mainLayout.getChildCount() - 1, listViewParams);

        // Handle item click to select value and dismiss dialog.
        listView.setOnItemClickListener((parent, view, position, id) -> {
            onSelected.accept(position);
            dialog.dismiss();
        });

        return dialog;
    }

    @Override
    protected void showDialog(Bundle state) {
        CharSequence[] entryValues = getEntryValues();

        Dialog dialog = createListDialog(
                getContext(),
                getTitle() != null ? getTitle().toString() : "",
                getEntriesForDialog(),
                entryValues,
                getValue(),
                position -> {
                    String selectedValue = entryValues[position].toString();
                    if (callChangeListener(selectedValue)) {
                        setValue(selectedValue);

                        // Update summaries from the original entries (without highlighting).
                        CharSequence[] originalEntries = getEntries();
                        if (originalEntries != null && position < originalEntries.length) {
                            setSummary(originalEntries[position]);
                        }
                    }
                }
        );

        // Add a listener to clear when the dialog is closed in any way.
        dialog.setOnDismissListener(dialogInterface -> clearHighlightedEntriesForDialog());

        // Show the dialog.
        dialog.show();
    }
}
