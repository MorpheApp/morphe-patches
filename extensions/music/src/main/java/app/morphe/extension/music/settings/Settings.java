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
import app.morphe.extension.music.patches.CrossfadePatch.CrossFadeDuration;
import app.morphe.extension.music.patches.CrossfadePatch.FadeCurve;
import app.morphe.extension.music.patches.PlaylistTrackTapPatch.TapAction;
import app.morphe.extension.music.patches.lyrics.derive.OpenAIClient;
import app.morphe.extension.music.patches.lyrics.derive.ProcessingProviders;
import app.morphe.extension.music.patches.lyrics.model.LyricsFilterDefaults;
import app.morphe.extension.music.patches.lyrics.model.MiniLyricsFormatter;
import app.morphe.extension.music.patches.soundproof.domain.AiDetectionPolicy;
import app.morphe.extension.music.sponsorblock.MusicSponsorBlockConfig;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.patches.DownloadMode;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.EnumSetting;
import app.morphe.extension.shared.settings.FloatSetting;
import app.morphe.extension.shared.settings.IntegerSetting;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.shared.settings.preference.SeekBarPreference;
import app.morphe.extension.shared.settings.preference.SeekBarPreference.SeekBarConfig;
import app.morphe.extension.shared.spoof.ClientType;

@SuppressWarnings("unused")
public class Settings extends SharedYouTubeSettings {

    // Jam queue sharing
    public static final BooleanSetting JAM_ENABLED = new BooleanSetting("morphe_music_jam_enabled", FALSE, true);

    // Ads
    public static final BooleanSetting HIDE_GET_PREMIUM_LABEL = new BooleanSetting("morphe_music_hide_get_premium_label", TRUE, true);
    public static final BooleanSetting HIDE_MUSIC_PREMIUM_PROMOTIONS = new BooleanSetting("morphe_music_hide_music_premium_promotions", TRUE, true);
    public static final BooleanSetting HIDE_VIDEO_ADS = new BooleanSetting("morphe_music_hide_video_ads", TRUE, true);

    // Feed
    public static final BooleanSetting HIDE_DOWNLOAD_BUTTON_IN_PODCAST = new BooleanSetting("morphe_music_hide_podcast_episode_download_button", FALSE, true);
    public static final BooleanSetting HIDE_EXPLORE_SHELF = new BooleanSetting("morphe_music_hide_explore_shelf", FALSE, true);
    public static final BooleanSetting HIDE_GRID_SHELVES = new BooleanSetting("morphe_music_hide_grid_shelves", FALSE, true);
    public static final BooleanSetting HIDE_HORIZONTAL_SHELVES = new BooleanSetting("morphe_music_hide_horizontal_shelves", FALSE, true);
    public static final BooleanSetting HIDE_LIST_SHELVES = new BooleanSetting("morphe_music_hide_list_shelves", FALSE, true);
    public static final BooleanSetting HIDE_NEW_FROM_SHELF = new BooleanSetting("morphe_music_hide_new_from_shelf", FALSE, true);
    public static final BooleanSetting HIDE_PLAYLIST_SHELVES = new BooleanSetting("morphe_music_hide_playlist_shelves", FALSE, true);
    public static final BooleanSetting HIDE_SPEED_DIAL_SHELF = new BooleanSetting("morphe_music_hide_speed_dial_shelf", FALSE, true);
    public static final BooleanSetting HIDE_YOU_MAY_ALSO_LIKE_SECTION = new BooleanSetting("morphe_music_hide_you_may_also_like_section", FALSE, true);
    public static final BooleanSetting HIDE_YOUR_DOWNLOADS_SHELF = new BooleanSetting("morphe_music_hide_your_downloads_shelf", FALSE, true);

    // General (Layout)
    public static final EnumSetting<StartPage> CHANGE_START_PAGE = new EnumSetting<>("morphe_change_start_page", StartPage.DEFAULT, true);
    public static final BooleanSetting FORCE_PORTRAIT_ORIENTATION = new BooleanSetting("morphe_music_force_portrait_orientation", FALSE, true);
    public static final BooleanSetting HIDE_CAST_BUTTON = new BooleanSetting("morphe_music_hide_cast_button", FALSE, true);
    public static final BooleanSetting HIDE_FILTER_BAR = new BooleanSetting("morphe_music_hide_filter_bar", FALSE, true);
    public static final BooleanSetting HIDE_HISTORY_BUTTON = new BooleanSetting("morphe_music_hide_history_button", FALSE, true);
    public static final BooleanSetting HIDE_SEARCH_BUTTON = new BooleanSetting("morphe_music_hide_search_button", FALSE, true);
    public static final BooleanSetting HIDE_VOICE_SEARCH_BUTTON = new BooleanSetting("morphe_music_hide_voice_search_button", FALSE, true);
    public static final BooleanSetting HIDE_SOUND_SEARCH_BUTTON = new BooleanSetting("morphe_music_hide_sound_search_button", FALSE, true);
    public static final BooleanSetting HIDE_LIBRARY_NEW_BUTTON = new BooleanSetting("morphe_music_hide_library_new_button", FALSE, true);
    public static final BooleanSetting HIDE_NOTIFICATION_BUTTON = new BooleanSetting("morphe_music_hide_notification_button", FALSE, true);
    public static final BooleanSetting HIDE_NAVIGATION_BAR = new BooleanSetting("morphe_music_hide_navigation_bar", FALSE, true);
    public static final BooleanSetting HIDE_NAVIGATION_BAR_HOME_BUTTON = new BooleanSetting("morphe_music_hide_navigation_bar_home_button", FALSE, true, parentNot(HIDE_NAVIGATION_BAR));
    public static final BooleanSetting HIDE_NAVIGATION_BAR_SAMPLES_BUTTON = new BooleanSetting("morphe_music_hide_navigation_bar_samples_button", FALSE, true, parentNot(HIDE_NAVIGATION_BAR));
    public static final BooleanSetting HIDE_NAVIGATION_BAR_EXPLORE_BUTTON = new BooleanSetting("morphe_music_hide_navigation_bar_explore_button", FALSE, true, parentNot(HIDE_NAVIGATION_BAR));
    public static final BooleanSetting HIDE_NAVIGATION_BAR_LIBRARY_BUTTON = new BooleanSetting("morphe_music_hide_navigation_bar_library_button", FALSE, true, parentNot(HIDE_NAVIGATION_BAR));
    public static final BooleanSetting HIDE_NAVIGATION_BAR_UPGRADE_BUTTON = new BooleanSetting("morphe_music_hide_navigation_bar_upgrade_button", TRUE, true, parentNot(HIDE_NAVIGATION_BAR));
    public static final BooleanSetting HIDE_NAVIGATION_BAR_LABEL = new BooleanSetting("morphe_music_hide_navigation_bar_labels", FALSE, true, parentNot(HIDE_NAVIGATION_BAR));
    public static final BooleanSetting NAVIGATION_BAR_GLASS = new BooleanSetting("morphe_music_navigation_bar_glass", FALSE, true, parentNot(HIDE_NAVIGATION_BAR));
    public static final IntegerSetting NAVIGATION_BAR_GLASS_OPACITY = new IntegerSetting("morphe_music_navigation_bar_glass_opacity", 20, false, parentsAll(parent(NAVIGATION_BAR_GLASS), parentNot(HIDE_NAVIGATION_BAR)));
    public static final IntegerSetting NAVIGATION_BAR_GLASS_BLUR = new IntegerSetting("morphe_music_navigation_bar_glass_blur", 20, false, parentsAll(parent(NAVIGATION_BAR_GLASS), parentNot(HIDE_NAVIGATION_BAR)));
    public static final IntegerSetting NAVIGATION_BAR_GLASS_RIM = new IntegerSetting("morphe_music_navigation_bar_glass_rim", 50, false, parentsAll(parent(NAVIGATION_BAR_GLASS), parentNot(HIDE_NAVIGATION_BAR)));
    public static final BooleanSetting NAVIGATION_BAR_GLASS_LIGHT = new BooleanSetting("morphe_music_navigation_bar_glass_light", FALSE, false, parentsAll(parent(NAVIGATION_BAR_GLASS), parentNot(HIDE_NAVIGATION_BAR)));
    public static final BooleanSetting NAVIGATION_BAR_GLASS_ALBUM_TINT = new BooleanSetting("morphe_music_navigation_bar_glass_album_tint", FALSE, false, parentsAll(parent(NAVIGATION_BAR_GLASS), parentNot(HIDE_NAVIGATION_BAR)));
    public static final BooleanSetting NAVIGATION_BAR_GLASS_HIDE_INDICATOR = new BooleanSetting("morphe_music_navigation_bar_glass_hide_indicator", FALSE, false, parentsAll(parent(NAVIGATION_BAR_GLASS), parentNot(HIDE_NAVIGATION_BAR)));
    public static final IntegerSetting NAVIGATION_BAR_GLASS_HEIGHT = new IntegerSetting("morphe_music_navigation_bar_glass_height", 100, false, parentsAll(parent(NAVIGATION_BAR_GLASS), parentNot(HIDE_NAVIGATION_BAR)));
    public static final IntegerSetting NAVIGATION_BAR_GLASS_WIDTH = new IntegerSetting("morphe_music_navigation_bar_glass_width", 100, false, parentsAll(parent(NAVIGATION_BAR_GLASS), parentNot(HIDE_NAVIGATION_BAR)));
    public static final IntegerSetting NAVIGATION_BAR_GLASS_BUTTON_SPACING = new IntegerSetting("morphe_music_navigation_bar_glass_button_spacing", 48, false, parentsAll(parent(NAVIGATION_BAR_GLASS), parentNot(HIDE_NAVIGATION_BAR)));
    public static final BooleanSetting MINIPLAYER_GLASS = new BooleanSetting("morphe_music_miniplayer_glass", FALSE, true);
    public static final IntegerSetting MINIPLAYER_GLASS_OPACITY = new IntegerSetting("morphe_music_miniplayer_glass_opacity", 25, false, parent(MINIPLAYER_GLASS));
    public static final IntegerSetting MINIPLAYER_GLASS_BLUR = new IntegerSetting("morphe_music_miniplayer_glass_blur", 20, false, parent(MINIPLAYER_GLASS));
    public static final IntegerSetting MINIPLAYER_GLASS_RIM = new IntegerSetting("morphe_music_miniplayer_glass_rim", 50, false, parent(MINIPLAYER_GLASS));
    public static final BooleanSetting MINIPLAYER_GLASS_LIGHT = new BooleanSetting("morphe_music_miniplayer_glass_light", FALSE, false, parent(MINIPLAYER_GLASS));
    public static final BooleanSetting MINIPLAYER_GLASS_ALBUM_TINT = new BooleanSetting("morphe_music_miniplayer_glass_album_tint", TRUE, false, parent(MINIPLAYER_GLASS));
    public static final BooleanSetting MINIPLAYER_GLASS_SPINNING_ARTWORK = new BooleanSetting("morphe_music_miniplayer_glass_spinning_artwork", FALSE, false, parent(MINIPLAYER_GLASS));
    public static final BooleanSetting MINIPLAYER_GLASS_HIDE_PROGRESS_BAR = new BooleanSetting("morphe_music_miniplayer_glass_hide_progress_bar", FALSE, false, parent(MINIPLAYER_GLASS));
    public static final IntegerSetting MINIPLAYER_GLASS_CORNER_RADIUS = new IntegerSetting("morphe_music_miniplayer_glass_corner_radius", 18, false, parent(MINIPLAYER_GLASS));
    public static final IntegerSetting MINIPLAYER_GLASS_ARTWORK_PADDING = new IntegerSetting("morphe_music_miniplayer_glass_artwork_padding", 7, false, parent(MINIPLAYER_GLASS));
    public static final EnumSetting<HeaderLogo> HEADER_LOGO = new EnumSetting<>("morphe_header_logo", HeaderLogo.DEFAULT, true);

    // Custom filter
    public static final BooleanSetting CUSTOM_FILTER = new BooleanSetting("morphe_music_custom_filter", FALSE);
    public static final StringSetting CUSTOM_FILTER_STRINGS = new StringSetting("morphe_music_custom_filter_strings", "", true, parent(CUSTOM_FILTER));

    // Settings menu filter
    public static final StringSetting SETTINGS_MENU_FILTER_STRINGS = new StringSetting("morphe_music_settings_menu_filter_strings", "", true);
    public static final StringSetting SETTINGS_MENU_FILTER_DISCOVERED = new StringSetting("morphe_music_settings_menu_filter_discovered", "", true, false);

    // Player
    public static final BooleanSetting CHANGE_MINIPLAYER_COLOR = new BooleanSetting("morphe_music_change_miniplayer_color", FALSE, true, parentNot(MINIPLAYER_GLASS));
    public static final BooleanSetting CHANGE_NAVIGATION_BAR_COLOR = new BooleanSetting("morphe_music_change_navigation_bar_color", FALSE, true, parentsAll(parent(CHANGE_MINIPLAYER_COLOR), parentNot(NAVIGATION_BAR_GLASS)));
    public static final BooleanSetting DISABLE_AUDIO_DUCKING = new BooleanSetting("morphe_music_disable_audio_ducking", FALSE, true);
    public static final BooleanSetting DISABLE_DISLIKE_REDIRECTION = new BooleanSetting("morphe_music_disable_dislike_redirection", FALSE, true);
    public static final BooleanSetting ENABLE_FORCED_MINIPLAYER = new BooleanSetting("morphe_music_enable_forced_miniplayer", FALSE, true);
    public static final BooleanSetting ENABLE_SWIPE_TO_DISMISS_MINIPLAYER = new BooleanSetting("morphe_music_enable_swipe_to_dismiss_miniplayer", FALSE, true);
    public static final BooleanSetting HIDE_AUDIO_VIDEO_TOGGLE = new BooleanSetting("morphe_music_hide_audio_video_toggle", FALSE, true);
    public static final BooleanSetting ENABLE_AUDIO_VIDEO_SWITCH = new BooleanSetting("morphe_music_enable_audio_video_switch", FALSE, true, parentNot(Settings.HIDE_AUDIO_VIDEO_TOGGLE));
    public static final BooleanSetting AUTO_SONG_VERSION = new BooleanSetting("morphe_music_auto_song_version", FALSE);
    public static final BooleanSetting HIDE_LYRICS_SHARE_BUTTON = new BooleanSetting("morphe_music_hide_lyrics_share_button", FALSE, true);
    public static final BooleanSetting HIDE_LYRICS_TRANSLATE_BUTTON = new BooleanSetting("morphe_music_hide_lyrics_translate_button", FALSE, true);
    public static final BooleanSetting HIDE_REPEAT_BUTTON = new BooleanSetting("morphe_music_hide_repeat_button", FALSE, true);
    public static final BooleanSetting HIDE_SHUFFLE_BUTTON = new BooleanSetting("morphe_music_hide_shuffle_button", FALSE, true);
    public static final BooleanSetting MINIPLAYER_NEXT_BUTTON = new BooleanSetting("morphe_music_miniplayer_next_button", TRUE, true);
    public static final BooleanSetting MINIPLAYER_PREVIOUS_BUTTON = new BooleanSetting("morphe_music_miniplayer_previous_button", TRUE, true);
    public static final IntegerSetting PLAYBACK_SPEED = new IntegerSetting("morphe_music_playback_speed", 100);
    public static final BooleanSetting PLAYBACK_SPEED_CHANGE_PITCH = new BooleanSetting("morphe_music_playback_speed_change_pitch", FALSE);
    public static final BooleanSetting AB_LOOP = new BooleanSetting("morphe_music_ab_loop", FALSE);
    public static final EnumSetting<TapAction> PLAYLIST_TRACK_TAP_ACTION = new EnumSetting<>("morphe_music_playlist_track_tap_action", TapAction.DEFAULT);

    // Playlist tags
    public static final BooleanSetting PLAYLIST_TAGS = new BooleanSetting("morphe_music_playlist_tags", FALSE, true);
    public static final StringSetting PLAYLIST_TAGS_DATA = new StringSetting("morphe_music_playlist_tags_data", "", false, true);
    public static final StringSetting PLAYLIST_TAGS_KNOWN_PLAYLISTS = new StringSetting("morphe_music_playlist_tags_known_playlists", "", false, false);
    public static final BooleanSetting REMEMBER_REPEAT_STATE = new BooleanSetting("morphe_music_remember_repeat_state", FALSE, true, parentNot(HIDE_REPEAT_BUTTON));
    public static final BooleanSetting REMEMBER_SHUFFLE_STATE = new BooleanSetting("morphe_music_remember_shuffle_state", FALSE, true, parentNot(HIDE_SHUFFLE_BUTTON));
    public static final BooleanSetting SAVED_SHUFFLE_STATE = new BooleanSetting("morphe_music_saved_shuffle_state", FALSE, parent(REMEMBER_SHUFFLE_STATE));

    // Action buttons
    public static final BooleanSetting HIDE_ACTION_BAR = new BooleanSetting("morphe_music_hide_action_bar", FALSE, true);
    public static final BooleanSetting HIDE_LIKE_DISLIKE_BUTTON = new BooleanSetting("morphe_music_hide_like_dislike_button", FALSE, true, parentNot(HIDE_ACTION_BAR));
    public static final BooleanSetting HIDE_COMMENTS_BUTTON = new BooleanSetting("morphe_music_hide_comments_button", FALSE, true, parentNot(HIDE_ACTION_BAR));
    public static final BooleanSetting HIDE_LIVE_CHAT_REPLAY_BUTTON = new BooleanSetting("morphe_music_hide_live_chat_replay_button", FALSE, true, parentNot(HIDE_ACTION_BAR));
    public static final BooleanSetting HIDE_LYRICS_BUTTON = new BooleanSetting("morphe_music_hide_lyrics_button", FALSE, true, parentNot(HIDE_ACTION_BAR));
    public static final BooleanSetting HIDE_SHARE_BUTTON = new BooleanSetting("morphe_music_hide_share_button", FALSE, true, parentNot(HIDE_ACTION_BAR));
    public static final BooleanSetting HIDE_SAVE_BUTTON = new BooleanSetting("morphe_music_hide_save_button", FALSE, true, parentNot(HIDE_ACTION_BAR));
    public static final BooleanSetting HIDE_DOWNLOAD_BUTTON = new BooleanSetting("morphe_music_hide_download_button", FALSE, true, parentNot(HIDE_ACTION_BAR));
    public static final BooleanSetting HIDE_RADIO_BUTTON = new BooleanSetting("morphe_music_hide_radio_button", FALSE, true, parentNot(HIDE_ACTION_BAR));

    // Comments
    public static final BooleanSetting HIDE_COMMENTS_COMMUNITY_GUIDELINES = new BooleanSetting("morphe_music_hide_comments_community_guidelines", FALSE);
    public static final BooleanSetting HIDE_COMMENTS_CONTEXT = new BooleanSetting("morphe_music_hide_comments_context", FALSE);
    public static final BooleanSetting HIDE_COMMENTS_EMOJI_BUTTON = new BooleanSetting("morphe_music_hide_comments_emoji_button", FALSE);
    public static final BooleanSetting HIDE_COMMENTS_GIF_BUTTON = new BooleanSetting("morphe_music_hide_comments_gif_button", FALSE);
    public static final BooleanSetting HIDE_COMMENTS_GIFS_AND_IMAGES = new BooleanSetting("morphe_music_hide_comments_gifs_and_images", FALSE);
    public static final BooleanSetting HIDE_COMMENTS_INFO_BUTTON = new BooleanSetting("morphe_music_hide_comments_info_button", FALSE, true);
    public static final BooleanSetting HIDE_COMMENTS_TIMESTAMP_BUTTON = new BooleanSetting("morphe_music_hide_comments_timestamp_button", FALSE);

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
    public static final EnumSetting<ClientType> SPOOF_VIDEO_STREAMS_CLIENT_TYPE = new EnumSetting<>("morphe_spoof_video_streams_client_type", ClientType.TV_SIMPLY, true, parent(SPOOF_VIDEO_STREAMS));

    public static final BooleanSetting PLAY_ALBUMS_SONGS = new BooleanSetting("morphe_music_play_album_songs", FALSE, true, parent(SPOOF_VIDEO_STREAMS));

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
    public static final BooleanSetting SCROBBLING_GUESS_ALBUM = new BooleanSetting("morphe_music_scrobbling_guess_album", FALSE, true, parentsAny(LISTENBRAINZ_SCROBBLING, LASTFM_SCROBBLING));

    // SoundProof
    public static final BooleanSetting SOUNDPROOF_SHOW_BUTTON = new BooleanSetting("morphe_music_soundproof_show_button", FALSE, true);
    public static final BooleanSetting SOUNDPROOF_SKIP_AI_ARTISTS = new BooleanSetting("morphe_music_soundproof_skip_ai_artists", FALSE, false);
    public static final BooleanSetting SOUNDPROOF_TOAST_ON_SKIP = new BooleanSetting("morphe_music_soundproof_toast_on_skip", TRUE, false, parent(SOUNDPROOF_SKIP_AI_ARTISTS));
    public static final IntegerSetting SOUNDPROOF_MINIMUM_AI_PERCENT = new IntegerSetting("morphe_music_soundproof_minimum_ai_percent", AiDetectionPolicy.DEFAULT_MINIMUM_AI_PERCENT, false, parentsAny(SOUNDPROOF_SHOW_BUTTON, SOUNDPROOF_SKIP_AI_ARTISTS));
    public static final IntegerSetting SOUNDPROOF_MINIMUM_VOTES = new IntegerSetting("morphe_music_soundproof_minimum_votes", AiDetectionPolicy.DEFAULT_MINIMUM_VOTES, false, parentsAny(SOUNDPROOF_SHOW_BUTTON, SOUNDPROOF_SKIP_AI_ARTISTS));

    // Lyrics
    public static final BooleanSetting LYRICS_ENABLED = new BooleanSetting("morphe_music_lyrics_enabled", TRUE, false);
    public static final BooleanSetting LYRICS_KEEP_SCREEN_ON = new BooleanSetting("morphe_music_lyrics_keep_screen_on", FALSE, false, parent(LYRICS_ENABLED));
    public static final String DEFAULT_LYRICS_ORDER =
            "YTMusic,-Captions,Apple,LRCLIB,QQ,NetEase,KuGou,Luna,-PetitLyrics,-bLyrics,-BiniLyrics,-LrcRed,-Unison,-SimpMusic,-AMLL,-LunaBeat,-Lyricify,-Musixmatch,-Spotify,-Deezer,-RMM,-Genius,";
    public static final StringSetting LYRICS_SOURCE = new StringSetting("morphe_music_lyrics_source", DEFAULT_LYRICS_ORDER, false, parent(LYRICS_ENABLED));
    public static final StringSetting APPLE_MUSIC_TOKEN = new StringSetting("morphe_music_apple_music_token", "", false, parent(LYRICS_ENABLED));
    public static final StringSetting SPOTIFY_TOKEN = new StringSetting("morphe_music_spotify_token", "", false, parent(LYRICS_ENABLED));
    public static final StringSetting DEEZER_ARL = new StringSetting("morphe_music_deezer_arl", "", false, parent(LYRICS_ENABLED));
    public static final StringSetting MUSIXMATCH_TOKEN = new StringSetting("morphe_music_musixmatch_token", "", false, parent(LYRICS_ENABLED));
    public static final BooleanSetting LYRICS_TRANSLATE = new BooleanSetting("morphe_music_lyrics_translate", FALSE, false, parent(LYRICS_ENABLED));
    public static final BooleanSetting LYRICS_TRANSLATE_ONLY = new BooleanSetting("morphe_music_lyrics_translate_only", FALSE, false, parent(LYRICS_ENABLED));
    public static final BooleanSetting LYRICS_TAP_TO_SEEK = new BooleanSetting("morphe_music_lyrics_tap_to_seek", TRUE, false, parent(LYRICS_ENABLED));
    public static final BooleanSetting LYRICS_SHOW_COPY_BUTTON = new BooleanSetting("morphe_music_lyrics_show_copy_button", TRUE, false, parent(LYRICS_ENABLED));
    public static final BooleanSetting LYRICS_SHOW_CURRENT_BUTTON = new BooleanSetting("morphe_music_lyrics_show_current_button", TRUE, false, parent(LYRICS_ENABLED));
    public static final BooleanSetting LYRICS_SHOW_SECTION = new BooleanSetting("morphe_music_lyrics_show_section", FALSE, false, parent(LYRICS_ENABLED));
    public static final BooleanSetting LYRICS_SHOW_TRANSLATE_BUTTON = new BooleanSetting("morphe_music_lyrics_show_translate_button", TRUE, false, parent(LYRICS_ENABLED));
    /** A comma separated priority list; a leading '-' disables a provider. */
    public static final StringSetting LYRICS_TRANSLATION_PROVIDERS = new StringSetting("morphe_music_lyrics_translation_provider", ProcessingProviders.DEFAULT_TRANSLATION_ORDER, false, parentsAll(parent(LYRICS_ENABLED), parent(LYRICS_SHOW_TRANSLATE_BUTTON)));
    public static final StringSetting LYRICS_TRANSLATION_LANGUAGE = new StringSetting("morphe_music_lyrics_translation_language", "DEFAULT", false, parentsAll(parent(LYRICS_ENABLED), parent(LYRICS_SHOW_TRANSLATE_BUTTON)));

    public static final BooleanSetting LYRICS_SHOW_ROMANIZE_BUTTON = new BooleanSetting("morphe_music_lyrics_show_romanize_button", TRUE, false, parent(LYRICS_ENABLED));
    /** A comma separated priority list; a leading '-' disables a provider. */
    public static final StringSetting LYRICS_ROMANIZATION_PROVIDERS = new StringSetting("morphe_music_lyrics_romanization_provider", ProcessingProviders.DEFAULT_ROMANIZATION_ORDER, false, parentsAll(parent(LYRICS_ENABLED), parent(LYRICS_SHOW_ROMANIZE_BUTTON)));
    public static final StringSetting LYRICS_ROMANIZE_LANGUAGE = new StringSetting("morphe_music_lyrics_romanize_language", "DEFAULT", false, parentsAll(parent(LYRICS_ENABLED), parent(LYRICS_SHOW_ROMANIZE_BUTTON)));

    /**
     * Comma separated provider names ({@code sudachi}, {@code unidict}, {@code pinyin},
     * {@code cldr}), or {@code none} for a saved choice of none. Blank means the user never chose.
     */
    public static final StringSetting LYRICS_LOCAL_DICTIONARIES = new StringSetting("morphe_music_lyrics_local_dictionaries", "", false, parentsAll(parent(LYRICS_ENABLED), parent(LYRICS_SHOW_ROMANIZE_BUTTON)));

    /**
     * The Rime word tables the user added, one per line, as
     * {@code subtag|label|url|charSource}. They are stored rather than shipped because a word
     * list's license and freshness are the user's to judge; the built-in Cantonese table is not
     * listed here.
     */
    public static final StringSetting LYRICS_RIME_CUSTOM_DICTS = new StringSetting("morphe_music_lyrics_rime_custom_dicts", "", false, parentsAll(parent(LYRICS_ENABLED), parent(LYRICS_SHOW_ROMANIZE_BUTTON)));

    /**
     * The built-in word tables whose switch is on, as comma separated subtags. Empty means none,
     * so a fresh installation downloads nothing until a table is ticked.
     */
    public static final StringSetting LYRICS_RIME_BUILTIN_SELECTED = new StringSetting("morphe_music_lyrics_rime_builtin_selected", "", false, parentsAll(parent(LYRICS_ENABLED), parent(LYRICS_SHOW_ROMANIZE_BUTTON)));

    /**
     * Keep non-Latin readings supplied by the source, such as a Chinese transliteration of a
     * Japanese song. Off accepts only Latin letters, accented letters and tone digits.
     */
    public static final BooleanSetting LYRICS_ALLOW_TRANSLITERATION = new BooleanSetting("morphe_music_lyrics_allow_transliteration", FALSE, false, parentsAll(parent(LYRICS_ENABLED), parent(LYRICS_SHOW_ROMANIZE_BUTTON)));
    public static final StringSetting LYRICS_RUBY_LANGUAGE = new StringSetting("morphe_music_lyrics_ruby_language", "DEFAULT", false, parentsAll(parent(LYRICS_ENABLED), parent(LYRICS_SHOW_ROMANIZE_BUTTON)));
    public static final StringSetting LYRICS_AI_RUBY_PROMPT = new StringSetting("morphe_music_lyrics_ai_ruby_prompt", OpenAIClient.RUBY_PROMPT, false, parentsAll(parent(LYRICS_ENABLED), parent(LYRICS_SHOW_ROMANIZE_BUTTON)));

    public static final StringSetting LYRICS_DEEPL_API_KEY = new StringSetting("morphe_music_lyrics_deepl_api_key", "", false, parent(LYRICS_ENABLED));
    public static final BooleanSetting LYRICS_SHOW_REFRESH_BUTTON = new BooleanSetting("morphe_music_lyrics_show_refresh_button", TRUE, false, parent(LYRICS_ENABLED));
    public static final BooleanSetting LYRICS_USE_AI_SECTION = new BooleanSetting("morphe_music_lyrics_use_ai_section", FALSE, false, parentsAll(parent(LYRICS_ENABLED), parent(LYRICS_SHOW_SECTION)));
    public static final StringSetting LYRICS_AI_BASE_URL = new StringSetting("morphe_music_lyrics_ai_base_url", "https://api.openai.com/v1/chat/completions", false, parent(LYRICS_ENABLED));
    public static final StringSetting LYRICS_AI_API_TOKEN = new StringSetting("morphe_music_lyrics_ai_api_token", "", false, parent(LYRICS_ENABLED));
    public static final StringSetting LYRICS_AI_MODEL = new StringSetting("morphe_music_lyrics_ai_model", "gpt-4o-mini", false, parent(LYRICS_ENABLED));
    public static final StringSetting LYRICS_AI_REASONING_EFFORT = new StringSetting("morphe_music_lyrics_ai_reasoning_effort", "", false, parent(LYRICS_ENABLED));
    public static final StringSetting LYRICS_AI_EXTRA_BODY = new StringSetting("morphe_music_lyrics_ai_extra_body", "", false, parent(LYRICS_ENABLED));
    /** A JSON object laid over the headers of every AI request; an empty value removes one. */
    public static final StringSetting LYRICS_AI_EXTRA_HEADERS = new StringSetting("morphe_music_lyrics_ai_extra_headers", "", false, parent(LYRICS_ENABLED));
    public static final StringSetting LYRICS_AI_TRANSLATION_PROMPT = new StringSetting("morphe_music_lyrics_ai_prompt", OpenAIClient.DEFAULT_PROMPT, false, parentsAll(parent(LYRICS_ENABLED), parent(LYRICS_SHOW_TRANSLATE_BUTTON)));
    public static final StringSetting LYRICS_AI_ROMANIZATION_PROMPT = new StringSetting("morphe_music_lyrics_ai_romanization_prompt", OpenAIClient.DEFAULT_PROMPT, false, parentsAll(parent(LYRICS_ENABLED), parent(LYRICS_SHOW_ROMANIZE_BUTTON)));
    public static final StringSetting LYRICS_AI_SECTION_PROMPT = new StringSetting("morphe_music_lyrics_ai_section_prompt", OpenAIClient.SECTION_PROMPT, false, parent(LYRICS_ENABLED));
    public static final StringSetting LYRICS_TRANSCRIBE_KEY = new StringSetting("morphe_music_lyrics_transcribe_key", "", false, parent(LYRICS_ENABLED));
    public static final BooleanSetting LYRICS_QUICKLRC_ENABLED = new BooleanSetting("morphe_music_lyrics_quicklrc_enabled", FALSE, false, parent(LYRICS_ENABLED));
    public static final BooleanSetting LYRICS_HIDE_INFO = new BooleanSetting("morphe_music_lyrics_hide_info", FALSE, false, parent(LYRICS_ENABLED));
    public static final BooleanSetting LYRICS_SWAP_TRANS_ROMA = new BooleanSetting("morphe_music_lyrics_swap_trans_roma", FALSE, false, parentsAll(parent(LYRICS_ENABLED), parentsAny(LYRICS_SHOW_TRANSLATE_BUTTON, LYRICS_SHOW_ROMANIZE_BUTTON)));
    /** Puts every line in the middle, whatever the format and its duet sides ask for. */
    public static final BooleanSetting LYRICS_FORCE_CENTER = new BooleanSetting("morphe_music_lyrics_force_center", FALSE, false, parent(LYRICS_ENABLED));
    public static final BooleanSetting LYRICS_ROMANIZE = new BooleanSetting("morphe_music_lyrics_romanize", FALSE, false, parent(LYRICS_ENABLED));
    public static final BooleanSetting LYRICS_ROMANIZE_ONLY = new BooleanSetting("morphe_music_lyrics_romanize_only", FALSE, false, parent(LYRICS_ENABLED));
    public static final BooleanSetting LYRICS_WORD_SYNC = new BooleanSetting("morphe_music_lyrics_word_sync", TRUE, false, parent(LYRICS_ENABLED));
    /** Uses spring physics and staggered FLIP cascading for scrolling lyric lines. */
    public static final BooleanSetting LYRICS_SPRING_SCROLL = new BooleanSetting("morphe_music_lyrics_spring_scroll", TRUE, false, parent(LYRICS_ENABLED));
    /** Shows animated three-dot instrumental gap indicators between lyric lines. */
    public static final BooleanSetting LYRICS_GAP_DOTS = new BooleanSetting("morphe_music_lyrics_gap_dots", TRUE, false, parent(LYRICS_ENABLED));
    /** Progressively dims inactive lines for a focused spotlight effect. */
    public static final BooleanSetting LYRICS_FOCUS_SPOTLIGHT = new BooleanSetting("morphe_music_lyrics_focus_spotlight", TRUE, false, parent(LYRICS_ENABLED));
    /** Shrinks every line that is not the one being sung. */
    public static final BooleanSetting LYRICS_LINE_SCALE = new BooleanSetting("morphe_music_lyrics_line_scale", TRUE, false, parent(LYRICS_ENABLED));
    public static final BooleanSetting LYRICS_HIDE_PLAYED = new BooleanSetting("morphe_music_lyrics_hide_played", FALSE, false, parent(LYRICS_ENABLED));
    /** Master switch for separating background vocals and harmony from the lead line. */
    public static final BooleanSetting LYRICS_SPLIT_BACKGROUND = new BooleanSetting("morphe_music_lyrics_split_background", TRUE, false, parent(LYRICS_ENABLED));
    public static final BooleanSetting LYRICS_HIDE_UNPLAYED = new BooleanSetting("morphe_music_lyrics_hide_unplayed", FALSE, false, parent(LYRICS_ENABLED));
    public static final BooleanSetting LYRICS_DYNAMIC_BACKGROUND = new BooleanSetting("morphe_music_lyrics_dynamic_background", TRUE, false, parent(LYRICS_ENABLED));
    public static final BooleanSetting LYRICS_DYNAMIC_BACKGROUND_ANIMATED = new BooleanSetting("morphe_music_lyrics_dynamic_background_animated", TRUE, false, parentsAll(parent(LYRICS_DYNAMIC_BACKGROUND), parent(LYRICS_ENABLED)));
    public static final IntegerSetting LYRICS_TEXT_SIZE = new IntegerSetting("morphe_music_lyrics_text_size", 28, false, parent(LYRICS_ENABLED));
    public static final IntegerSetting LYRICS_OFFSET_MS = new IntegerSetting("morphe_music_lyrics_offset_ms", 0, false, parent(LYRICS_ENABLED));
    public static final BooleanSetting LYRICS_SB_MATCHING = new BooleanSetting("morphe_music_lyrics_sb_matching", TRUE, false, parent(LYRICS_ENABLED));
    public static final BooleanSetting LYRICS_MEDIASESSION = new BooleanSetting("morphe_music_lyrics_mediasession", FALSE, false, parent(LYRICS_ENABLED));
    public static final BooleanSetting LYRICS_MINIPLAYER = new BooleanSetting("morphe_music_lyrics_miniplayer", FALSE, false, parent(LYRICS_ENABLED));
    public static final BooleanSetting LYRICS_FORCE_CAPITALIZE = new BooleanSetting("morphe_music_lyrics_force_capitalize", FALSE, false, parent(LYRICS_ENABLED));
    public static final BooleanSetting LYRICS_STRIP_TRAILING_PUNCTUATION = new BooleanSetting("morphe_music_lyrics_strip_trailing_punctuation", FALSE, false, parent(LYRICS_ENABLED));
    public static final BooleanSetting LYRICS_HAN_FORM = new BooleanSetting("morphe_music_lyrics_han_form", FALSE, false, parent(LYRICS_ENABLED));
    public static final StringSetting LYRICS_MINI_TRACK_FORMAT = new StringSetting("morphe_music_lyrics_mini_track_format", MiniLyricsFormatter.DEFAULT_TRACK_FORMAT, false, parentsAll(parent(LYRICS_ENABLED), parentsAny(LYRICS_MINIPLAYER, LYRICS_MEDIASESSION)));
    public static final StringSetting LYRICS_MINI_ENCAPSULATION = new StringSetting("morphe_music_lyrics_mini_encapsulation", MiniLyricsFormatter.DEFAULT_ENCAPSULATION, false, parentsAll(parent(LYRICS_ENABLED), parentsAny(LYRICS_MINIPLAYER, LYRICS_MEDIASESSION)));
    public static final IntegerSetting LYRICS_MINI_CHAR_LIMIT = new IntegerSetting("morphe_music_lyrics_mini_char_limit", 0, false, parentsAll(parent(LYRICS_ENABLED), parentsAny(LYRICS_MINIPLAYER, LYRICS_MEDIASESSION)));
    public static final IntegerSetting LYRICS_MINI_ARTIST_CHAR_LIMIT = new IntegerSetting("morphe_music_lyrics_mini_artist_char_limit", 0, false, parentsAll(parent(LYRICS_ENABLED), parentsAny(LYRICS_MINIPLAYER, LYRICS_MEDIASESSION)));
    public static final IntegerSetting LYRICS_MINI_ARTIST_MIN_MS = new IntegerSetting("morphe_music_lyrics_mini_artist_min_ms", 1500, false, parentsAll(parent(LYRICS_ENABLED), parentsAny(LYRICS_MINIPLAYER, LYRICS_MEDIASESSION)));
    public static final IntegerSetting LYRICS_MINI_ARTIST_MAX_MS = new IntegerSetting("morphe_music_lyrics_mini_artist_max_ms", 3000, false, parentsAll(parent(LYRICS_ENABLED), parentsAny(LYRICS_MINIPLAYER, LYRICS_MEDIASESSION)));
    public static final FloatSetting LYRICS_MINI_FULL_WIDTH_MULTIPLIER = new FloatSetting("morphe_music_lyrics_mini_full_width_multiplier", 1.75f, false, parentsAll(parent(LYRICS_ENABLED), parentsAny(LYRICS_MINIPLAYER, LYRICS_MEDIASESSION)));
    public static final StringSetting LYRICS_MINI_INSTRUMENTAL = new StringSetting("morphe_music_lyrics_mini_instrumental", MiniLyricsFormatter.DEFAULT_INSTRUMENTAL, false, parentsAll(parent(LYRICS_ENABLED), parentsAny(LYRICS_MINIPLAYER, LYRICS_MEDIASESSION)));
    public static final IntegerSetting LYRICS_MINI_NEXT_OFFSET_MS = new IntegerSetting("morphe_music_lyrics_mini_next_offset_ms", -800, false, parentsAll(parent(LYRICS_ENABLED), parentsAny(LYRICS_MINIPLAYER, LYRICS_MEDIASESSION)));
    public static final BooleanSetting LYRICS_USE_EMBEDDED = new BooleanSetting("morphe_music_lyrics_use_embedded", TRUE, true, parent(LYRICS_ENABLED));
    public static final StringSetting LYRICS_CAPTION_COOKIES = new StringSetting("morphe_music_lyrics_caption_cookies", "", false, parent(LYRICS_ENABLED));

    // Lyrics metadata and line filtering live with the filter itself. Touching that class
    // is what puts the three settings into the framework's registry.
    static {
        LyricsFilterDefaults.register();
    }

    // SponsorBlock
    public static final BooleanSetting SB_ENABLED = new BooleanSetting("morphe_sb_enabled", TRUE);
    public static final BooleanSetting SB_TOAST_ON_SKIP = new BooleanSetting("morphe_sb_toast_on_skip", TRUE, parent(SB_ENABLED));
    public static final BooleanSetting SB_TOAST_ON_CONNECTION_ERROR = new BooleanSetting("morphe_sb_toast_on_connection_error", TRUE, parent(SB_ENABLED));
    public static final StringSetting SB_API_URL = new StringSetting("morphe_sb_api_url", "https://sponsor.ajay.app", parent(SB_ENABLED));
    /** Generated on the first segment submission. Not shown in the settings. */
    public static final StringSetting SB_PRIVATE_USER_ID = new StringSetting("morphe_sb_private_user_id_Do_Not_Share", "");
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
    private static final BooleanSetting DOWNLOAD_BUTTON_OVERRIDE_MIGRATED = new BooleanSetting("morphe_download_button_override_migrated", FALSE, false, false);
    private static final BooleanSetting DEPRECATED_EXTERNAL_DOWNLOADER_ACTION_BUTTON = new BooleanSetting("morphe_external_downloader_action_button", FALSE);
    private static final BooleanSetting DEPRECATED_EXTERNAL_DOWNLOADER_FLYOUT_BUTTON = new BooleanSetting("morphe_external_downloader_flyout_button", FALSE);
    private static final BooleanSetting DEPRECATED_EXTERNAL_DOWNLOADER_FLYOUT_MENU = new BooleanSetting("morphe_external_downloader_flyout_menu", FALSE);
    private static final BooleanSetting DEPRECATED_HIDE_CATEGORY_BAR = new BooleanSetting("morphe_music_hide_category_bar", FALSE, true);
    private static final BooleanSetting DEPRECATED_MUSIC_IN_APP_DOWNLOADS = new BooleanSetting("morphe_music_in_app_downloads", FALSE);
    private static final BooleanSetting DEPRECATED_PERMANENT_REPEAT = new BooleanSetting("morphe_music_permanent_repeat", FALSE, true);
    private static final BooleanSetting DEPRECATED_LYRICS_DISPLAY_ARTIST_FIRST = new BooleanSetting("morphe_music_lyrics_display_artist_first", FALSE, false);
    private static final BooleanSetting DEPRECATED_LYRICS_USE_AI_TRANSLATION = new BooleanSetting("morphe_music_lyrics_use_ai_translation", FALSE, false);
    private static final BooleanSetting DEPRECATED_LYRICS_USE_AI_ROMANIZATION = new BooleanSetting("morphe_music_lyrics_use_ai_romanization", FALSE, false);
    private static final BooleanSetting DEPRECATED_LYRICS_LOCAL_ROMANIZATION = new BooleanSetting("morphe_music_lyrics_local_romanization", FALSE, false);

    static {
        migrateOldSettingToNew(DEPRECATED_EXTERNAL_DOWNLOADER_FLYOUT_BUTTON, DEPRECATED_EXTERNAL_DOWNLOADER_FLYOUT_MENU);
        migrateOldSettingToNew(DEPRECATED_HIDE_CATEGORY_BAR, HIDE_FILTER_BAR);
        migrateOldSettingToNew(DEPRECATED_PERMANENT_REPEAT, REMEMBER_REPEAT_STATE);
        migrateInAppDownloadsToDownloadMode();
        migrateDownloadButtonOverride();
        migrateLyricsArtistFirstToTrackFormat();
        migrateQuickLrcEnabled();
        migrateLyricsProviderSwitches();
    }

    static {
        if (!SPOOF_APP_VERSION_TARGET.isSetToDefault() &&
                SPOOF_APP_VERSION_TARGET.get().compareTo(SPOOF_APP_VERSION_TARGET.defaultValue) < 0) {
            Logger.printInfo(() -> "Resetting spoof app version");
            SPOOF_APP_VERSION_TARGET.resetToDefault();
        }

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
        SeekBarPreference.register(new SeekBarConfig(SOUNDPROOF_MINIMUM_AI_PERCENT,
                5, 100, 5, "%"));
        SeekBarPreference.register(new SeekBarConfig(SOUNDPROOF_MINIMUM_VOTES,
                1, 50, 1, ""));
        SeekBarPreference.register(new SeekBarConfig(NAVIGATION_BAR_GLASS_OPACITY,
                0, 100, 1, "%"));
        SeekBarPreference.register(new SeekBarConfig(NAVIGATION_BAR_GLASS_BLUR,
                0, 40, 1, "dp"));
        SeekBarPreference.register(new SeekBarConfig(NAVIGATION_BAR_GLASS_RIM,
                0, 100, 5, "%"));
        SeekBarPreference.register(new SeekBarConfig(NAVIGATION_BAR_GLASS_HEIGHT,
                60, 160, 5, "%"));
        SeekBarPreference.register(new SeekBarConfig(NAVIGATION_BAR_GLASS_WIDTH,
                50, 100, 5, "%"));
        SeekBarPreference.register(new SeekBarConfig(NAVIGATION_BAR_GLASS_BUTTON_SPACING,
                8, 96, 4, "dp"));
        SeekBarPreference.register(new SeekBarConfig(MINIPLAYER_GLASS_OPACITY,
                0, 100, 1, "%"));
        SeekBarPreference.register(new SeekBarConfig(MINIPLAYER_GLASS_BLUR,
                0, 40, 1, "dp"));
        SeekBarPreference.register(new SeekBarConfig(MINIPLAYER_GLASS_RIM,
                0, 100, 5, "%"));
        SeekBarPreference.register(new SeekBarConfig(MINIPLAYER_GLASS_CORNER_RADIUS,
                8, 32, 1, "dp"));
        SeekBarPreference.register(new SeekBarConfig(MINIPLAYER_GLASS_ARTWORK_PADDING,
                4, 14, 1, "dp"));
        SeekBarPreference.register(new SeekBarConfig(LYRICS_TEXT_SIZE,
                18, 36, 2, "sp"));
        SeekBarPreference.register(new SeekBarConfig(LYRICS_OFFSET_MS,
                -2000, 2000, 100, "ms"));
        SeekBarPreference.register(new SeekBarConfig(LYRICS_MINI_NEXT_OFFSET_MS,
                -2000, 2000, 100, "ms"));
        SeekBarPreference.register(new SeekBarConfig(PLAYBACK_SPEED,
                25, 300, 5, "%"));

        // Must run before any code reads a SegmentCategory setting.
        MusicSponsorBlockConfig.install();
    }

    /**
     * The in-app downloads switch was replaced with the download mode.
     */
    private static void migrateInAppDownloadsToDownloadMode() {
        if (DEPRECATED_MUSIC_IN_APP_DOWNLOADS.isSetToDefault()) {
            return;
        }
        // The switch was only used together with the Download button override.
        if (DOWNLOAD_MODE.isSetToDefault() && DEPRECATED_EXTERNAL_DOWNLOADER_ACTION_BUTTON.get()) {
            DOWNLOAD_MODE.save(DownloadMode.IN_APP);
        }
        DEPRECATED_MUSIC_IN_APP_DOWNLOADS.resetToDefault();
    }

    /** The artist first switch was replaced with the track label format. */
    private static void migrateLyricsArtistFirstToTrackFormat() {
        if (DEPRECATED_LYRICS_DISPLAY_ARTIST_FIRST.get() && LYRICS_MINI_TRACK_FORMAT.isSetToDefault()) {
            LYRICS_MINI_TRACK_FORMAT.save(MiniLyricsFormatter.ARTIST_FIRST_TRACK_FORMAT);
        }
        DEPRECATED_LYRICS_DISPLAY_ARTIST_FIRST.resetToDefault();
    }

    /**
     * Before the switch existed a stored key alone turned QuickLRC on, so leaving the new switch
     * off would take the feature away from everyone who had configured it.
     */
    @SuppressWarnings("ConstantValue")
    private static void migrateQuickLrcEnabled() {
        if (LYRICS_QUICKLRC_ENABLED.isSetToDefault()) {
            return;
        }
        final String key = LYRICS_TRANSCRIBE_KEY.get();
        if (key != null && !key.trim().isEmpty()) {
            LYRICS_QUICKLRC_ENABLED.save(true);
        }
    }

    /** The three switches of v2.0.0 became entries of the provider lists. */
    private static void migrateLyricsProviderSwitches() {
        // Writing the defaults back would pin them, so a future default change would never apply.
        if (DEPRECATED_LYRICS_USE_AI_TRANSLATION.isSetToDefault()
                && DEPRECATED_LYRICS_USE_AI_ROMANIZATION.isSetToDefault()
                && DEPRECATED_LYRICS_LOCAL_ROMANIZATION.isSetToDefault()) {
            return;
        }

        if (DEPRECATED_LYRICS_USE_AI_TRANSLATION.get()) {
            LYRICS_TRANSLATION_PROVIDERS.save(ProcessingProviders.withProviderEnabled(
                    LYRICS_TRANSLATION_PROVIDERS.get(), ProcessingProviders.TRANSLATION_ORDER,
                    ProcessingProviders.OPENAI_COMPATIBLE, true));
        }
        if (DEPRECATED_LYRICS_USE_AI_ROMANIZATION.get()) {
            LYRICS_ROMANIZATION_PROVIDERS.save(ProcessingProviders.withProviderEnabled(
                    LYRICS_ROMANIZATION_PROVIDERS.get(), ProcessingProviders.ROMANIZATION_ORDER,
                    ProcessingProviders.OPENAI_COMPATIBLE, true));
        }
        if (DEPRECATED_LYRICS_LOCAL_ROMANIZATION.get()) {
            LYRICS_ROMANIZATION_PROVIDERS.save(ProcessingProviders.withProviderEnabled(
                    LYRICS_ROMANIZATION_PROVIDERS.get(), ProcessingProviders.ROMANIZATION_ORDER,
                    ProcessingProviders.LOCAL, true));
        }

        DEPRECATED_LYRICS_USE_AI_TRANSLATION.resetToDefault();
        DEPRECATED_LYRICS_USE_AI_ROMANIZATION.resetToDefault();
        DEPRECATED_LYRICS_LOCAL_ROMANIZATION.resetToDefault();
    }

    /**
     * The External downloader mode now always replaces the Download button and menu, and the overrides
     * were removed. Before, the Download button and menu were only replaced if their override was on.
     * The menu overrides are not migrated to the new menu switch, which also applies to in-app downloads.
     * The External downloader mode is still in use, so a marker is the only way to tell this already ran.
     */
    private static void migrateDownloadButtonOverride() {
        if (DOWNLOAD_BUTTON_OVERRIDE_MIGRATED.get()) {
            return;
        }
        final boolean override = DEPRECATED_EXTERNAL_DOWNLOADER_ACTION_BUTTON.get();
        DownloadMode mode = DOWNLOAD_MODE.get();
        if (mode == DownloadMode.DEFAULT && override) {
            DOWNLOAD_MODE.save(DownloadMode.EXTERNAL);
        } else if (mode == DownloadMode.EXTERNAL && !override) {
            // Nothing was replaced.
            DOWNLOAD_MODE.save(DownloadMode.DEFAULT);
        }
        DEPRECATED_EXTERNAL_DOWNLOADER_ACTION_BUTTON.resetToDefault();
        DEPRECATED_EXTERNAL_DOWNLOADER_FLYOUT_MENU.resetToDefault();
        DOWNLOAD_BUTTON_OVERRIDE_MIGRATED.save(true);
    }
}
