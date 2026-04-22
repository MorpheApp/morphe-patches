/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.extension.reddit.patches;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import java.lang.ref.WeakReference;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public class OpenLinksExternallyPatch {
    private static WeakReference<Activity> activityRef = new WeakReference<>(null);

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point.
     */
    public static void openLinksExternally(String uri) {
        if (uri != null && Settings.OPEN_LINKS_EXTERNALLY.get()) {
            Activity activity = activityRef.get();
            if (activity != null && !activity.isDestroyed()) {
                try {
                    activityRef = new WeakReference<>(null);
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setData(Uri.parse(uri));
                    activity.startActivity(intent);
                    activity.finish();
                } catch (Exception e) {
                    Logger.printException(() -> "Can not open URL: " + uri, e);
                }
            }
        }
    }

    /**
     * Injection point.
     * <p>
     * Override 'CustomTabsIntent', in order to open links in the default browser.
     * Instead of doing CustomTabsActivity,
     *
     * @param activity The activity, to start an Intent.
     * @param uri      The URL to be opened in the default browser.
     */
    public static boolean openLinksExternally(Activity activity, Uri uri) {
        try {
            if (activity != null && uri != null && Settings.OPEN_LINKS_EXTERNALLY.get()) {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setData(uri);
                activity.startActivity(intent);
                return true;
            }
        } catch (Exception e) {
            Logger.printException(() -> "Can not open URL: " + uri, e);
        }
        return false;
    }

    /**
     * Injection point.
     */
    public static void setActivity(Activity activity) {
        activityRef = new WeakReference<>(activity);
    }
}
