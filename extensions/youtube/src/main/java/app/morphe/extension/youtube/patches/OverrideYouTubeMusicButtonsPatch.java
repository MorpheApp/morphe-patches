/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class OverrideYouTubeMusicButtonsPatch {

    private static final String YOUTUBE_MUSIC_PACKAGE_NAME = "com.google.android.apps.youtube.music";
    private static final String MORPHE_MUSIC_PACKAGE_NAME = "app.morphe.android.apps.youtube.music";
    private static final String HIJACK_FLAG = "morphe_hijacked";

    private static String getTargetPackage() {
        String customTarget = Settings.CUSTOM_MUSIC_PACKAGE_NAME.get().trim();
        if (customTarget.isEmpty()) {
            return MORPHE_MUSIC_PACKAGE_NAME;
        }
        return customTarget;
    }

    private static Intent hijackIntent(Intent intent) {
        String target = getTargetPackage();
        PackageManager pm = Utils.getContext().getPackageManager();
        Intent launchIntent = pm.getLaunchIntentForPackage(target);

        if (launchIntent == null) {
            intent.setData(Uri.parse("https://music.youtube.com/"));
            intent.setPackage(null);
            intent.setComponent(null);
            return intent;
        }

        Uri data = intent.getData();
        String uriString = data != null ? data.toString() : "";
        boolean isPlayStoreLink = uriString.contains("play.google.com") || uriString.startsWith("market://");

        intent.setAction(launchIntent.getAction());
        intent.setComponent(launchIntent.getComponent());
        intent.setPackage(target);
        intent.putExtra(HIJACK_FLAG, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        if (isPlayStoreLink || data == null) {
            intent.setData(null);
        } else {
            intent.setData(data);
        }

        return intent;
    }

    public static Intent overrideSetPackage(Intent intent, String packageName) {
        if (intent == null) return null;

        if (!Settings.OVERRIDE_YOUTUBE_MUSIC_BUTTONS.get()) {
            return intent.setPackage(packageName);
        }

        if (intent.getBooleanExtra(HIJACK_FLAG, false)) {
            return intent;
        }

        if (YOUTUBE_MUSIC_PACKAGE_NAME.equals(packageName)) {
            return hijackIntent(intent);
        }

        return intent.setPackage(packageName);
    }

    public static Intent overrideSetData(Intent intent, Uri uri) {
        if (intent == null) return null;
        if (uri == null) return intent.setData(null);

        if (!Settings.OVERRIDE_YOUTUBE_MUSIC_BUTTONS.get()) {
            return intent.setData(uri);
        }

        if (intent.getBooleanExtra(HIJACK_FLAG, false)) {
            return intent;
        }

        String uriString = uri.toString();
        if (uriString.contains(YOUTUBE_MUSIC_PACKAGE_NAME) || uriString.contains("music.youtube.com")) {
            intent.setData(uri);
            return hijackIntent(intent);
        }

        return intent.setData(uri);
    }

    public static Intent overrideSetComponent(Intent intent, ComponentName component) {
        if (intent == null) return null;

        if (!Settings.OVERRIDE_YOUTUBE_MUSIC_BUTTONS.get()) {
            return intent.setComponent(component);
        }

        if (intent.getBooleanExtra(HIJACK_FLAG, false)) {
            return intent;
        }

        if (component != null && YOUTUBE_MUSIC_PACKAGE_NAME.equals(component.getPackageName())) {
            intent.setComponent(component);
            return hijackIntent(intent);
        }

        return intent.setComponent(component);
    }
}
