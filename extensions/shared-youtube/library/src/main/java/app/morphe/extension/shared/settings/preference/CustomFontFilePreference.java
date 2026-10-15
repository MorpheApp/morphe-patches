/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3579
 * https://github.com/MorpheApp/morphe-patches/pull/3673
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.preference.Preference;
import android.provider.OpenableColumns;
import android.util.AttributeSet;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.CustomFontPatch;
import app.morphe.extension.shared.patches.CustomFontPatch.FontType;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;

/**
 * Picks the custom font file and shows the name of the picked file.
 */
@SuppressWarnings({"unused", "deprecation"})
public class CustomFontFilePreference extends Preference {

    public static final int PICK_FONT_REQUEST_CODE = 0xC001;

    private static WeakReference<CustomFontFilePreference> shownPreference = new WeakReference<>(null);

    public CustomFontFilePreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        initialize();
    }

    public CustomFontFilePreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initialize();
    }

    public CustomFontFilePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        initialize();
    }

    public CustomFontFilePreference(Context context) {
        super(context);
        initialize();
    }

    private void initialize() {
        shownPreference = new WeakReference<>(this);
        updateSummary();
        setOnPreferenceClickListener(preference -> {
            openPicker();
            return true;
        });
    }

    private void updateSummary() {
        String fileName = SharedYouTubeSettings.CUSTOM_FONT_FILE.get();
        setSummary(fileName.isEmpty()
                ? str("morphe_custom_font_file_summary")
                : fileName);
    }

    static void openPicker() {
        AbstractPreferenceFragment fragment = AbstractPreferenceFragment.instance.get();
        if (fragment == null) {
            Logger.printException(() -> "No settings fragment to open the file picker");
            return;
        }

        // Not all document providers report a font MIME type for font files,
        // so any file can be picked and the file is checked after.
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            fragment.startActivityForResult(intent, PICK_FONT_REQUEST_CODE);
        } catch (ActivityNotFoundException ex) {
            Logger.printException(() -> "No file picker found", ex);
        }
    }

    /**
     * Called by the settings fragment with the result of the file picker.
     */
    public static void handleActivityResult(Context context, int resultCode, @Nullable Intent data) {
        try {
            if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
                return;
            }

            Uri uri = data.getData();
            String fileName = getFileName(context, uri);
            if (fileName == null || !isFontFileName(fileName)
                    || !CustomFontPatch.importFontFile(context, uri)) {
                Utils.showToastLong(str("morphe_custom_font_file_invalid"));
                return;
            }

            // The font file can also be picked when the custom font is selected without a file.
            AbstractPreferenceFragment.settingImportInProgress = true;
            try {
                SharedYouTubeSettings.CUSTOM_FONT_FILE.save(fileName);
                SharedYouTubeSettings.CUSTOM_FONT_TYPE.save(FontType.CUSTOM);
            } finally {
                AbstractPreferenceFragment.settingImportInProgress = false;
            }

            CustomFontFilePreference preference = shownPreference.get();
            if (preference != null) {
                preference.updateSummary();
            }

            AbstractPreferenceFragment.showRestartDialog(context);
        } catch (Exception ex) {
            Logger.printException(() -> "handleActivityResult failure", ex);
        }
    }

    private static boolean isFontFileName(String name) {
        name = name.toLowerCase(Locale.ENGLISH);
        return name.endsWith(".ttf") || name.endsWith(".otf");
    }

    @Nullable
    private static String getFileName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not query file name: " + uri, ex);
        }

        return uri.getLastPathSegment();
    }
}
