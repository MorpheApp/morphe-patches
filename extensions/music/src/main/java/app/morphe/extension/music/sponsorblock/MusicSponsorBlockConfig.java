/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.sponsorblock;

import android.graphics.Canvas;
import android.graphics.Rect;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.FloatSetting;
import app.morphe.extension.shared.settings.IntegerSetting;
import app.morphe.extension.shared.settings.LongSetting;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.shared.sponsorblock.SegmentPlaybackController;
import app.morphe.extension.shared.sponsorblock.SponsorBlockApi;
import app.morphe.extension.shared.sponsorblock.SponsorBlockApi.CategorySettingsProvider;
import app.morphe.extension.shared.sponsorblock.SponsorBlockApi.ChannelWhitelistAdapter;
import app.morphe.extension.shared.sponsorblock.SponsorBlockApi.Configuration;
import app.morphe.extension.shared.sponsorblock.SponsorBlockApi.PlayerStateAdapter;
import app.morphe.extension.shared.sponsorblock.SponsorBlockApi.SettingsAdapter;
import app.morphe.extension.shared.sponsorblock.SponsorBlockApi.UiBridge;
import app.morphe.extension.shared.sponsorblock.SponsorBlockApi.VideoInformationAdapter;
import app.morphe.extension.shared.sponsorblock.objects.CategoryBehaviour;
import app.morphe.extension.shared.sponsorblock.objects.SegmentCategory;

/**
 * Music-side stub class for the SponsorBlock injection points and {@link Configuration}.
 * Surfaces only the seekbar overlay and auto-skip behavior - UI, voting, whitelist, and
 * highlight features are disabled. Injection methods forward to {@link SegmentPlaybackController}.
 */
public final class MusicSponsorBlockConfig implements Configuration {

    private static final MusicSponsorBlockConfig INSTANCE = new MusicSponsorBlockConfig();

    private static volatile boolean installed;

    /** Idempotent. Configures {@link SponsorBlockApi} on first call. */
    public static void install() {
        if (installed) return;
        synchronized (MusicSponsorBlockConfig.class) {
            if (installed) return;
            SponsorBlockApi.configure(INSTANCE);
            installed = true;
        }
    }

    /** Injection point. Installs the configuration before delegating to the shared controller. */
    @SuppressWarnings("unused")
    public static void initialize() {
        install();
        SegmentPlaybackController.initialize();
    }

    /** Injection point. */
    @SuppressWarnings("unused")
    public static void setVideoTime(long millis) {
        SegmentPlaybackController.setVideoTime(millis);
    }

    /** Injection point. */
    @SuppressWarnings("unused")
    public static void setCurrentVideoId(@Nullable String videoId) {
        SegmentPlaybackController.setCurrentVideoId(videoId);
    }

    /** Injection point. Inset by the rounded-end radius so segment markers align with the
     * visible track instead of the full measured bar. */
    @SuppressWarnings("unused")
    public static void setSeekbarRectangle(@Nullable Rect rect) {
        if (rect == null) {
            SegmentPlaybackController.setSeekbarRectangle(null);
            return;
        }
        final int inset = rect.height() / 2;
        Rect insetRect = new Rect(rect.left + inset, rect.top, rect.right - inset, rect.bottom);
        SegmentPlaybackController.setSeekbarRectangle(insetRect);
    }

    /** Injection point. Draws centered on the stored rect (no posY argument). */
    @SuppressWarnings("unused")
    public static void drawSegmentTimeBars(@NonNull Canvas canvas) {
        SegmentPlaybackController.drawSegmentTimeBars(canvas);
    }

    private MusicSponsorBlockConfig() {}

    @Override public boolean undoToastEnabled()            { return false; }
    @Override public boolean includesHighlight()           { return false; }
    @Override public boolean supportsSegmentCreation()     { return false; }
    @Override public boolean compactSkipButtonEnabled()    { return false; }
    @Override public boolean viewTrackingEnabled()         { return false; }

    /**
     * Music exposes no skip-button overlay, so manual / seekbar-only behaviors would be no-ops.
     */
    private static final CategoryBehaviour[] MUSIC_BEHAVIORS = {
            CategoryBehaviour.SKIP_AUTOMATICALLY,
            CategoryBehaviour.SKIP_AUTOMATICALLY_ONCE,
            CategoryBehaviour.IGNORE
    };

    @Override
    public @NonNull CategoryBehaviour[] availableBehaviors(@NonNull SegmentCategory category) {
        return MUSIC_BEHAVIORS;
    }

    @Override public @NonNull VideoInformationAdapter video()        { return MUSIC_VIDEO; }
    @Override public @NonNull SettingsAdapter settings()             { return MUSIC_SETTINGS; }
    @Override public @NonNull CategorySettingsProvider categorySettings() { return MUSIC_CATEGORY_SETTINGS; }
    @Override public @NonNull PlayerStateAdapter playerState()       { return PlayerStateAdapter.DEFAULT; }
    @Override public @NonNull UiBridge ui()                          { return UiBridge.NO_OP; }
    @Override public @Nullable ChannelWhitelistAdapter channelWhitelist() { return null; }

    private static final VideoInformationAdapter MUSIC_VIDEO = new VideoInformationAdapter() {
        @Override public long  getVideoTime()     { return VideoInformation.getVideoTime(); }
        @Override public long  getVideoLength()   { return VideoInformation.getVideoLength(); }
        @Override public float getPlaybackSpeed() { return 1.0f; } // Music has no playback-speed hook.
        @Override public boolean seekTo(long pos) { return VideoInformation.seekTo(pos); }
    };

    private static final SettingsAdapter MUSIC_SETTINGS = new SettingsAdapter() {
        @Override public @NonNull BooleanSetting sbEnabled()                    { return Settings.SB_ENABLED; }
        @Override public @NonNull BooleanSetting toastOnSkip()                  { return Settings.SB_TOAST_ON_SKIP; }
        @Override public @NonNull BooleanSetting toastOnConnectionError()       { return Settings.SB_TOAST_ON_CONNECTION_ERROR; }
        @Override public @NonNull StringSetting  apiUrl()                       { return Settings.SB_API_URL; }
        @Override public @Nullable BooleanSetting videoLengthWithoutSegments()  { return null; }
        @Override public @Nullable BooleanSetting toastOnWhitelistedChannel()   { return null; }
        @Override public @Nullable BooleanSetting autoHideSkipButton()          { return null; }
        @Override public @Nullable BooleanSetting userIsVip()                   { return null; }
        @Override public @Nullable StringSetting  privateUserId()               { return null; }
        @Override public @Nullable FloatSetting   segmentMinDurationSeconds()   { return null; }
        @Override public @Nullable LongSetting    lastVipCheck()                { return null; }
        @Override public @Nullable BooleanSetting trackSkipCount()              { return null; }
        @Override public @Nullable LongSetting    localTimeSavedMilliseconds()  { return null; }
        @Override public @Nullable IntegerSetting localTimeSavedNumberSegments(){ return null; }
        @Override public long autoHideSkipButtonDurationMs() { return 4000; }
        @Override public long toastOnSkipDurationMs()        { return 4000; }
    };

    private static final CategorySettingsProvider MUSIC_CATEGORY_SETTINGS = new CategorySettingsProvider() {
        @Override
        public @NonNull StringSetting behaviorFor(@NonNull SegmentCategory category) {
            return switch (category) {
                case SPONSOR        -> Settings.SB_CATEGORY_SPONSOR;
                case SELF_PROMO     -> Settings.SB_CATEGORY_SELF_PROMO;
                case INTERACTION    -> Settings.SB_CATEGORY_INTERACTION;
                case INTRO          -> Settings.SB_CATEGORY_INTRO;
                case OUTRO          -> Settings.SB_CATEGORY_OUTRO;
                case PREVIEW        -> Settings.SB_CATEGORY_PREVIEW;
                case HOOK           -> Settings.SB_CATEGORY_HOOK;
                case FILLER         -> Settings.SB_CATEGORY_FILLER;
                case MUSIC_OFFTOPIC -> Settings.SB_CATEGORY_MUSIC_OFFTOPIC;
                // Active category set excludes these - safety net for direct access.
                case HIGHLIGHT, UNSUBMITTED -> throw new UnsupportedOperationException(
                        "Unsupported category: " + category);
            };
        }

        @Override
        public @NonNull StringSetting colorFor(@NonNull SegmentCategory category) {
            return switch (category) {
                case SPONSOR        -> Settings.SB_CATEGORY_SPONSOR_COLOR;
                case SELF_PROMO     -> Settings.SB_CATEGORY_SELF_PROMO_COLOR;
                case INTERACTION    -> Settings.SB_CATEGORY_INTERACTION_COLOR;
                case INTRO          -> Settings.SB_CATEGORY_INTRO_COLOR;
                case OUTRO          -> Settings.SB_CATEGORY_OUTRO_COLOR;
                case PREVIEW        -> Settings.SB_CATEGORY_PREVIEW_COLOR;
                case HOOK           -> Settings.SB_CATEGORY_HOOK_COLOR;
                case FILLER         -> Settings.SB_CATEGORY_FILLER_COLOR;
                case MUSIC_OFFTOPIC -> Settings.SB_CATEGORY_MUSIC_OFFTOPIC_COLOR;
                case HIGHLIGHT, UNSUBMITTED -> throw new UnsupportedOperationException(
                        "Unsupported category: " + category);
            };
        }
    };
}
