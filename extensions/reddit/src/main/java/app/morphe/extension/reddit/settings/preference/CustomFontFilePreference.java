/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.extension.reddit.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.database.Cursor;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.preference.Preference;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import java.lang.ref.WeakReference;
import java.util.Locale;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;

@SuppressWarnings("deprecation")
public final class CustomFontFilePreference extends Preference {
    public static final int READ_FONT_REQUEST_CODE = 0xC001;

    private static WeakReference<CustomFontFilePreference> pendingPreference = new WeakReference<>(null);

    private final StringSetting setting;
    private final String defaultSummary;

    public CustomFontFilePreference(Context context, StringSetting setting) {
        super(context);
        this.setting = setting;

        setTitle(str(setting.key + "_title"));
        setKey(setting.key);

        String summaryKey = setting.key + "_summary";
        this.defaultSummary = ResourceUtils.getStringIdentifier(summaryKey) != 0
                ? str(summaryKey).toString()
                : "Select a TTF or OTF font file";
        refreshSummary();

        setOnPreferenceClickListener(preference -> openPicker());
    }

    private boolean openPicker() {
        AbstractPreferenceFragment fragment = AbstractPreferenceFragment.instance.get();
        if (fragment == null) return false;

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                "font/ttf",
                "font/otf",
                "application/x-font-ttf",
                "application/x-font-opentype",
        });

        pendingPreference = new WeakReference<>(this);
        fragment.startActivityForResult(intent, READ_FONT_REQUEST_CODE);
        return true;
    }

    public static void handleActivityResult(Context context, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null) return;

        CustomFontFilePreference preference = pendingPreference.get();
        if (preference == null) return;

        Uri uri = data.getData();
        if (uri == null) return;

        if (!isSupportedFontSelection(context, uri)) {
            if (ResourceUtils.getStringIdentifier("morphe_custom_font_invalid_file") != 0) {
                Utils.showToastLong(str("morphe_custom_font_invalid_file"));
            } else {
                Utils.showToastLong("Please select a .ttf or .otf font file.");
            }
            return;
        }

        int readFlag = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        if (readFlag != 0) {
            try {
                context.getContentResolver().takePersistableUriPermission(uri, readFlag);
            } catch (Exception ignored) {
                // Some providers do not support persistable grants.
            }
        }

        // Restart is only needed if custom font rendering is currently active.
        boolean customFontEnabled = Settings.CUSTOM_FONT.get();

        // Suppress automatic restart prompts from the shared preference change listener.
        AbstractPreferenceFragment.settingImportInProgress = true;
        try {
            preference.setting.save(uri.toString());
            preference.refreshSummary();
        } finally {
            AbstractPreferenceFragment.settingImportInProgress = false;
        }

        if (customFontEnabled) {
            AbstractPreferenceFragment.showRestartDialog(context);
        }
    }

    private static boolean isSupportedFontSelection(Context context, Uri uri) {
        String displayName = getDisplayName(context, uri);
        if (displayName != null) {
            return isSupportedFileName(displayName);
        }

        String segment = uri.getLastPathSegment();
        return segment != null && isSupportedFileName(segment);
    }

    private static boolean isSupportedFileName(String name) {
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".ttf") || lower.endsWith(".otf");
    }

    private static String getDisplayName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }

            int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            if (nameColumn < 0) {
                return null;
            }

            return cursor.getString(nameColumn);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void refreshSummary() {
        String configured = setting.get();
        if (configured == null || configured.trim().isEmpty()) {
            setSummary(defaultSummary);
            return;
        }

        Uri uri = Uri.parse(configured);
        String friendlyPath = getFriendlyPath(uri, configured);

        String selectedFontLabel = ResourceUtils.getStringIdentifier("morphe_custom_font_selected_font") != 0
                ? str("morphe_custom_font_selected_font").toString()
                : "Selected font";
        setSummary(selectedFontLabel + " - " + friendlyPath);
    }

    private static String getFriendlyPath(Uri uri, String configured) {
        try {
            // SAF providers usually expose a stable document ID such as "primary:Docs/Font.ttf".
            String documentId = DocumentsContract.getDocumentId(uri);
            int colonIndex = documentId.indexOf(':');
            if (colonIndex >= 0 && colonIndex + 1 < documentId.length()) {
                return documentId.substring(colonIndex + 1);
            }
            if (!documentId.isEmpty()) {
                return documentId;
            }
        } catch (Exception ignored) {
            // Ignore and fall back to URI path parsing.
        }

        String tail = uri.getLastPathSegment();
        if (tail == null || tail.isEmpty()) {
            return configured;
        }

        int colonIndex = tail.indexOf(':');
        if (colonIndex >= 0 && colonIndex + 1 < tail.length()) {
            return tail.substring(colonIndex + 1);
        }

        return tail;
    }
}