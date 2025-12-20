package app.morphe.extension.shared.settings;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static app.morphe.extension.shared.patches.CustomBrandingPatch.BrandingTheme;
import static app.morphe.extension.shared.settings.Setting.parent;

import app.morphe.extension.shared.Logger;

/**
 * Settings shared across multiple apps.
 * <p>
 * To ensure this class is loaded when the UI is created, app specific setting bundles should extend
 * or reference this class.
 */
public class BaseSettings {
    public static final BooleanSetting DEBUG = new BooleanSetting("morphe_debug", FALSE);
    public static final BooleanSetting DEBUG_STACKTRACE = new BooleanSetting("morphe_debug_stacktrace", FALSE, parent(DEBUG));
    public static final BooleanSetting DEBUG_TOAST_ON_ERROR = new BooleanSetting("morphe_debug_toast_on_error", TRUE, "morphe_debug_toast_on_error_user_dialog_message");

    public static final IntegerSetting CHECK_ENVIRONMENT_WARNINGS_ISSUED = new IntegerSetting("morphe_check_environment_warnings_issued", 0, true, false);

    public static final EnumSetting<AppLanguage> MORPHE_LANGUAGE = new EnumSetting<>("morphe_language", AppLanguage.DEFAULT, true, "morphe_language_user_dialog_message");

    /**
     * Use the icons declared in the preferences created during patching. If no icons or styles are declared then this setting does nothing.
     */
    public static final BooleanSetting SHOW_MENU_ICONS = new BooleanSetting("morphe_show_menu_icons", TRUE, true);
    /**
     * Do not use this setting directly. Instead use {@link app.morphe.extension.shared.Utils#appIsUsingBoldIcons()}
     */
    public static final BooleanSetting SETTINGS_DISABLE_BOLD_ICONS = new BooleanSetting("morphe_settings_disable_bold_icons", FALSE, true);

    public static final BooleanSetting SETTINGS_SEARCH_HISTORY = new BooleanSetting("morphe_settings_search_history", TRUE, true);
    public static final StringSetting SETTINGS_SEARCH_ENTRIES = new StringSetting("morphe_settings_search_entries", "");

    /**
     * The first time the app was launched with no previous app data (either a clean install, or after wiping app data).
     */
    public static final LongSetting FIRST_TIME_APP_LAUNCHED = new LongSetting("morphe_last_time_app_was_launched", -1L, false, false);

    //
    // Settings shared by YouTube and YouTube Music.
    //

    public static final BooleanSetting SPOOF_VIDEO_STREAMS = new BooleanSetting("morphe_spoof_video_streams", TRUE, true, "morphe_spoof_video_streams_user_dialog_message");
    public static final BooleanSetting SPOOF_STREAMING_DATA_STATS_FOR_NERDS = new BooleanSetting("morphe_spoof_streaming_data_stats_for_nerds", TRUE, parent(SPOOF_VIDEO_STREAMS));

    public static final BooleanSetting SANITIZE_SHARED_LINKS = new BooleanSetting("morphe_sanitize_sharing_links", TRUE);
    public static final BooleanSetting REPLACE_MUSIC_LINKS_WITH_YOUTUBE = new BooleanSetting("morphe_replace_music_with_youtube", FALSE);

    public static final BooleanSetting CHECK_WATCH_HISTORY_DOMAIN_NAME = new BooleanSetting("morphe_check_watch_history_domain_name", TRUE, false, false);

    public static final EnumSetting<BrandingTheme> CUSTOM_BRANDING_ICON = new EnumSetting<>("morphe_custom_branding_icon", BrandingTheme.BLACK, true);
    public static final IntegerSetting CUSTOM_BRANDING_NAME = new IntegerSetting("morphe_custom_branding_name", 1, true);

    public static final StringSetting DISABLED_FEATURE_FLAGS = new StringSetting("morphe_disabled_feature_flags", "", true, parent(DEBUG));

    static {
        final long now = System.currentTimeMillis();

        if (FIRST_TIME_APP_LAUNCHED.get() < 0) {
            Logger.printInfo(() -> "First launch of installation with no prior app data");
            FIRST_TIME_APP_LAUNCHED.save(now);
        }
    }
}
