package app.morphe.extension.shared.settings;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static app.morphe.extension.shared.settings.Setting.migrateOldSettingToNew;
import static app.morphe.extension.shared.settings.Setting.parent;
import static app.morphe.extension.shared.settings.Setting.parentsAny;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.BaseAppRefreshRatePatch.AppRefreshType;
import app.morphe.extension.shared.patches.BaseAppRefreshRatePatch.RefreshRateType;
import app.morphe.extension.shared.patches.CustomBrandingPatch;
import app.morphe.extension.shared.patches.CustomBrandingPatch.BrandingTheme;
import app.morphe.extension.shared.patches.CustomBrandingPatch.NotificationIconTheme;
import app.morphe.extension.shared.patches.PoTokenProviderPatch.PoTokenProviderAvailability;
import app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch.JavaScriptClientAvailability;
import app.morphe.extension.shared.spoof.js.JavaScriptVariant;
import app.morphe.extension.shared.theme.ThemeColorPatch.ThemeColorChangeForegroundAvailability;
import app.morphe.extension.shared.theme.ThemeColorPatch.ThemeColorCustomAvailability;
import app.morphe.extension.shared.theme.ThemeColorPatch.ThemeColorDark;
import app.morphe.extension.shared.theme.ThemeColorPatch.ThemeColorLight;

/**
 * Settings shared by YouTube and YouTube Music.
 * <p>
 * To ensure this class is loaded when the UI is created, app specific setting bundles should extend
 * or reference this class.
 */
public class SharedYouTubeSettings extends BaseSettings {

    public static final BooleanSetting SETTINGS_INITIALIZED = new BooleanSetting("morphe_settings_initialized", FALSE, false, false);

    public static final BooleanSetting SETTINGS_SEARCH_HISTORY = new BooleanSetting("morphe_settings_search_history", TRUE, true);
    public static final StringSetting SETTINGS_SEARCH_ENTRIES = new StringSetting("morphe_settings_search_entries", "");

    // Network proxy
    // There are multiple endpoint calls due to the spoof video streams patch. Place the proxy settings first.
    public static final BooleanSetting PROXY_ENABLED = new BooleanSetting("morphe_proxy_enabled", FALSE, true);
    public static final StringSetting PROXY_HOST = new StringSetting("morphe_proxy_host", "", true, parent(PROXY_ENABLED));
    public static final IntegerSetting PROXY_PORT = new IntegerSetting("morphe_proxy_port", 8080, true, parent(PROXY_ENABLED));
    public static final BooleanSetting PROXY_HTTPS = new BooleanSetting("morphe_proxy_https", FALSE, true, parent(PROXY_ENABLED));
    public static final BooleanSetting PROXY_AUTH_ENABLED = new BooleanSetting("morphe_proxy_auth_enabled", FALSE, true, parent(PROXY_ENABLED));
    public static final StringSetting PROXY_AUTH_USERNAME = new StringSetting("morphe_proxy_auth_username", "", true, parent(PROXY_AUTH_ENABLED));
    public static final StringSetting PROXY_AUTH_PASSWORD = new StringSetting("morphe_proxy_auth_password", "", true, false, null, parent(PROXY_AUTH_ENABLED));
    public static final BooleanSetting PROXY_ALLOW_DIRECT_FALLBACK = new BooleanSetting("morphe_proxy_allow_direct_fallback", FALSE, true, parent(PROXY_ENABLED));

    public static final BooleanSetting DISABLE_DRC_AUDIO = new BooleanSetting("morphe_disable_drc_audio", FALSE, true);
    public static final BooleanSetting FORCE_ORIGINAL_AUDIO = new BooleanSetting("morphe_force_original_audio", TRUE, true);

    // Ads
    public static final BooleanSetting HIDE_FULLSCREEN_ADS = new BooleanSetting("morphe_hide_fullscreen_ads", TRUE);

    public static final BooleanSetting REMOVE_VIEWER_DISCRETION_DIALOG = new BooleanSetting("morphe_remove_viewer_discretion_dialog", FALSE, true);

    public static final BooleanSetting CHECK_WATCH_HISTORY_DOMAIN_NAME = new BooleanSetting("morphe_check_watch_history_domain_name", TRUE, false, false);

    // Theme
    public static final EnumSetting<ThemeColorDark> THEME_COLOR_DARK = new EnumSetting<>("morphe_theme_color_dark", ThemeColorDark.PURE_BLACK, true);
    public static final EnumSetting<ThemeColorLight> THEME_COLOR_LIGHT = new EnumSetting<>("morphe_theme_color_light", ThemeColorLight.WHITE, true);
    public static final StringSetting THEME_COLOR_DARK_CUSTOM = new StringSetting("morphe_theme_color_dark_custom", "#0F0F0F", true, new ThemeColorCustomAvailability(THEME_COLOR_DARK));
    public static final StringSetting THEME_COLOR_LIGHT_CUSTOM = new StringSetting("morphe_theme_color_light_custom", "#FFFFFF", true, new ThemeColorCustomAvailability(THEME_COLOR_LIGHT));
    public static final BooleanSetting THEME_COLOR_CHANGE_FOREGROUND = new BooleanSetting("morphe_theme_color_change_foreground", false, true, new ThemeColorChangeForegroundAvailability());
    public static final BooleanSetting THEME_LAST_USED_DARK_MODE = new BooleanSetting("morphe_theme_last_used_dark_mode", Utils.isDarkModeEnabled(), false, false);

    // Custom branding
    public static final EnumSetting<BrandingTheme> CUSTOM_BRANDING_ICON = new EnumSetting<>("morphe_custom_branding_icon", CustomBrandingPatch.getDefaultIconStyle(), true);
    public static final EnumSetting<NotificationIconTheme> CUSTOM_BRANDING_NOTIFICATION_ICON = new EnumSetting<>("morphe_custom_branding_notification_icon", NotificationIconTheme.FOLLOW, true);
    public static final IntegerSetting CUSTOM_BRANDING_NAME = new IntegerSetting("morphe_custom_branding_name", CustomBrandingPatch.getDefaultAppNameIndex(), true);

    // Miscellaneous
    public static final BooleanSetting DEBUG_PROTOBUFFER = new BooleanSetting("morphe_debug_protobuffer", FALSE, false, "morphe_debug_protobuffer_user_dialog_message", parent(DEBUG));
    public static final BooleanSetting DEBUG_SPANNABLE = new BooleanSetting("morphe_debug_spannable", FALSE, parent(DEBUG));
    public static final StringSetting DISABLED_FEATURE_FLAGS = new StringSetting("morphe_disabled_feature_flags", "", true, parent(DEBUG));
    public static final StringSetting FORCED_FEATURE_FLAGS = new StringSetting("morphe_forced_feature_flags", "", true, parent(DEBUG));
    public static final StringSetting FEATURE_FLAGS_BISECT = new StringSetting("morphe_feature_flags_bisect", "", false, false, null, parent(DEBUG));
    public static final BooleanSetting DISABLE_QUIC_PROTOCOL = new BooleanSetting("morphe_disable_quic_protocol", FALSE, true);
    public static final BooleanSetting SANITIZE_SHARING_LINKS = new BooleanSetting("morphe_sanitize_sharing_links", TRUE);
    public static final BooleanSetting REPLACE_MUSIC_LINKS_WITH_YOUTUBE = new BooleanSetting("morphe_replace_music_with_youtube", FALSE);
    public static final BooleanSetting REPLACE_LINKS_WITH_SHORTENER = new BooleanSetting("morphe_replace_links_with_shortener", FALSE);

    // Spoof video streams
    public static final BooleanSetting SPOOF_VIDEO_STREAMS = new BooleanSetting("morphe_spoof_video_streams", TRUE, true, "morphe_spoof_video_streams_user_dialog_message");
    public static final BooleanSetting SPOOF_VIDEO_STREAMS_STATS_FOR_NERDS = new BooleanSetting("morphe_spoof_video_streams_stats_for_nerds", TRUE, parent(SPOOF_VIDEO_STREAMS));
    public static final EnumSetting<JavaScriptVariant> SPOOF_VIDEO_STREAMS_PLAYER_JS_VARIANT = new EnumSetting<>("morphe_spoof_video_streams_player_js_variant", JavaScriptVariant.HOUSE_BRAND, true, new JavaScriptClientAvailability());
    public static final StringSetting SPOOF_VIDEO_STREAMS_PLAYER_JS_HASH_VALUE = new StringSetting("morphe_spoof_video_streams_player_js_hash_value", "", true, false);
    public static final LongSetting SPOOF_VIDEO_STREAMS_PLAYER_JS_SAVED_MILLISECONDS = new LongSetting("morphe_spoof_video_streams_player_js_saved_milliseconds", -1L, false, false);
    public static final StringSetting OAUTH2_REFRESH_TOKEN = new StringSetting("morphe_oauth2_refresh_token", "", false, false);
    public static final StringSetting SPOOF_VIDEO_STREAMS_CLIENT_IDS = new StringSetting("morphe_spoof_video_streams_clent_id", "", false, false);

    // PoToken provider
    public static final BooleanSetting EXTERNAL_POTOKEN_PROVIDER = new BooleanSetting("morphe_external_potoken_provider", FALSE, true, "morphe_external_potoken_provider_user_dialog_message", new PoTokenProviderAvailability());

    // External downloads
    public static final BooleanSetting EXTERNAL_DOWNLOADER = new BooleanSetting("morphe_external_downloader", FALSE);
    public static final BooleanSetting EXTERNAL_DOWNLOADER_ACTION_BUTTON = new BooleanSetting("morphe_external_downloader_action_button", FALSE);
    public static final BooleanSetting EXTERNAL_DOWNLOADER_FLYOUT_MENU = new BooleanSetting("morphe_external_downloader_flyout_menu", FALSE, parent(EXTERNAL_DOWNLOADER_ACTION_BUTTON));
    public static final StringSetting EXTERNAL_DOWNLOADER_PACKAGE_NAME = new StringSetting("morphe_external_downloader_name", "com.deniscerri.ytdl" /* YTDLnis */, parentsAny(EXTERNAL_DOWNLOADER, EXTERNAL_DOWNLOADER_ACTION_BUTTON));

    // Spoof app version
    public static final BooleanSetting SPOOF_APP_VERSION = new BooleanSetting("morphe_spoof_app_version", FALSE, true, "morphe_spoof_app_version_user_dialog_message");
    public static final StringSetting SPOOF_APP_VERSION_TARGET = new StringSetting("morphe_spoof_app_version_target", getDefaultSpoofAppVersionTarget(), true, parent(SPOOF_APP_VERSION));

    public static final StringSetting APP_REFRESH_RATE = new StringSetting("morphe_app_refresh_rate", "DEFAULT", true);
    public static final EnumSetting<AppRefreshType> APP_REFRESH_RATE_TYPE = new EnumSetting<>("morphe_app_refresh_rate_type", AppRefreshType.ALWAYS, true, new RefreshRateType());

    // Return YouTube Dislike
    public static final BooleanSetting RYD_ENABLED = new BooleanSetting("morphe_ryd_enabled", TRUE);
    public static final StringSetting RYD_USER_ID = new StringSetting("morphe_ryd_user_id", "", false, false);
    public static final BooleanSetting RYD_DISLIKE_PERCENTAGE = new BooleanSetting("morphe_ryd_dislike_percentage", FALSE, true, parent(RYD_ENABLED));
    public static final BooleanSetting RYD_COMPACT_LAYOUT = new BooleanSetting("morphe_ryd_compact_layout", FALSE, true, parent(RYD_ENABLED));
    public static final BooleanSetting RYD_ESTIMATED_LIKE = new BooleanSetting("morphe_ryd_estimated_like", TRUE, true, parent(RYD_ENABLED));
    public static final BooleanSetting RYD_TOAST_ON_CONNECTION_ERROR = new BooleanSetting("morphe_ryd_toast_on_connection_error", TRUE, parent(RYD_ENABLED));

    // Migration
    private static final BooleanSetting DEPRECATED_EXTERNAL_DOWNLOADER_FLYOUT_BUTTON = new BooleanSetting("morphe_external_downloader_flyout_button", FALSE);
    private static final BooleanSetting DEPRECATED_SANITIZE_URL_QUERY = new BooleanSetting("morphe_sanitize_url_query", TRUE);

    static {
        // TODO: Eventually remove these migrations
        migrateOldSettingToNew(DEPRECATED_EXTERNAL_DOWNLOADER_FLYOUT_BUTTON, EXTERNAL_DOWNLOADER_FLYOUT_MENU);
        migrateOldSettingToNew(DEPRECATED_SANITIZE_URL_QUERY, SANITIZE_SHARING_LINKS);
    }

    private static String getDefaultSpoofAppVersionTarget() {
        return ""; // Modified during patching.
    }
}
