/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.sponsorblock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.FloatSetting;
import app.morphe.extension.shared.settings.IntegerSetting;
import app.morphe.extension.shared.settings.LongSetting;
import app.morphe.extension.shared.settings.Setting;
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
import app.morphe.extension.shared.sponsorblock.objects.SegmentCategory;
import app.morphe.extension.shared.sponsorblock.objects.SponsorSegment;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerControlsVisibility;
import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.shared.ShortsPlayerState;
import app.morphe.extension.youtube.shared.VideoState;
import app.morphe.extension.youtube.sponsorblock.ui.SponsorBlockViewController;
import kotlin.Unit;

/**
 * YouTube-side stub class for the SponsorBlock injection points and {@link Configuration}.
 * Injection methods forward to {@link SegmentPlaybackController}; the patches reference this
 * class so the injection surface lives in the host extension package.
 */
public final class YouTubeSponsorBlockConfig implements Configuration {

    private static final YouTubeSponsorBlockConfig INSTANCE = new YouTubeSponsorBlockConfig();

    private static volatile boolean installed;

    /**
     * Idempotent. Configures {@link SponsorBlockApi} on first call and wires player observers.
     */
    public static void install() {
        if (installed) return;
        synchronized (YouTubeSponsorBlockConfig.class) {
            if (installed) return;
            SponsorBlockApi.configure(INSTANCE);

            PlayerType.getOnChange().addObserver((PlayerType type) -> {
                SegmentPlaybackController.onPlayerTypeChanged();
                return Unit.INSTANCE;
            });

            VideoInformation.onChannelIdChange.addObserver((String channelId) -> {
                SegmentPlaybackController.onChannelIdChange(channelId);
                return Unit.INSTANCE;
            });

            // Mirrors the side effects the legacy SponsorBlockPreferenceGroup applied from its
            // updateUI() pass when the user changed an SB setting.
            Setting.preferences.preferences.registerOnSharedPreferenceChangeListener(
                    (prefs, key) -> Utils.runOnMainThread(() -> applySettingSideEffect(key)));

            installed = true;
        }
    }

    private static void applySettingSideEffect(String key) {
        if (key == null) return;
        if (key.equals(Settings.SB_ENABLED.key)) {
            if (!Settings.SB_ENABLED.get()) {
                SponsorBlockViewController.hideAll();
                SegmentPlaybackController.setCurrentVideoId(null);
            }
            SponsorBlockViewController.updateLayout();
        } else if (key.equals(Settings.SB_CREATE_NEW_SEGMENT.key)) {
            if (!Settings.SB_CREATE_NEW_SEGMENT.get()) {
                SponsorBlockViewController.hideNewSegmentLayout();
            }
        } else if (key.equals(Settings.SB_SQUARE_LAYOUT.key)
                || key.equals(Settings.SB_COMPACT_SKIP_BUTTON.key)) {
            SponsorBlockViewController.updateLayout();
        }
    }

    /**
     * Injection point. Installs the configuration before delegating to the shared controller.
     *
     * @param ignoredPlayerController unused; matches the patch's onCreateHook signature.
     */
    @SuppressWarnings("unused")
    public static void initialize(VideoInformation.PlaybackController ignoredPlayerController) {
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

    /** Injection point. */
    @SuppressWarnings("unused")
    public static void setSeekbarRectangle(@Nullable Rect rect) {
        SegmentPlaybackController.setSeekbarRectangle(rect);
    }

    /** Injection point. */
    @SuppressWarnings("unused")
    public static void setSeekbarThickness(int thickness) {
        SegmentPlaybackController.setSeekbarThickness(thickness);
    }

    /** Injection point. */
    @SuppressWarnings("unused")
    public static void drawSegmentTimeBars(final Canvas canvas, final float posY) {
        SegmentPlaybackController.drawSegmentTimeBars(canvas, posY);
    }

    /** Injection point. */
    @SuppressWarnings("unused")
    public static String appendTimeWithoutSegments(String totalTime) {
        return SegmentPlaybackController.appendTimeWithoutSegments(totalTime);
    }

    /** Injection point. */
    @SuppressWarnings("unused")
    public static void setAdProgressTextVisibility(int visibility) {
        SegmentPlaybackController.setAdProgressTextVisibility(visibility);
    }

    private YouTubeSponsorBlockConfig() {}

    @Override public boolean undoToastEnabled()            { return true; }
    @Override public boolean includesHighlight()           { return true; }
    @Override public boolean supportsSegmentCreation()     { return true; }
    @Override public boolean compactSkipButtonEnabled()    { return Settings.SB_COMPACT_SKIP_BUTTON.get(); }
    @Override public boolean viewTrackingEnabled()         { return true; }

    @Override public @NonNull VideoInformationAdapter video()        { return YT_VIDEO; }
    @Override public @NonNull SettingsAdapter settings()             { return YT_SETTINGS; }
    @Override public @NonNull CategorySettingsProvider categorySettings() { return YT_CATEGORY_SETTINGS; }
    @Override public @NonNull PlayerStateAdapter playerState()       { return YT_PLAYER_STATE; }
    @Override public @NonNull UiBridge ui()                          { return YT_UI; }
    @Override public @Nullable ChannelWhitelistAdapter channelWhitelist() { return YT_CHANNEL_WHITELIST; }

    private static final VideoInformationAdapter YT_VIDEO = new VideoInformationAdapter() {
        @Override public long  getVideoTime()       { return VideoInformation.getVideoTime(); }
        @Override public long  getVideoLength()     { return VideoInformation.getVideoLength(); }
        @Override public float getPlaybackSpeed()   { return VideoInformation.getPlaybackSpeed(); }
        @Override public boolean seekTo(long pos)   { return VideoInformation.seekTo(pos); }
    };

    private static final SettingsAdapter YT_SETTINGS = new SettingsAdapter() {
        @Override public @NonNull BooleanSetting sbEnabled()                { return Settings.SB_ENABLED; }
        @Override public @NonNull BooleanSetting toastOnSkip()              { return Settings.SB_TOAST_ON_SKIP; }
        @Override public @NonNull BooleanSetting toastOnConnectionError()   { return Settings.SB_TOAST_ON_CONNECTION_ERROR; }
        @Override public @NonNull StringSetting  apiUrl()                   { return Settings.SB_API_URL; }
        @Override public @NonNull BooleanSetting videoLengthWithoutSegments()  { return Settings.SB_VIDEO_LENGTH_WITHOUT_SEGMENTS; }
        @Override public @NonNull BooleanSetting toastOnWhitelistedChannel()   { return Settings.SB_TOAST_ON_WHITELISTED_CHANNEL; }
        @Override public @NonNull BooleanSetting autoHideSkipButton()          { return Settings.SB_AUTO_HIDE_SKIP_BUTTON; }
        @Override public @NonNull BooleanSetting userIsVip()                   { return Settings.SB_USER_IS_VIP; }
        @Override public @NonNull StringSetting  privateUserId()               { return Settings.SB_PRIVATE_USER_ID; }
        @Override public @NonNull FloatSetting   segmentMinDurationSeconds()   { return Settings.SB_SEGMENT_MIN_DURATION; }
        @Override public @NonNull LongSetting    lastVipCheck()                { return Settings.SB_LAST_VIP_CHECK; }
        @Override public @NonNull BooleanSetting trackSkipCount()              { return Settings.SB_TRACK_SKIP_COUNT; }
        @Override public @NonNull LongSetting    localTimeSavedMilliseconds()  { return Settings.SB_LOCAL_TIME_SAVED_MILLISECONDS; }
        @Override public @NonNull IntegerSetting localTimeSavedNumberSegments(){ return Settings.SB_LOCAL_TIME_SAVED_NUMBER_SEGMENTS; }
        @Override public long autoHideSkipButtonDurationMs() { return Settings.SB_AUTO_HIDE_SKIP_BUTTON_DURATION.get().adjustedDuration; }
        @Override public long toastOnSkipDurationMs()        { return Settings.SB_TOAST_ON_SKIP_DURATION.get().adjustedDuration; }
    };

    private static final CategorySettingsProvider YT_CATEGORY_SETTINGS = new CategorySettingsProvider() {
        @Override
        public @NonNull StringSetting behaviorFor(@NonNull SegmentCategory category) {
            return switch (category) {
                case SPONSOR        -> Settings.SB_CATEGORY_SPONSOR;
                case SELF_PROMO     -> Settings.SB_CATEGORY_SELF_PROMO;
                case INTERACTION    -> Settings.SB_CATEGORY_INTERACTION;
                case HIGHLIGHT      -> Settings.SB_CATEGORY_HIGHLIGHT;
                case INTRO          -> Settings.SB_CATEGORY_INTRO;
                case OUTRO          -> Settings.SB_CATEGORY_OUTRO;
                case PREVIEW        -> Settings.SB_CATEGORY_PREVIEW;
                case HOOK           -> Settings.SB_CATEGORY_HOOK;
                case FILLER         -> Settings.SB_CATEGORY_FILLER;
                case MUSIC_OFFTOPIC -> Settings.SB_CATEGORY_MUSIC_OFFTOPIC;
                case UNSUBMITTED    -> Settings.SB_CATEGORY_UNSUBMITTED;
            };
        }

        @Override
        public @NonNull StringSetting colorFor(@NonNull SegmentCategory category) {
            return switch (category) {
                case SPONSOR        -> Settings.SB_CATEGORY_SPONSOR_COLOR;
                case SELF_PROMO     -> Settings.SB_CATEGORY_SELF_PROMO_COLOR;
                case INTERACTION    -> Settings.SB_CATEGORY_INTERACTION_COLOR;
                case HIGHLIGHT      -> Settings.SB_CATEGORY_HIGHLIGHT_COLOR;
                case INTRO          -> Settings.SB_CATEGORY_INTRO_COLOR;
                case OUTRO          -> Settings.SB_CATEGORY_OUTRO_COLOR;
                case PREVIEW        -> Settings.SB_CATEGORY_PREVIEW_COLOR;
                case HOOK           -> Settings.SB_CATEGORY_HOOK_COLOR;
                case FILLER         -> Settings.SB_CATEGORY_FILLER_COLOR;
                case MUSIC_OFFTOPIC -> Settings.SB_CATEGORY_MUSIC_OFFTOPIC_COLOR;
                case UNSUBMITTED    -> Settings.SB_CATEGORY_UNSUBMITTED_COLOR;
            };
        }
    };

    private static final PlayerStateAdapter YT_PLAYER_STATE = new PlayerStateAdapter() {
        @Override public boolean isPlaying()                  { return VideoState.getCurrent() == VideoState.PLAYING; }
        @Override public boolean isPaused()                   { return VideoState.getCurrent() == VideoState.PAUSED; }
        @Override public boolean isPictureInPicture()         { return PlayerType.getCurrent() == PlayerType.WATCH_WHILE_PICTURE_IN_PICTURE; }
        @Override public boolean isInlineMinimal()            { return PlayerType.getCurrent() == PlayerType.INLINE_MINIMAL; }
        @Override public boolean isPlayerTypeNoneOrHidden()   { return PlayerType.getCurrent().isNoneOrHidden(); }
        @Override public boolean isShortsOpen()               { return ShortsPlayerState.isOpen(); }
        @Override public boolean playerControlsVisible()      {
            return PlayerControlsVisibility.getCurrent() != PlayerControlsVisibility.PLAYER_CONTROLS_VISIBILITY_HIDDEN;
        }
    };

    private static final UiBridge YT_UI = new UiBridge() {
        @Override public void showSkipSegmentButton(@NonNull SponsorSegment segment)   { SponsorBlockViewController.showSkipSegmentButton(segment); }
        @Override public void hideSkipSegmentButton()                                  { SponsorBlockViewController.hideSkipSegmentButton(); }
        @Override public void setSkipSegment(@NonNull SponsorSegment segment)          { SponsorBlockViewController.setSkipSegment(segment); }
        @Override public void showSkipHighlightButton(@NonNull SponsorSegment segment) { SponsorBlockViewController.showSkipHighlightButton(segment); }
        @Override public void hideSkipHighlightButton()                                { SponsorBlockViewController.hideSkipHighlightButton(); }
        @Override public void hideAll()                                                { SponsorBlockViewController.hideAll(); }
        @Override public @Nullable Context overlayContext()                            { return SponsorBlockViewController.getOverLaysViewGroupContext(); }
        @Override public void showErrorDialog(@NonNull String message)                 { SponsorBlockUtils.showErrorDialog(message); }
        @Override public void notifyNewSegmentPreviewed()                              { SponsorBlockUtils.setNewSponsorSegmentPreviewed(); }
        @Override public void clearUnsubmittedSegmentTimes()                           { SponsorBlockUtils.clearUnsubmittedSegmentTimes(); }
    };

    private static final ChannelWhitelistAdapter YT_CHANNEL_WHITELIST = new ChannelWhitelistAdapter() {
        @Override public boolean isChannelWhitelisted(@Nullable String channelId) {
            return channelId != null && SponsorBlockChannelWhitelist.isChannelWhitelisted(channelId);
        }
        @Override public boolean isCurrentChannelWhitelisted() {
            return SponsorBlockChannelWhitelist.isCurrentChannelWhitelisted();
        }
    };
}
