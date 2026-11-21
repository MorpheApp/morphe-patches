/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5134
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.settings.preference;

import android.content.Context;
import android.util.AttributeSet;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.preference.CustomDialogListPreference;
import app.morphe.extension.youtube.holdgesture.LauncherAction;

/**
 * List of the actions for a slot of the launcher of the hold gesture, without the actions of
 * patches that were not used on the app. Those would leave the slot empty.
 */
@SuppressWarnings({"unused", "deprecation"})
public class HoldGestureLauncherActionPreference extends CustomDialogListPreference {

    public HoldGestureLauncherActionPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    public HoldGestureLauncherActionPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public HoldGestureLauncherActionPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public HoldGestureLauncherActionPreference(Context context) {
        super(context);
        init();
    }

    private void init() {
        CharSequence[] entries = getEntries();
        CharSequence[] entryValues = getEntryValues();
        if (entries == null || entryValues == null || entries.length != entryValues.length) {
            return;
        }

        // The preference value is only loaded once it is attached to the hierarchy.
        Setting<?> setting = Setting.getSettingFromPath(getKey());
        String currentValue = setting == null ? "" : String.valueOf(setting.get());
        List<CharSequence> shownEntries = new ArrayList<>(entries.length);
        List<CharSequence> shownValues = new ArrayList<>(entryValues.length);

        for (int i = 0, length = entryValues.length; i < length; i++) {
            String value = entryValues[i].toString();
            boolean available;
            try {
                available = LauncherAction.valueOf(value).isAvailable();
            } catch (IllegalArgumentException ex) {
                available = true;
            }
            // Keep what the slot is set to, so that the list shows it as picked.
            if (available || value.equals(currentValue)) {
                shownEntries.add(entries[i]);
                shownValues.add(value);
            }
        }

        setEntries(shownEntries.toArray(new CharSequence[0]));
        setEntryValues(shownValues.toArray(new CharSequence[0]));
    }

    @Override
    public boolean tracksNewEntries() {
        return false; // Entries depend on the patches that were used.
    }
}
