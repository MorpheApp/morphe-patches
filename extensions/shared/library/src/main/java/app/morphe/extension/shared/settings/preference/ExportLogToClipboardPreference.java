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

import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;

import app.morphe.extension.shared.Logger;

@SuppressWarnings({"unused", "deprecation"})
public class ExportLogToClipboardPreference extends Preference implements Preference.OnPreferenceClickListener {

    public ExportLogToClipboardPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }
    public ExportLogToClipboardPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }
    public ExportLogToClipboardPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    public ExportLogToClipboardPreference(Context context) {
        super(context);
        init();
    }

    private void init() {
        setOnPreferenceClickListener(this);
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
        try {
            AbstractPreferenceFragment fragment = AbstractPreferenceFragment.instance.get();
            if (fragment != null) {
                fragment.showLogDialog();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onPreferenceClick failure", ex);
        }
        return true;
    }
}
