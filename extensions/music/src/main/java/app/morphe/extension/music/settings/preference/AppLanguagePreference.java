/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.app.LocaleManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.LocaleList;
import android.preference.Preference;
import android.provider.Settings;
import android.util.AttributeSet;

import java.util.Locale;

import app.morphe.extension.shared.Logger;

/**
 * Opens the per-app language page of the system, since YT Music has no language option of its own.
 * The system only offers that page since Android 13.
 */
@SuppressWarnings({"deprecation", "unused"})
public class AppLanguagePreference extends Preference {

    public AppLanguagePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setPersistent(false);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            setEnabled(false);
            setSummary(str("morphe_music_app_language_summary_unsupported"));
            return;
        }

        // Picking a language recreates the activity, so the summary is always current.
        setSummary(getAppLanguageName(context));
    }

    private static String getAppLanguageName(Context context) {
        LocaleList locales = context.getSystemService(LocaleManager.class).getApplicationLocales();
        if (locales.isEmpty()) {
            return str("morphe_music_app_language_summary_default");
        }

        Locale locale = locales.get(0);
        String name = locale.getDisplayName(locale);
        return name.substring(0, 1).toUpperCase(locale) + name.substring(1);
    }

    @Override
    protected void onClick() {
        Context context = getContext();
        try {
            Intent intent = new Intent(Settings.ACTION_APP_LOCALE_SETTINGS,
                    Uri.fromParts("package", context.getPackageName(), null));
            context.startActivity(intent);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not open app language settings", ex);
        }
    }
}
