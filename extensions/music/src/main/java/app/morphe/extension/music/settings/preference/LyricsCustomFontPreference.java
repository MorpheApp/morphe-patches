/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.preference.Preference;
import android.provider.OpenableColumns;
import android.util.AttributeSet;
import android.util.Pair;
import android.view.WindowManager;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.music.patches.lyrics.ui.LyricsFontManager;
import app.morphe.extension.music.patches.lyrics.ui.LyricsFontManager.FontImportResult;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;
import app.morphe.extension.shared.ui.CustomDialog;

/**
 * Preference row in the Lyrics Display section allowing the user to upload a custom
 * {@code .ttf} / {@code .otf} font file (e.g., SF Pro Bold, Inter Bold, Inter ExtraBold)
 * or revert to the default font without restarting the app.
 */
@SuppressWarnings({"unused", "deprecation"})
public class LyricsCustomFontPreference extends Preference {

    public static final int PICK_FONT_REQUEST_CODE = 0xC025;

    private static final AtomicBoolean IMPORT_IN_FLIGHT = new AtomicBoolean(false);
    private static WeakReference<LyricsCustomFontPreference> shownPreference = new WeakReference<>(null);

    public LyricsCustomFontPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        initialize();
    }

    public LyricsCustomFontPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initialize();
    }

    public LyricsCustomFontPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        initialize();
    }

    public LyricsCustomFontPreference(Context context) {
        super(context);
        initialize();
    }

    private void initialize() {
        shownPreference = new WeakReference<>(this);
        setTitle(str("morphe_music_lyrics_custom_font_title"));
        updateSummary();
        setOnPreferenceClickListener(preference -> {
            showFontDialog(getContext());
            return true;
        });
    }

    public void updateSummary() {
        String activeName = LyricsFontManager.activeFontSummaryName();
        setSummary(activeName.isEmpty()
                ? str("morphe_music_lyrics_custom_font_summary_default")
                : str("morphe_music_lyrics_custom_font_summary_active", activeName));
    }

    private void showFontDialog(Context context) {
        boolean hasActiveFont = !LyricsFontManager.activeFontSummaryName().isEmpty();
        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                context,
                str("morphe_music_lyrics_custom_font_title"),
                str("morphe_music_lyrics_custom_font_dialog_message"),
                null,
                str("morphe_music_lyrics_custom_font_choose"),
                this::openPicker,
                () -> {},
                hasActiveFont ? str("morphe_music_lyrics_custom_font_reset") : null,
                hasActiveFont ? () -> {
                    LyricsFontManager.clearCustomFont(context);
                    updateSummary();
                    Utils.showToastShort(str("morphe_music_lyrics_custom_font_cleared"));
                } : null,
                true
        );
        try {
            dialogPair.first.show();
        } catch (WindowManager.BadTokenException ex) {
            Logger.printDebug(() -> "Could not show custom font dialog", ex);
        }
    }

    private void openPicker() {
        AbstractPreferenceFragment fragment = AbstractPreferenceFragment.instance.get();
        if (fragment == null) {
            Logger.printException(() -> "No settings fragment available to open lyrics font picker");
            return;
        }
        // Document providers do not consistently tag .ttf / .otf files with font/* MIME types,
        // so allow picking any openable file and validate its SFNT header on import.
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            fragment.startActivityForResult(intent, PICK_FONT_REQUEST_CODE);
        } catch (ActivityNotFoundException ex) {
            Logger.printException(() -> "No document picker found", ex);
        }
    }

    /** Handles the result of {@link Intent#ACTION_OPEN_DOCUMENT} from {@link MusicPreferenceFragment}. */
    public static void handleActivityResult(@Nullable Context context, int resultCode, @Nullable Intent data) {
        try {
            if (context == null || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
                return;
            }
            if (!IMPORT_IN_FLIGHT.compareAndSet(false, true)) {
                return;
            }
            Uri uri = data.getData();
            Context appContext = context.getApplicationContext();
            Utils.runOnBackgroundThread(() -> {
                try {
                    String fileName = queryFileName(appContext, uri);
                    FontImportResult result = LyricsFontManager.importFontFile(appContext, uri, fileName);
                    Utils.runOnMainThread(() -> {
                        if (!result.success()) {
                            Utils.showToastLong(str("morphe_music_lyrics_custom_font_invalid"));
                            return;
                        }
                        LyricsCustomFontPreference pref = shownPreference.get();
                        if (pref != null) {
                            pref.updateSummary();
                        }
                        Utils.showToastShort(str("morphe_music_lyrics_custom_font_applied", result.displayLabel()));
                    });
                } catch (Exception ex) {
                    Logger.printException(() -> "LyricsCustomFontPreference background import failure", ex);
                    Utils.runOnMainThread(() -> Utils.showToastLong(str("morphe_music_lyrics_custom_font_invalid")));
                } finally {
                    IMPORT_IN_FLIGHT.set(false);
                }
            });
        } catch (Exception ex) {
            IMPORT_IN_FLIGHT.set(false);
            Logger.printException(() -> "LyricsCustomFontPreference.handleActivityResult failure", ex);
        }
    }

    @Nullable
    private static String queryFileName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not query font file display name: " + uri, ex);
        }
        return uri.getLastPathSegment();
    }
}
