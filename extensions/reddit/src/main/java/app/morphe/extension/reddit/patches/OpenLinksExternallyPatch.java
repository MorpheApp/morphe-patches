package app.morphe.extension.reddit.patches;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import java.lang.ref.WeakReference;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public class OpenLinksExternallyPatch {
    private static WeakReference<Activity> activityRef = new WeakReference<>(null);

    public static boolean patchEnabled;

    public static void setPatchEnabled() {
        patchEnabled = true;
    }

    /**
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

    public static void openLinksExternally(String uri) {
        if (uri != null && Settings.OPEN_LINKS_EXTERNALLY.get()) {
            Activity activity = activityRef.get();
            if (activity != null && !activity.isDestroyed()) {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setData(Uri.parse(uri));
                    activity.startActivity(intent);
                    Utils.runOnMainThreadDelayed(() -> {
                        activity.finish();
                        activityRef = new WeakReference<>(null);
                    }, 100);
                } catch (Exception e) {
                    Logger.printException(() -> "Can not open URL: " + uri, e);
                }
            }
        }
    }

    public static void setActivity(Activity activity) {
        activityRef = new WeakReference<>(activity);
    }
}
