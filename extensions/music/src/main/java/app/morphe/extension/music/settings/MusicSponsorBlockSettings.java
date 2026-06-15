package app.morphe.extension.music.settings;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;

import app.morphe.extension.music.sponsorblock.objects.SegmentCategory;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.StringSetting;

/**
 * SponsorBlock settings for YouTube Music.
 * Kept in a separate class from Settings to keep the diff minimal.
 */
@SuppressWarnings("unused")
public class MusicSponsorBlockSettings {

    // Core
    public static final BooleanSetting SB_ENABLED =
            new BooleanSetting("morphe_music_sb_enabled", TRUE);
    public static final BooleanSetting SB_TOAST_ON_SKIP =
            new BooleanSetting("morphe_music_sb_toast_on_skip", TRUE);
    public static final BooleanSetting SB_TOAST_ON_CONNECTION_ERROR =
            new BooleanSetting("morphe_music_sb_toast_on_connection_error", TRUE);
    public static final StringSetting SB_API_URL =
            new StringSetting("morphe_music_sb_api_url", "https://sponsor.ajay.app");

    // Categories. The enabled flag keys match the SwitchPreference keys added by the patch;
    // a category is skipped automatically when enabled, ignored otherwise. The default on/off
    // state mirrors SponsorBlock's defaults. Colors are not exposed in the UI and only feed the
    // seekbar overlay.
    public static final BooleanSetting SB_CATEGORY_SPONSOR =
            new BooleanSetting("morphe_music_sb_segments_sponsor", TRUE);
    public static final StringSetting SB_CATEGORY_SPONSOR_COLOR =
            new StringSetting("morphe_music_sb_sponsor_color", "#00D400");

    public static final BooleanSetting SB_CATEGORY_SELF_PROMO =
            new BooleanSetting("morphe_music_sb_segments_selfpromo", TRUE);
    public static final StringSetting SB_CATEGORY_SELF_PROMO_COLOR =
            new StringSetting("morphe_music_sb_selfpromo_color", "#FFFF00");

    public static final BooleanSetting SB_CATEGORY_INTERACTION =
            new BooleanSetting("morphe_music_sb_segments_interaction", TRUE);
    public static final StringSetting SB_CATEGORY_INTERACTION_COLOR =
            new StringSetting("morphe_music_sb_interaction_color", "#CC00FF");

    public static final BooleanSetting SB_CATEGORY_INTRO =
            new BooleanSetting("morphe_music_sb_segments_intro", TRUE);
    public static final StringSetting SB_CATEGORY_INTRO_COLOR =
            new StringSetting("morphe_music_sb_intro_color", "#00FFFF");

    public static final BooleanSetting SB_CATEGORY_OUTRO =
            new BooleanSetting("morphe_music_sb_segments_outro", TRUE);
    public static final StringSetting SB_CATEGORY_OUTRO_COLOR =
            new StringSetting("morphe_music_sb_outro_color", "#0202ED");

    public static final BooleanSetting SB_CATEGORY_PREVIEW =
            new BooleanSetting("morphe_music_sb_segments_preview", FALSE);
    public static final StringSetting SB_CATEGORY_PREVIEW_COLOR =
            new StringSetting("morphe_music_sb_preview_color", "#008FD6");

    public static final BooleanSetting SB_CATEGORY_HOOK =
            new BooleanSetting("morphe_music_sb_segments_hook", FALSE);
    public static final StringSetting SB_CATEGORY_HOOK_COLOR =
            new StringSetting("morphe_music_sb_hook_color", "#395699");

    public static final BooleanSetting SB_CATEGORY_FILLER =
            new BooleanSetting("morphe_music_sb_segments_filler", FALSE);
    public static final StringSetting SB_CATEGORY_FILLER_COLOR =
            new StringSetting("morphe_music_sb_filler_color", "#7300FF");

    public static final BooleanSetting SB_CATEGORY_MUSIC_OFFTOPIC =
            new BooleanSetting("morphe_music_sb_segments_nomusic", TRUE);
    public static final StringSetting SB_CATEGORY_MUSIC_OFFTOPIC_COLOR =
            new StringSetting("morphe_music_sb_music_offtopic_color", "#FF9900");

    private static boolean initialized;

    /**
     * No-op whose only purpose is to force this class to load. Invoking any static member runs the
     * field initializers above, registering every SponsorBlock {@link BooleanSetting} with the
     * global setting registry. Without that the preferences screen and import/export cannot resolve
     * the SponsorBlock switches. Called from {@code Settings}' static initializer.
     *
     * <p>This intentionally does not touch {@link SegmentCategory} (and therefore does not read any
     * stored color) so it is safe to call very early during app startup.
     */
    public static void load() {
    }

    /**
     * Primes the segment colors used by the seekbar overlay. Safe to call repeatedly; runs once.
     * Invoked from the playback controller when a track loads, by which point the app context and
     * stored settings are guaranteed to be available.
     */
    public static void initialize() {
        if (initialized) return;
        initialized = true;
        SegmentCategory.updateEnabledCategories();
    }
}
