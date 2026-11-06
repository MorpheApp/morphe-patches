/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.settings.preference;

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

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;
import app.morphe.extension.youtube.patches.CustomSeekbarThumb;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings({"unused", "deprecation"})
public class CustomSeekbarThumbPreference extends Preference {

    public static final int REQUEST_CODE = 0xC002;

    private static WeakReference<CustomSeekbarThumbPreference> shownPreference = new WeakReference<>(null);

    public CustomSeekbarThumbPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        initialize();
    }

    public CustomSeekbarThumbPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initialize();
    }

    public CustomSeekbarThumbPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        initialize();
    }

    public CustomSeekbarThumbPreference(Context context) {
        super(context);
        initialize();
    }

    private void initialize() {
        shownPreference = new WeakReference<>(this);
        setTitle(str("morphe_custom_seekbar_thumb_file_title"));
        updateSummary();
        setOnPreferenceClickListener(preference -> {
            openPicker();
            return true;
        });
    }

    public void updateSummary() {
        String path = Settings.CUSTOM_SEEKBAR_THUMB_FILE_PATH.get();
        if (path.isEmpty()) {
            setSummary(str("morphe_custom_seekbar_thumb_file_summary"));
        } else {
            File file = new File(path);
            String name = file.getName();
            setSummary(str("morphe_custom_seekbar_thumb_file_selected", name));
        }
    }

    public static void openPicker() {
        AbstractPreferenceFragment fragment = AbstractPreferenceFragment.instance.get();
        if (fragment == null) {
            Logger.printException(() -> "No settings fragment to open the file picker");
            return;
        }

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                "image/png",
                "image/gif",
                "image/jpeg",
                "image/webp"
        });
        try {
            fragment.startActivityForResult(intent, REQUEST_CODE);
        } catch (ActivityNotFoundException ex) {
            Logger.printException(() -> "No file picker found", ex);
        }
    }

    public static void handleActivityResult(Context context, int resultCode, @Nullable Intent data) {
        try {
            if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
                return;
            }

            Uri uri = data.getData();
            String fileName = getFileName(context, uri);
            if (fileName == null || !isSupportedImageFile(fileName)) {
                Utils.showToastLong(str("morphe_custom_seekbar_thumb_invalid_file"));
                return;
            }

            File targetFile = copyToInternalStorage(context, uri, fileName);
            if (targetFile == null || !targetFile.exists()) {
                Utils.showToastLong(str("morphe_custom_seekbar_thumb_invalid_file"));
                return;
            }

            Settings.CUSTOM_SEEKBAR_THUMB_FILE_PATH.save(targetFile.getAbsolutePath());

            CustomSeekbarThumbPreference preference = shownPreference.get();
            if (preference != null) {
                preference.updateSummary();
            }

            CustomSeekbarThumb.reload();
        } catch (Exception ex) {
            Logger.printException(() -> "handleActivityResult failure", ex);
        }
    }

    private static boolean isSupportedImageFile(String name) {
        String lower = name.toLowerCase(Locale.ENGLISH);
        return lower.endsWith(".png") || lower.endsWith(".gif")
                || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".webp");
    }

    @Nullable
    private static File copyToInternalStorage(Context context, Uri uri, String originalName) {
        String ext = "";
        int dot = originalName.lastIndexOf('.');
        if (dot >= 0) {
            ext = originalName.substring(dot);
        }
        File target = new File(context.getFilesDir(), "custom_seekbar_thumb" + ext);
        File temp = new File(context.getFilesDir(), "custom_seekbar_thumb_temp" + ext);
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(temp)) {
            if (in == null) return null;
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
            }
            out.flush();
            if (target.exists()) {
                target.delete();
            }
            if (!temp.renameTo(target)) {
                return null;
            }
            return target;
        } catch (Exception ex) {
            Logger.printException(() -> "copyToInternalStorage failure", ex);
            return null;
        } finally {
            if (temp.exists()) {
                temp.delete();
            }
        }
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
