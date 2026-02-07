package app.morphe.extension.youtube.patches;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.spoof.SpoofAppVersionPatch;
import app.morphe.extension.youtube.shared.PlayerType;

@SuppressWarnings("unused")
public class FIxLikeButtonPatch {

    private static final String THEMED_LIKE_ANIMATIONS_PREFIX =
            "https://www.gstatic.com/youtube/img/lottie/custom_animated_like_icon/";
    private static final String THEMED_LIKE_ANIMATIONS_LEGACY_ICON_RESIZED_URL_PREFIX =
            "https://cdn.jsdelivr.net/gh/MorpheApp/morphe-patches@dev/patches/src/main/resources/actionbar/assets/";
    private static final String THEMED_LIKE_ANIMATIONS_LEGACY_DARK_ICON_RESIZED_URL =
            THEMED_LIKE_ANIMATIONS_LEGACY_ICON_RESIZED_URL_PREFIX + "animated_like_icon_dark_v4.json";
    private static final String THEMED_LIKE_ANIMATIONS_LEGACY_LIGHT_ICON_RESIZED_URL =
            THEMED_LIKE_ANIMATIONS_LEGACY_ICON_RESIZED_URL_PREFIX + "animated_like_icon_light_v4.json";

    /**
     * The action button icon changes based on the server response.
     * It cannot be turned on or off with an experimental flag on the client side.
     * <p>
     * YouTube 20.39+ uses a bold icon in the action bar, while YouTube 20.38 uses a legacy icon in the action bar.
     */
    private static final boolean USE_LEGACY_ICON = !VersionCheckPatch.IS_20_39_OR_GREATER
            || SpoofAppVersionPatch.isSpoofingToLessThan("20.38.00");

    /**
     * Injection point.
     */
    public static String fixThemedLikeAnimations(String url) {
        if (USE_LEGACY_ICON && url != null && url.startsWith(THEMED_LIKE_ANIMATIONS_PREFIX)) {
            String finalUrl =
                    // Fullscreen quick actions always use a white icon (animated_like_icon_dark_v4.json)
                    PlayerType.getCurrent() == PlayerType.WATCH_WHILE_FULLSCREEN
                            // If not fullscreen, follows the app theme.
                            || Utils.isDarkModeEnabled()
                            ? THEMED_LIKE_ANIMATIONS_LEGACY_DARK_ICON_RESIZED_URL
                            : THEMED_LIKE_ANIMATIONS_LEGACY_LIGHT_ICON_RESIZED_URL;

            Logger.printDebug(() -> "Fix themed like animation from " + url + "\n to " + finalUrl);

            return finalUrl;
        }

        return url;
    }

}

