package app.morphe.extension.music.settings;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static app.morphe.extension.shared.settings.Setting.parent;
import static app.morphe.extension.shared.settings.Setting.parentNot;
import static app.morphe.extension.shared.sponsorblock.objects.CategoryBehaviour.IGNORE;
import static app.morphe.extension.shared.sponsorblock.objects.CategoryBehaviour.SKIP_AUTOMATICALLY;

import app.morphe.extension.music.patches.ChangeHeaderPatch.HeaderLogo;
import app.morphe.extension.music.patches.ChangeStartPagePatch.StartPage;
import app.morphe.extension.music.patches.CrossfadeManager.CrossFadeDuration;
import app.morphe.extension.music.patches.CrossfadeManager.FadeCurve;
import app.morphe.extension.music.sponsorblock.MusicSponsorBlockConfig;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.EnumSetting;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.shared.spoof.ClientType;

@SuppressWarnings({"deprecation", "RedundantSuppression"})
public class Settings extends SharedYouTubeSettings {

    // Ads
    public static final BooleanSetting HIDE_GET_PREMIUM_LABEL = new BooleanSetting("morphe_music_hide_get_premium_label", TRUE, true);
    public static final BooleanSetting HIDE_VIDEO_ADS = new BooleanSetting("morphe_music_hide_video_ads", TRUE, true);

    // General (Layout)
    public static final EnumSetting<StartPage> CHANGE_START_PAGE = new EnumSetting<>("morphe_change_start_page", StartPage.DEFAULT, true);
    public static final BooleanSetting HIDE_CAST_BUTTON = new BooleanSetting("morphe_music_hide_cast_button", TRUE, true);
    public static final BooleanSetting HIDE_CATEGORY_BAR = new BooleanSetting("morphe_music_hide_category_bar", FALSE, true);
    public static final BooleanSetting HIDE_HISTORY_BUTTON = new BooleanSetting("morphe_music_hide_history_button", FALSE, true);
    public static final BooleanSetting HIDE_SEARCH_BUTTON = new BooleanSetting("morphe_music_hide_search_button", FALSE, true);
    public static final BooleanSetting HIDE_NOTIFICATION_BUTTON = new BooleanSetting("morphe_music_hide_notification_button", FALSE, true);
    public static final BooleanSetting HIDE_NAVIGATION_BAR = new BooleanSetting("morphe_music_hide_navigation_bar", FALSE, true);
    public static final BooleanSetting HIDE_NAVIGATION_BAR_HOME_BUTTON = new BooleanSetting("morphe_music_hide_navigation_bar_home_button", FALSE, true, parentNot(HIDE_NAVIGATION_BAR));
    public static final BooleanSetting HIDE_NAVIGATION_BAR_SAMPLES_BUTTON = new BooleanSetting("morphe_music_hide_navigation_bar_samples_button", FALSE, true, parentNot(HIDE_NAVIGATION_BAR));
    public static final BooleanSetting HIDE_NAVIGATION_BAR_EXPLORE_BUTTON = new BooleanSetting("morphe_music_hide_navigation_bar_explore_button", FALSE, true, parentNot(HIDE_NAVIGATION_BAR));
    public static final BooleanSetting HIDE_NAVIGATION_BAR_LIBRARY_BUTTON = new BooleanSetting("morphe_music_hide_navigation_bar_library_button", FALSE, true, parentNot(HIDE_NAVIGATION_BAR));
    public static final BooleanSetting HIDE_NAVIGATION_BAR_UPGRADE_BUTTON = new BooleanSetting("morphe_music_hide_navigation_bar_upgrade_button", TRUE, true, parentNot(HIDE_NAVIGATION_BAR));
    public static final BooleanSetting HIDE_NAVIGATION_BAR_LABEL = new BooleanSetting("morphe_music_hide_navigation_bar_labels", FALSE, true, parentNot(HIDE_NAVIGATION_BAR));
    public static final EnumSetting<HeaderLogo> HEADER_LOGO = new EnumSetting<>("morphe_header_logo", HeaderLogo.DEFAULT, true);

    // Player
    public static final BooleanSetting MINIPLAYER_NEXT_BUTTON = new BooleanSetting("morphe_music_miniplayer_next_button", TRUE, true);
    public static final BooleanSetting MINIPLAYER_PREVIOUS_BUTTON = new BooleanSetting("morphe_music_miniplayer_previous_button", TRUE, true);
    public static final BooleanSetting CHANGE_MINIPLAYER_COLOR = new BooleanSetting("morphe_music_change_miniplayer_color", FALSE, true);
    public static final BooleanSetting ENABLE_FORCED_MINIPLAYER = new BooleanSetting("morphe_music_enable_forced_miniplayer", FALSE, true);
    public static final BooleanSetting ENABLE_SWIPE_TO_DISMISS_MINIPLAYER = new BooleanSetting("morphe_music_enable_swipe_to_dismiss_miniplayer", FALSE, true);
    public static final BooleanSetting PERMANENT_REPEAT = new BooleanSetting("morphe_music_play_permanent_repeat", FALSE, true);

    // Crossfade
    public static final BooleanSetting CROSSFADE_ENABLED = new BooleanSetting("morphe_music_crossfade_enabled", FALSE, true);
    public static final EnumSetting<FadeCurve> CROSSFADE_CURVE = new EnumSetting<>("morphe_music_crossfade_curve", FadeCurve.EQUAL_POWER, parent(CROSSFADE_ENABLED));
    public static final EnumSetting<CrossFadeDuration> CROSSFADE_DURATION = new EnumSetting<>("morphe_music_crossfade_duration", CrossFadeDuration.MILLISECONDS_3000, parent(CROSSFADE_ENABLED));
    public static final BooleanSetting CROSSFADE_ON_SKIP = new BooleanSetting("morphe_music_crossfade_on_skip", TRUE, parent(CROSSFADE_ENABLED));
    public static final BooleanSetting CROSSFADE_ON_AUTO_ADVANCE = new BooleanSetting("morphe_music_crossfade_on_auto_advance", TRUE, parent(CROSSFADE_ENABLED));
    public static final BooleanSetting CROSSFADE_SESSION_CONTROL = new BooleanSetting("morphe_music_crossfade_session_control", TRUE, parent(CROSSFADE_ENABLED));

    // Miscellaneous
    public static final EnumSetting<ClientType> SPOOF_VIDEO_STREAMS_CLIENT_TYPE = new EnumSetting<>("morphe_spoof_video_streams_client_type",
            ClientType.ANDROID_REEL_NO_AUTH, true, parent(SPOOF_VIDEO_STREAMS));

    public static final BooleanSetting FORCE_ORIGINAL_AUDIO = new BooleanSetting("morphe_force_original_audio", TRUE, true);

    // SponsorBlock
    public static final BooleanSetting SB_ENABLED = new BooleanSetting("morphe_sb_enabled", TRUE);
    public static final BooleanSetting SB_TOAST_ON_SKIP = new BooleanSetting("morphe_sb_toast_on_skip", TRUE, parent(SB_ENABLED));
    public static final BooleanSetting SB_TOAST_ON_CONNECTION_ERROR = new BooleanSetting("morphe_music_sb_toast_on_connection_error", TRUE, parent(SB_ENABLED));
    public static final StringSetting SB_API_URL = new StringSetting("morphe_music_sb_api_url", "https://sponsor.ajay.app", parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_SPONSOR = new StringSetting("morphe_music_sb_sponsor", SKIP_AUTOMATICALLY.morpheKeyValue, parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_SPONSOR_COLOR = new StringSetting("morphe_music_sb_sponsor_color", "#FF00D400", parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_SELF_PROMO = new StringSetting("morphe_music_sb_selfpromo", SKIP_AUTOMATICALLY.morpheKeyValue, parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_SELF_PROMO_COLOR = new StringSetting("morphe_music_sb_selfpromo_color", "#FFFFFF00", parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_INTERACTION = new StringSetting("morphe_music_sb_interaction", SKIP_AUTOMATICALLY.morpheKeyValue, parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_INTERACTION_COLOR = new StringSetting("morphe_music_sb_interaction_color", "#FFCC00FF", parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_INTRO = new StringSetting("morphe_music_sb_intro", SKIP_AUTOMATICALLY.morpheKeyValue, parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_INTRO_COLOR = new StringSetting("morphe_music_sb_intro_color", "#FF00FFFF", parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_OUTRO = new StringSetting("morphe_music_sb_outro", SKIP_AUTOMATICALLY.morpheKeyValue, parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_OUTRO_COLOR = new StringSetting("morphe_music_sb_outro_color", "#FF0202ED", parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_PREVIEW = new StringSetting("morphe_music_sb_preview", IGNORE.morpheKeyValue, parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_PREVIEW_COLOR = new StringSetting("morphe_music_sb_preview_color", "#FF008FD6", parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_HOOK = new StringSetting("morphe_music_sb_hook", IGNORE.morpheKeyValue, parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_HOOK_COLOR = new StringSetting("morphe_music_sb_hook_color", "#FF395699", parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_FILLER = new StringSetting("morphe_music_sb_filler", IGNORE.morpheKeyValue, parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_FILLER_COLOR = new StringSetting("morphe_music_sb_filler_color", "#FF7300FF", parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_MUSIC_OFFTOPIC = new StringSetting("morphe_music_sb_music_offtopic", SKIP_AUTOMATICALLY.morpheKeyValue, parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_MUSIC_OFFTOPIC_COLOR = new StringSetting("morphe_music_sb_music_offtopic_color", "#FFFF9900", parent(SB_ENABLED));

    static {
        // Must run before any code reads a SegmentCategory setting.
        MusicSponsorBlockConfig.install();
    }
}
