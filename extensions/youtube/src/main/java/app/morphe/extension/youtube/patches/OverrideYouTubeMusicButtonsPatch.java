/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.util.Base64;

import androidx.annotation.Nullable;

import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.utils.ProtoNode;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class OverrideYouTubeMusicButtonsPatch {

    private static final String YOUTUBE_MUSIC_PACKAGE_NAME = "com.google.android.apps.youtube.music";
    private static final String MORPHE_MUSIC_PACKAGE_NAME = "app.morphe.android.apps.youtube.music";
    private static final String HIJACK_FLAG = "morphe_hijacked";
    private static final String YOUTUBE_MUSIC_WATCH_URL = "https://music.youtube.com/watch?v=";

    private static String getTargetPackageName() {
        String target = Settings.CUSTOM_MUSIC_PACKAGE_NAME.get().trim();
        return target.isEmpty() ? MORPHE_MUSIC_PACKAGE_NAME : target;
    }

    private static Intent hijackIntent(Intent intent) {
        String target = getTargetPackageName();
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
        if (isPlayStoreLink) {
            data = null;
        }

        String action = launchIntent.getAction();
        ComponentName component = launchIntent.getComponent();

        if (data != null) {
            ComponentName linkHandler = resolveLinkHandler(pm, target, data);
            if (linkHandler == null) {
                // Third-party clients usually only handle regular YouTube Music links.
                Uri watchUri = getWatchUri(data);
                if (watchUri != null) {
                    Logger.printDebug(() -> "Rewriting link to: " + watchUri);
                    data = watchUri;
                    linkHandler = resolveLinkHandler(pm, target, data);
                }
            }

            // Some clients only read the link of a view intent.
            action = Intent.ACTION_VIEW;
            if (linkHandler != null) {
                component = linkHandler;
            }
        }

        intent.setAction(action);
        intent.setComponent(component);
        intent.setPackage(target);
        intent.putExtra(HIJACK_FLAG, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setData(data);

        return intent;
    }

    /**
     * @return The activity of the package that handles the link, or null if none or several do.
     */
    @Nullable
    private static ComponentName resolveLinkHandler(PackageManager pm, String packageName, Uri data) {
        Intent viewIntent = new Intent(Intent.ACTION_VIEW, data);
        viewIntent.setPackage(packageName);
        ResolveInfo resolveInfo = pm.resolveActivity(viewIntent, PackageManager.MATCH_DEFAULT_ONLY);
        if (resolveInfo == null || resolveInfo.activityInfo == null
                || !packageName.equals(resolveInfo.activityInfo.packageName)) {
            return null;
        }

        return new ComponentName(resolveInfo.activityInfo.packageName, resolveInfo.activityInfo.name);
    }

    /**
     * YouTube links to a song as 'vnd.youtube.music://music.youtube.com/?ybp=...',
     * where 'ybp' is a Base64 encoded proto message containing the video id.
     *
     * @return The regular YouTube Music link of the song, or null if the link has no video id.
     */
    @Nullable
    private static Uri getWatchUri(Uri data) {
        try {
            if (!data.isHierarchical()) return null;
            String ybp = data.getQueryParameter("ybp");
            if (ybp == null) return null;

            // Decode both the URL safe and the regular Base64 alphabet.
            byte[] bytes = Base64.decode(ybp.replace('-', '+').replace('_', '/'), Base64.DEFAULT);
            List<ProtoNode> message = ProtoNode.parse(bytes);
            if (message == null) return null;

            for (ProtoNode node : ProtoNode.textNodes(message)) {
                String text = node.getText();
                if (VideoInformation.isVideoId(text)) {
                    return Uri.parse(YOUTUBE_MUSIC_WATCH_URL + text);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getWatchUri failure", ex);
        }

        return null;
    }

    /**
     * Injection point.
     * <p>
     * Before Android 11, YouTube opens a YouTube Music link only if YouTube Music is installed,
     * and otherwise opens the Play Store page of YouTube Music. The target app is reported as
     * YouTube Music, so the link is opened and then redirected to the target app.
     */
    public static ApplicationInfo getApplicationInfo(PackageManager packageManager, String packageName, int flags)
            throws PackageManager.NameNotFoundException {
        if (Settings.OVERRIDE_YOUTUBE_MUSIC_BUTTONS.get() && YOUTUBE_MUSIC_PACKAGE_NAME.equals(packageName)) {
            String target = getTargetPackageName();
            if (packageManager.getLaunchIntentForPackage(target) != null) {
                return packageManager.getApplicationInfo(target, flags);
            }
        }

        return packageManager.getApplicationInfo(packageName, flags);
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
