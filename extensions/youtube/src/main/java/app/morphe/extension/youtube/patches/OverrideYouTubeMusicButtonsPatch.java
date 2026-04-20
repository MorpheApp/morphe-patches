/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class OverrideYouTubeMusicButtonsPatch {

    private static final String YOUTUBE_MUSIC_PACKAGE_NAME = "com.google.android.apps.youtube.music";
    private static final String HIJACK_FLAG = "morphe_hijacked";

    public static Intent overrideSetPackage(Intent intent, String packageName) {
        if (intent == null) return null;

        if (!Settings.OVERRIDE_YOUTUBE_MUSIC_BUTTONS.get()) {
            return intent.setPackage(packageName);
        }

        if (intent.getBooleanExtra(HIJACK_FLAG, false)) {
            return intent;
        }

        if (YOUTUBE_MUSIC_PACKAGE_NAME.equals(packageName)) {
            String target = Settings.MORPHE_MUSIC_PACKAGE_NAME.get().trim();

            if (Utils.isNotEmpty(target) && isAppInstalled(target)) {
                PackageManager pm = Utils.getContext().getPackageManager();
                Intent launchIntent = pm.getLaunchIntentForPackage(target);

                if (launchIntent != null) {
                    intent.setAction(launchIntent.getAction());
                    intent.setComponent(launchIntent.getComponent());
                    intent.setPackage(target);
                    intent.putExtra(HIJACK_FLAG, true);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    return intent;
                }
            }
            return intent.setPackage(null);
        }

        return intent.setPackage(packageName);
    }

    public static Intent overrideSetData(Intent intent, Uri uri) {
        if (intent == null) return null;
        if (uri == null) return intent.setData(null);
        if (!Settings.OVERRIDE_YOUTUBE_MUSIC_BUTTONS.get()) {
            return intent.setData(uri);
        }

        String uriString = uri.toString();
        if (uriString.contains(YOUTUBE_MUSIC_PACKAGE_NAME)) {

            String target = Settings.MORPHE_MUSIC_PACKAGE_NAME.get().trim();
            if (Utils.isNotEmpty(target) && isAppInstalled(target)) {

                Uri musicUri = Uri.parse("https://music.youtube.com/");
                if (!uriString.contains("play.google.com") && !uriString.startsWith("market://")) {
                    musicUri = uri;
                }

                intent.setAction(Intent.ACTION_VIEW);
                intent.setData(musicUri);
                intent.setPackage(target);
                intent.setComponent(null);
                intent.putExtra(HIJACK_FLAG, true);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                return intent;
            }

            intent.setData(Uri.parse("https://music.youtube.com/"));
            intent.setPackage(null);
            intent.setComponent(null);
            return intent;
        }

        return intent.setData(uri);
    }

    private static boolean isAppInstalled(String packageName) {
        try {
            return Utils.getContext().getPackageManager().getLaunchIntentForPackage(packageName) != null;
        } catch (Exception e) {
            return false;
        }
    }
}