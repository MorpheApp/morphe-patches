/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.extension.reddit.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.preference.Preference;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Locale;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;

@SuppressWarnings("deprecation")
public final class CustomFontFilePreference extends Preference {
    public static final int READ_FONT_REQUEST_CODE = 0xC001;

    private static WeakReference<CustomFontFilePreference> pendingPreference = new WeakReference<>(null);

    private final StringSetting filePathSetting;
    private final String defaultSummary;

    public CustomFontFilePreference(Context context, StringSetting filePathSetting) {
        super(context);
        this.filePathSetting = filePathSetting;

        setTitle(str(filePathSetting.key + "_title"));
        setKey(filePathSetting.key);

        this.defaultSummary = str(filePathSetting.key + "_summary");
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
            Utils.showToastLong(str("morphe_custom_font_invalid_file"));
            return;
        }

        final int readFlag = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        if (readFlag != 0) {
            try {
                context.getContentResolver().takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ex) {
                // Some providers do not support persistable grants.
                Logger.printDebug(() -> "Failed to take persistable URI permission", ex);
            }
        }

        // Suppress automatic restart prompts from the shared preference change listener.
        AbstractPreferenceFragment.settingImportInProgress = true;
        try {
            preference.filePathSetting.save(uri.toString());
            preference.refreshSummary();
        } finally {
            AbstractPreferenceFragment.settingImportInProgress = false;
        }

        // Restart is only needed if custom font rendering is currently active.
        if (Settings.CUSTOM_FONT.get()) {
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

    @Nullable
    private static String getDisplayName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(
                uri, null, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }

            int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            if (nameColumn < 0) {
                return null;
            }

            return cursor.getString(nameColumn);
        }
    }

    private void refreshSummary() {
        String configured = filePathSetting.get().trim();
        if (configured.isBlank()) {
            setSummary(defaultSummary);
            return;
        }

        Uri uri = Uri.parse(configured);
        String friendlyPath = getFriendlyPath(uri, configured);

        setSummary(str("morphe_custom_font_selected_font", friendlyPath));
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
        } catch (Exception ex) {
            // Ignore and fall back to URI path parsing.
            Logger.printDebug(() -> "Failed to parse document ID", ex);
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