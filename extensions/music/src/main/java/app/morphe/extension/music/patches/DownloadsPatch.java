/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.facebook.litho.ComponentHost;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.music.patches.utils.VideoUtils;

@SuppressWarnings("unused")
public final class DownloadsPatch {

    private static final String ELEMENTS_SENDER_VIEW = "com.google.android.libraries.youtube.rendering.elements.sender_view";
    private static final String EXTERNAL_DOWNLOADER_LAUNCHED = "external_downloader_launched";

    private static String downloadButtonLabel = "";

    /**
     * Injection point.
     */
    public static CharSequence onLithoTextLoaded(@NonNull Object conversionContext, @NonNull CharSequence original) {
        try {
            if (Settings.EXTERNAL_DOWNLOADER_ACTION_BUTTON.get() &&
                    downloadButtonLabel.isEmpty() &&
                    conversionContext.toString().contains("music_download_button.")) {

                downloadButtonLabel = original.toString();
                Logger.printDebug(() -> "DownloadsPatch: Captured download button label: " + downloadButtonLabel);
            }
        } catch (Exception ignored) {
        }
        return original;
    }

    /**
     * Injection point.
     */
    public static boolean inAppDownloadButtonOnClick(@Nullable Map<Object, Object> map) {
        try {
            if (!Settings.EXTERNAL_DOWNLOADER_ACTION_BUTTON.get() || downloadButtonLabel.isEmpty()) {
                return false;
            }

            if (map != null && map.get(ELEMENTS_SENDER_VIEW) instanceof ComponentHost componentHost) {

                String description = componentHost.getContentDescription() != null ?
                        componentHost.getContentDescription().toString() : "";

                if (downloadButtonLabel.equals(description)) {
                    if (!map.containsKey(EXTERNAL_DOWNLOADER_LAUNCHED)) {
                        map.put(EXTERNAL_DOWNLOADER_LAUNCHED, Boolean.TRUE);

                        Utils.runOnMainThreadDelayed(VideoUtils::launchExternalDownloader, 0);
                    }
                    return true;
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "inAppDownloadButtonOnClick failure", ex);
        }
        return false;
    }
}
