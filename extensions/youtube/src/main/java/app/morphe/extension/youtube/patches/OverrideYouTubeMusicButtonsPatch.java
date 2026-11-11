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

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class OverrideYouTubeMusicButtonsPatch {

    private static final String YOUTUBE_MUSIC_PACKAGE_NAME = "com.google.android.apps.youtube.music";
    private static final String MORPHE_MUSIC_PACKAGE_NAME = "app.morphe.android.apps.youtube.music";
    private static final String HIJACK_FLAG = "morphe_hijacked";
    private static final String YOUTUBE_MUSIC_WATCH_URL = "https://music.youtube.com/watch?v=";
    private static final int VIDEO_ID_LENGTH = 11;

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
     * where 'ybp' is a Base64 encoded protobuf message containing the video id.
     *
     * @return The regular YouTube Music link of the song, or null if the link has no video id.
     */
    @Nullable
    private static Uri getWatchUri(Uri data) {
        try {
            if (!data.isHierarchical()) return null;
            String ybp = data.getQueryParameter("ybp");
            if (ybp == null) return null;

            byte[] bytes = Base64.decode(ybp.replace('-', '+').replace('_', '/'), Base64.DEFAULT);
            String videoId = findVideoId(bytes, 0, bytes.length, 0);
            return videoId == null ? null : Uri.parse(YOUTUBE_MUSIC_WATCH_URL + videoId);
        } catch (Exception ex) {
            Logger.printException(() -> "getWatchUri failure", ex);
            return null;
        }
    }

    /**
     * @return The first length delimited field of the protobuf message that is a video id.
     */
    @Nullable
    private static String findVideoId(byte[] bytes, int start, int end, int depth) {
        if (depth > 8) return null;

        int position = start;
        while (position < end) {
            long[] tag = readVarint(bytes, position, end);
            if (tag == null) return null;
            position = (int) tag[1];

            switch ((int) (tag[0] & 0x7)) {
                case 0 -> { // Varint.
                    long[] value = readVarint(bytes, position, end);
                    if (value == null) return null;
                    position = (int) value[1];
                }
                case 1 -> position += 8; // Fixed 64 bit.
                case 5 -> position += 4; // Fixed 32 bit.
                case 2 -> { // Length delimited.
                    long[] length = readVarint(bytes, position, end);
                    if (length == null || length[0] < 0 || length[0] > end - length[1]) return null;
                    int fieldStart = (int) length[1];
                    int fieldEnd = fieldStart + (int) length[0];

                    if (isVideoId(bytes, fieldStart, fieldEnd)) {
                        return new String(bytes, fieldStart, VIDEO_ID_LENGTH);
                    }
                    String videoId = findVideoId(bytes, fieldStart, fieldEnd, depth + 1);
                    if (videoId != null) return videoId;

                    position = fieldEnd;
                }
                default -> {
                    return null;
                }
            }
        }

        return null;
    }

    /**
     * @return The value and the position after it, or null if the varint is malformed.
     */
    @Nullable
    private static long[] readVarint(byte[] bytes, int position, int end) {
        long value = 0;
        for (int shift = 0; shift < 64 && position < end; shift += 7) {
            byte b = bytes[position++];
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return new long[]{value, position};
            }
        }

        return null;
    }

    private static boolean isVideoId(byte[] bytes, int start, int end) {
        if (end - start != VIDEO_ID_LENGTH) return false;

        for (int i = start; i < end; i++) {
            char c = (char) bytes[i];
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_')) {
                return false;
            }
        }

        return true;
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
