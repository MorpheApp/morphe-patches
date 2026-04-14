/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.content.Intent;
import android.net.Uri;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class OverrideYouTubeMusicActionsPatch {

    private static final String YOUTUBE_MUSIC_PACKAGE_NAME = "com.google.android.apps.youtube.music";

    public static Intent overrideSetPackage(Intent intent, String packageName) {
        if (intent == null) return null;

        if (!Settings.OVERRIDE_YOUTUBE_MUSIC_BUTTON.get()) {
            return intent.setPackage(packageName);
        }

        if (YOUTUBE_MUSIC_PACKAGE_NAME.equals(packageName)) {
            String target = Settings.MORPHE_MUSIC_PACKAGE_NAME.get();
            if (Utils.isNotEmpty(target) && Utils.isPackageEnabled(target)) {
                return intent.setPackage(target);
            }

            return intent.setPackage(null);
        }

        return intent.setPackage(packageName);
    }

    public static Intent overrideSetData(Intent intent, Uri uri) {
        if (intent == null) return null;
        if (uri == null) return intent.setData(null);
        if (!Settings.OVERRIDE_YOUTUBE_MUSIC_BUTTON.get()) {
            return intent.setData(uri);
        }

        String uriString = uri.toString();
        if (uriString.contains(YOUTUBE_MUSIC_PACKAGE_NAME)) {
            if (uriString.startsWith("market://") || uriString.contains("play.google.com/store/apps")) {

                intent.setData(Uri.parse("https://music.youtube.com/"));

                String target = Settings.MORPHE_MUSIC_PACKAGE_NAME.get();
                if (Utils.isNotEmpty(target) && Utils.isPackageEnabled(target)) {

                    intent.setPackage(target);
                } else {
                    intent.setPackage(null);
                }
                return intent;
            }
        }

        return intent.setData(uri);
    }
}