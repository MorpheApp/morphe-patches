package app.morphe.extension.shared.settings;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static app.morphe.extension.shared.patches.CustomBrandingPatch.BrandingTheme;
import static app.morphe.extension.shared.settings.Setting.parent;

import app.morphe.extension.shared.patches.CustomBrandingPatch;
import app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch.SpoofClientJavaScriptVariantAvailability;
import app.morphe.extension.shared.spoof.js.JavaScriptVariant;

/**
 * Settings shared by YouTube and YouTube Music.
 * <p>
 * To ensure this class is loaded when the UI is created, app specific setting bundles should extend
 * or reference this class.
 */
public class SharedYouTubeSettings extends BaseSettings {
    public static final BooleanSetting SETTINGS_SEARCH_HISTORY = new BooleanSetting("morphe_settings_search_history", TRUE, true);
    public static final StringSetting SETTINGS_SEARCH_ENTRIES = new StringSetting("morphe_settings_search_entries", "");

    public static final BooleanSetting DISABLE_DRC_AUDIO = new BooleanSetting("morphe_disable_drc_audio", FALSE, true);

    public static final BooleanSetting SPOOF_VIDEO_STREAMS = new BooleanSetting("morphe_spoof_video_streams", TRUE, true, "morphe_spoof_video_streams_user_dialog_message");
    public static final BooleanSetting SPOOF_VIDEO_STREAMS_STATS_FOR_NERDS = new BooleanSetting("morphe_spoof_video_streams_stats_for_nerds", TRUE, parent(SPOOF_VIDEO_STREAMS));
    public static final EnumSetting<JavaScriptVariant> SPOOF_VIDEO_STREAMS_JS_VARIANT = new EnumSetting<>("morphe_spoof_video_streams_js_variant", JavaScriptVariant.TV, true, new SpoofClientJavaScriptVariantAvailability());

    public static final StringSetting SPOOF_VIDEO_STREAMS_JS_HASH = new StringSetting("morphe_spoof_video_streams_js_hash", "", false);
    public static final LongSetting SPOOF_VIDEO_STREAMS_JS_SAVED_MILLISECONDS = new LongSetting("morphe_spoof_video_streams_js_saved_milliseconds", -1L, false);
    public static final StringSetting OAUTH2_REFRESH_TOKEN = new StringSetting("morphe_oauth2_refresh_token", "", false, false);

    public static final BooleanSetting SANITIZE_SHARED_LINKS = new BooleanSetting("morphe_sanitize_sharing_links", TRUE);
    public static final BooleanSetting REPLACE_MUSIC_LINKS_WITH_YOUTUBE = new BooleanSetting("morphe_replace_music_with_youtube", FALSE);

    public static final BooleanSetting CHECK_WATCH_HISTORY_DOMAIN_NAME = new BooleanSetting("morphe_check_watch_history_domain_name", TRUE, false, false);

    public static final EnumSetting<BrandingTheme> CUSTOM_BRANDING_ICON = new EnumSetting<>("morphe_custom_branding_icon", CustomBrandingPatch.getDefaultIconStyle(), true);
    public static final IntegerSetting CUSTOM_BRANDING_NAME = new IntegerSetting("morphe_custom_branding_name", CustomBrandingPatch.getDefaultAppNameIndex(), true);

    public static final StringSetting DISABLED_FEATURE_FLAGS = new StringSetting("morphe_disabled_feature_flags", "", true, parent(DEBUG));
}
