package app.morphe.extension.music.settings;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static app.morphe.extension.shared.settings.Setting.migrateOldSettingToNew;
import static app.morphe.extension.shared.settings.Setting.parent;
import static app.morphe.extension.shared.settings.Setting.parentNot;
import static app.morphe.extension.shared.settings.Setting.parentsAll;
import static app.morphe.extension.shared.settings.Setting.parentsAny;
import static app.morphe.extension.shared.sponsorblock.objects.CategoryBehaviour.IGNORE;
import static app.morphe.extension.shared.sponsorblock.objects.CategoryBehaviour.SKIP_AUTOMATICALLY;

import app.morphe.extension.music.patches.ChangeHeaderPatch.HeaderLogo;
import app.morphe.extension.music.patches.ChangeStartPagePatch.StartPage;
import app.morphe.extension.music.patches.CrossfadeManager.CrossFadeDuration;
import app.morphe.extension.music.patches.CrossfadeManager.FadeCurve;
import app.morphe.extension.music.sponsorblock.MusicSponsorBlockConfig;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.EnumSetting;
import app.morphe.extension.shared.settings.IntegerSetting;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.shared.settings.preference.SeekBarPreference;
import app.morphe.extension.shared.settings.preference.SeekBarPreference.SeekBarConfig;
import app.morphe.extension.shared.spoof.ClientType;


@SuppressWarnings({"deprecation", "RedundantSuppression"})
public class Settings extends SharedYouTubeSettings {

    // Ads
    public static final BooleanSetting HIDE_GET_PREMIUM_LABEL = new BooleanSetting("morphe_music_hide_get_premium_label", TRUE, true);
    public static final BooleanSetting HIDE_VIDEO_ADS = new BooleanSetting("morphe_music_hide_video_ads", TRUE, true);

    // General (Layout)
    public static final EnumSetting<StartPage> CHANGE_START_PAGE = new EnumSetting<>("morphe_change_start_page", StartPage.DEFAULT, true);
    public static final BooleanSetting HIDE_CAST_BUTTON = new BooleanSetting("morphe_music_hide_cast_button", TRUE, true);
    public static final BooleanSetting HIDE_FILTER_BAR = new BooleanSetting("morphe_music_hide_filter_bar", FALSE, true);
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


    // Custom filter
    public static final BooleanSetting CUSTOM_FILTER = new BooleanSetting("morphe_music_custom_filter", FALSE);
    public static final StringSetting CUSTOM_FILTER_STRINGS = new StringSetting("morphe_music_custom_filter_strings", "", true, parent(CUSTOM_FILTER));

    // Settings menu filter
    public static final StringSetting SETTINGS_MENU_FILTER_STRINGS = new StringSetting("morphe_music_settings_menu_filter_strings", "", true);
    public static final StringSetting SETTINGS_MENU_FILTER_DISCOVERED = new StringSetting("morphe_music_settings_menu_filter_discovered", "", true);

    // Player
    public static final BooleanSetting MINIPLAYER_NEXT_BUTTON = new BooleanSetting("morphe_music_miniplayer_next_button", TRUE, true);
    public static final BooleanSetting MINIPLAYER_PREVIOUS_BUTTON = new BooleanSetting("morphe_music_miniplayer_previous_button", TRUE, true);
    public static final BooleanSetting CHANGE_MINIPLAYER_COLOR = new BooleanSetting("morphe_music_change_miniplayer_color", FALSE, true);
    public static final BooleanSetting CHANGE_NAVIGATION_BAR_COLOR = new BooleanSetting("morphe_music_change_navigation_bar_color", FALSE, true, parent(CHANGE_MINIPLAYER_COLOR));
    public static final BooleanSetting ENABLE_FORCED_MINIPLAYER = new BooleanSetting("morphe_music_enable_forced_miniplayer", FALSE, true);
    public static final BooleanSetting ENABLE_SWIPE_TO_DISMISS_MINIPLAYER = new BooleanSetting("morphe_music_enable_swipe_to_dismiss_miniplayer", FALSE, true);
    public static final BooleanSetting DISABLE_DISLIKE_REDIRECTION = new BooleanSetting("morphe_music_disable_dislike_redirection", FALSE, true);

    // Action buttons
    public static final BooleanSetting HIDE_ACTION_BAR = new BooleanSetting("morphe_music_hide_action_bar", FALSE, true);
    public static final BooleanSetting HIDE_LIKE_DISLIKE_BUTTON = new BooleanSetting("morphe_music_hide_like_dislike_button", FALSE, true, parentNot(HIDE_ACTION_BAR));
    public static final BooleanSetting HIDE_COMMENTS_BUTTON = new BooleanSetting("morphe_music_hide_comments_button", FALSE, true, parentNot(HIDE_ACTION_BAR));
    public static final BooleanSetting HIDE_LYRICS_BUTTON = new BooleanSetting("morphe_music_hide_lyrics_button", FALSE, true, parentNot(HIDE_ACTION_BAR));
    public static final BooleanSetting HIDE_SHARE_BUTTON = new BooleanSetting("morphe_music_hide_share_button", FALSE, true, parentNot(HIDE_ACTION_BAR));
    public static final BooleanSetting HIDE_SAVE_BUTTON = new BooleanSetting("morphe_music_hide_save_button", FALSE, true, parentNot(HIDE_ACTION_BAR));
    public static final BooleanSetting HIDE_DOWNLOAD_BUTTON = new BooleanSetting("morphe_music_hide_download_button", FALSE, true, parentNot(HIDE_ACTION_BAR));
    public static final BooleanSetting HIDE_RADIO_BUTTON = new BooleanSetting("morphe_music_hide_radio_button", FALSE, true, parentNot(HIDE_ACTION_BAR));
    public static final BooleanSetting HIDE_LYRICS_SHARE_BUTTON = new BooleanSetting("morphe_music_hide_lyrics_share_button", FALSE, true);
    public static final BooleanSetting HIDE_LYRICS_TRANSLATE_BUTTON = new BooleanSetting("morphe_music_hide_lyrics_translate_button", FALSE, true);
    public static final BooleanSetting REMEMBER_REPEAT_STATE = new BooleanSetting("morphe_music_remember_repeat_state", FALSE, true);
    public static final BooleanSetting REMEMBER_SHUFFLE_STATE = new BooleanSetting("morphe_music_remember_shuffle_state", FALSE, true);
    public static final BooleanSetting SAVED_SHUFFLE_STATE = new BooleanSetting("morphe_music_saved_shuffle_state", FALSE, parent(REMEMBER_SHUFFLE_STATE));

    // Flyout menu
    public static final BooleanSetting HIDE_FLYOUT_MENU_3_COLUMN_COMPONENT = new BooleanSetting("morphe_music_hide_flyout_menu_3_column_component", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_LIKE_DISLIKE = new BooleanSetting("morphe_music_hide_flyout_menu_like_dislike", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_TASTE_MATCH = new BooleanSetting("morphe_music_hide_flyout_menu_taste_match", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_ADD_TO_LISTEN_LATER = new BooleanSetting("morphe_music_hide_flyout_menu_add_to_listen_later", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_ADD_TO_QUEUE = new BooleanSetting("morphe_music_hide_flyout_menu_add_to_queue", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_CAPTIONS = new BooleanSetting("morphe_music_hide_flyout_menu_captions", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_DELETE_PLAYLIST = new BooleanSetting("morphe_music_hide_flyout_menu_delete_playlist", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_DISMISS_QUEUE = new BooleanSetting("morphe_music_hide_flyout_menu_dismiss_queue", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_DONT_RECOMMEND_ARTIST = new BooleanSetting("morphe_music_hide_flyout_menu_dont_recommend_artist", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_DOWNLOAD = new BooleanSetting("morphe_music_hide_flyout_menu_download", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_EDIT_PLAYLIST = new BooleanSetting("morphe_music_hide_flyout_menu_edit_playlist", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_GO_TO_ALBUM = new BooleanSetting("morphe_music_hide_flyout_menu_go_to_album", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_GO_TO_ARTIST = new BooleanSetting("morphe_music_hide_flyout_menu_go_to_artist", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_GO_TO_EPISODE = new BooleanSetting("morphe_music_hide_flyout_menu_go_to_episode", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_GO_TO_PODCAST = new BooleanSetting("morphe_music_hide_flyout_menu_go_to_podcast", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_HELP = new BooleanSetting("morphe_music_hide_flyout_menu_help", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_MARK_EPISODE_AS_PLAYED = new BooleanSetting("morphe_music_hide_flyout_menu_mark_episode_as_played", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_NOT_INTERESTED = new BooleanSetting("morphe_music_hide_flyout_menu_not_interested", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_PIN_TO_SPEED_DIAL = new BooleanSetting("morphe_music_hide_flyout_menu_pin_to_speed_dial", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_PLAY_NEXT = new BooleanSetting("morphe_music_hide_flyout_menu_play_next", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_QUALITY = new BooleanSetting("morphe_music_hide_flyout_menu_quality", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_REMOVE_FROM_LIBRARY = new BooleanSetting("morphe_music_hide_flyout_menu_remove_from_library", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_REMOVE_FROM_PLAYLIST = new BooleanSetting("morphe_music_hide_flyout_menu_remove_from_playlist", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_REPORT = new BooleanSetting("morphe_music_hide_flyout_menu_report", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_SAVE_EPISODE_FOR_LATER_SAVE_TO_LIBRARY = new BooleanSetting("morphe_music_hide_flyout_menu_save_episode_for_later_save_to_library", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_SAVE_TO_PLAYLIST = new BooleanSetting("morphe_music_hide_flyout_menu_save_to_playlist", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_SHARE = new BooleanSetting("morphe_music_hide_flyout_menu_share", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_SHUFFLE_PLAY = new BooleanSetting("morphe_music_hide_flyout_menu_shuffle_play", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_SLEEP_TIMER = new BooleanSetting("morphe_music_hide_flyout_menu_sleep_timer", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_START_RADIO = new BooleanSetting("morphe_music_hide_flyout_menu_start_radio", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_STATS_FOR_NERDS = new BooleanSetting("morphe_music_hide_flyout_menu_stats_for_nerds", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_SUBSCRIBE = new BooleanSetting("morphe_music_hide_flyout_menu_subscribe", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_UNPIN_FROM_SPEED_DIAL = new BooleanSetting("morphe_music_hide_flyout_menu_unpin_from_speed_dial", FALSE);
    public static final BooleanSetting HIDE_FLYOUT_MENU_VIEW_SONG_CREDIT = new BooleanSetting("morphe_music_hide_flyout_menu_view_song_credit", FALSE);

    // Crossfade
    public static final BooleanSetting CROSSFADE_ENABLED = new BooleanSetting("morphe_music_crossfade_enabled", FALSE, true);
    public static final EnumSetting<FadeCurve> CROSSFADE_CURVE = new EnumSetting<>("morphe_music_crossfade_curve", FadeCurve.EQUAL_POWER, parent(CROSSFADE_ENABLED));
    public static final EnumSetting<CrossFadeDuration> CROSSFADE_DURATION = new EnumSetting<>("morphe_music_crossfade_duration", CrossFadeDuration.MILLISECONDS_3000, parent(CROSSFADE_ENABLED));
    public static final BooleanSetting CROSSFADE_ON_SKIP = new BooleanSetting("morphe_music_crossfade_on_skip", TRUE, parent(CROSSFADE_ENABLED));
    public static final BooleanSetting CROSSFADE_ON_AUTO_ADVANCE = new BooleanSetting("morphe_music_crossfade_on_auto_advance", TRUE, parent(CROSSFADE_ENABLED));
    public static final BooleanSetting CROSSFADE_SESSION_CONTROL = new BooleanSetting("morphe_music_crossfade_session_control", TRUE, parent(CROSSFADE_ENABLED));

    // Miscellaneous
    public static final EnumSetting<ClientType> SPOOF_VIDEO_STREAMS_CLIENT_TYPE = new EnumSetting<>("morphe_spoof_video_streams_client_type", ClientType.TV, true, parent(SPOOF_VIDEO_STREAMS));

    public static final BooleanSetting FORCE_ORIGINAL_AUDIO = new BooleanSetting("morphe_force_original_audio", TRUE, true);

    // Scrobbling
    public static final BooleanSetting LISTENBRAINZ_SCROBBLING = new BooleanSetting("morphe_music_listenbrainz_enabled", FALSE, true);
    public static final StringSetting LISTENBRAINZ_USER_TOKEN = new StringSetting("morphe_music_listenbrainz_token", "", false, parent(LISTENBRAINZ_SCROBBLING));
    public static final BooleanSetting LISTENBRAINZ_NOW_PLAYING = new BooleanSetting("morphe_music_listenbrainz_now_playing", FALSE, true, parent(LISTENBRAINZ_SCROBBLING));
    public static final IntegerSetting LISTENBRAINZ_MIN_SONG_DURATION = new IntegerSetting("morphe_music_listenbrainz_min_song_duration", 30, true, parent(LISTENBRAINZ_SCROBBLING));
    public static final IntegerSetting LISTENBRAINZ_DELAY_PERCENT = new IntegerSetting("morphe_music_listenbrainz_delay_percent", 50, true, parent(LISTENBRAINZ_SCROBBLING));
    public static final IntegerSetting LISTENBRAINZ_DELAY_SECONDS = new IntegerSetting("morphe_music_listenbrainz_delay_seconds", 180, true, parent(LISTENBRAINZ_SCROBBLING));
    public static final BooleanSetting LASTFM_SCROBBLING = new BooleanSetting("morphe_music_lastfm_enabled", FALSE, true);
    public static final StringSetting LASTFM_SESSION_KEY = new StringSetting("morphe_music_lastfm_session_key", "", false, parent(LASTFM_SCROBBLING));
    public static final StringSetting LASTFM_USERNAME = new StringSetting("morphe_music_lastfm_username", "", false, parent(LASTFM_SCROBBLING));
    public static final BooleanSetting LASTFM_NOW_PLAYING = new BooleanSetting("morphe_music_lastfm_now_playing", FALSE, true, parent(LASTFM_SCROBBLING));
    public static final BooleanSetting LASTFM_LOVE_ON_LIKE = new BooleanSetting("morphe_music_lastfm_love_on_like", FALSE, true, parent(LASTFM_SCROBBLING));
    public static final IntegerSetting LASTFM_MIN_SONG_DURATION = new IntegerSetting("morphe_music_lastfm_min_song_duration", 30, true, parent(LASTFM_SCROBBLING));
    public static final IntegerSetting LASTFM_DELAY_PERCENT = new IntegerSetting("morphe_music_lastfm_delay_percent", 50, true, parent(LASTFM_SCROBBLING));
    public static final IntegerSetting LASTFM_DELAY_SECONDS = new IntegerSetting("morphe_music_lastfm_delay_seconds", 180, true, parent(LASTFM_SCROBBLING));
    public static final BooleanSetting SCROBBLING_METADATA_CLEANUP = new BooleanSetting("morphe_music_scrobbling_metadata_cleanup", TRUE, true, parentsAny(LISTENBRAINZ_SCROBBLING, LASTFM_SCROBBLING));
    public static final StringSetting SCROBBLING_CUSTOM_REGEX = new StringSetting("morphe_music_scrobbling_custom_regex", "", true, parentsAll(parent(SCROBBLING_METADATA_CLEANUP), parentsAny(LISTENBRAINZ_SCROBBLING, LASTFM_SCROBBLING)));
    public static final BooleanSetting SCROBBLING_PARSE_TITLE = new BooleanSetting("morphe_music_scrobbling_parse_title", FALSE, true, parentsAny(LISTENBRAINZ_SCROBBLING, LASTFM_SCROBBLING));

    // SponsorBlock
    public static final BooleanSetting SB_ENABLED = new BooleanSetting("morphe_sb_enabled", TRUE);
    public static final BooleanSetting SB_TOAST_ON_SKIP = new BooleanSetting("morphe_sb_toast_on_skip", TRUE, parent(SB_ENABLED));
    public static final BooleanSetting SB_TOAST_ON_CONNECTION_ERROR = new BooleanSetting("morphe_sb_toast_on_connection_error", TRUE, parent(SB_ENABLED));
    public static final StringSetting SB_API_URL = new StringSetting("morphe_sb_api_url", "https://sponsor.ajay.app", parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_SPONSOR = new StringSetting("morphe_sb_sponsor", SKIP_AUTOMATICALLY.morpheKeyValue, parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_SPONSOR_COLOR = new StringSetting("morphe_sb_sponsor_color", "#FF00D400", parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_SELF_PROMO = new StringSetting("morphe_sb_selfpromo", SKIP_AUTOMATICALLY.morpheKeyValue, parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_SELF_PROMO_COLOR = new StringSetting("morphe_sb_selfpromo_color", "#FFFFFF00", parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_INTERACTION = new StringSetting("morphe_sb_interaction", SKIP_AUTOMATICALLY.morpheKeyValue, parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_INTERACTION_COLOR = new StringSetting("morphe_sb_interaction_color", "#FFCC00FF", parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_INTRO = new StringSetting("morphe_sb_intro", SKIP_AUTOMATICALLY.morpheKeyValue, parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_INTRO_COLOR = new StringSetting("morphe_sb_intro_color", "#FF00FFFF", parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_OUTRO = new StringSetting("morphe_sb_outro", SKIP_AUTOMATICALLY.morpheKeyValue, parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_OUTRO_COLOR = new StringSetting("morphe_sb_outro_color", "#FF0202ED", parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_PREVIEW = new StringSetting("morphe_sb_preview", IGNORE.morpheKeyValue, parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_PREVIEW_COLOR = new StringSetting("morphe_sb_preview_color", "#FF008FD6", parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_HOOK = new StringSetting("morphe_sb_hook", IGNORE.morpheKeyValue, parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_HOOK_COLOR = new StringSetting("morphe_sb_hook_color", "#FF395699", parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_FILLER = new StringSetting("morphe_sb_filler", IGNORE.morpheKeyValue, parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_FILLER_COLOR = new StringSetting("morphe_sb_filler_color", "#FF7300FF", parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_MUSIC_OFFTOPIC = new StringSetting("morphe_sb_music_offtopic", SKIP_AUTOMATICALLY.morpheKeyValue, parent(SB_ENABLED));
    public static final StringSetting SB_CATEGORY_MUSIC_OFFTOPIC_COLOR = new StringSetting("morphe_sb_music_offtopic_color", "#FFFF9900", parent(SB_ENABLED));

    // Migration
    private static final BooleanSetting DEPRECATED_HIDE_CATEGORY_BAR = new BooleanSetting("morphe_music_hide_category_bar", FALSE, true);
    private static final BooleanSetting DEPRECATED_PERMANENT_REPEAT = new BooleanSetting("morphe_music_permanent_repeat", FALSE, true);

    static {
        migrateOldSettingToNew(DEPRECATED_HIDE_CATEGORY_BAR , HIDE_FILTER_BAR);
        migrateOldSettingToNew(DEPRECATED_PERMANENT_REPEAT , REMEMBER_REPEAT_STATE);
    }

    static {
        SeekBarPreference.register(new SeekBarConfig(LISTENBRAINZ_MIN_SONG_DURATION,
                10, 60, 5, "s"));
        SeekBarPreference.register(new SeekBarConfig(LISTENBRAINZ_DELAY_PERCENT,
                30, 95, 5, "%"));
        SeekBarPreference.register(new SeekBarConfig(LISTENBRAINZ_DELAY_SECONDS,
                30, 360, 10, "s"));
        SeekBarPreference.register(new SeekBarConfig(LASTFM_MIN_SONG_DURATION,
                10, 60, 5, "s"));
        SeekBarPreference.register(new SeekBarConfig(LASTFM_DELAY_PERCENT,
                30, 95, 5, "%"));
        SeekBarPreference.register(new SeekBarConfig(LASTFM_DELAY_SECONDS,
                30, 360, 10, "s"));

        // Must run before any code reads a SegmentCategory setting.
        MusicSponsorBlockConfig.install();
    }
}
