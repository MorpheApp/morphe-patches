/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.sponsorblock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.FloatSetting;
import app.morphe.extension.shared.settings.IntegerSetting;
import app.morphe.extension.shared.settings.LongSetting;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.shared.sponsorblock.objects.CategoryBehaviour;
import app.morphe.extension.shared.sponsorblock.objects.SegmentCategory;
import app.morphe.extension.shared.sponsorblock.objects.SponsorSegment;

/**
 * Entry point for the shared SponsorBlock implementation.
 * Each host app supplies a {@link Configuration} that adapts the shared playback logic to its own
 * player, settings, and (optionally) UI components.
 *
 * <p>Two-step boot:
 * <ol>
 *   <li>{@link #configure(Configuration)} during app init.</li>
 *   <li>{@link SegmentPlaybackController#initialize()} from the player-create injection point.</li>
 * </ol>
 *
 * <p>Optional features are gated by adapter presence: a non-null
 * {@link Configuration#channelWhitelist()} enables the whitelist code path, a non-null
 * {@link SettingsAdapter#autoHideSkipButton()} enables auto-hide, etc. The remaining boolean
 * flags cover concepts that cannot be inferred from settings alone.
 */
public final class SponsorBlockApi {

    @Nullable
    private static volatile Configuration configuration;

    private SponsorBlockApi() {}

    public static void configure(@NonNull Configuration configuration) {
        SponsorBlockApi.configuration = Objects.requireNonNull(configuration);
    }

    /**
     * @throws IllegalStateException if {@link #configure(Configuration)} has not been called.
     */
    @NonNull
    public static Configuration config() {
        Configuration c = configuration;
        if (c == null) {
            throw new IllegalStateException("SponsorBlockApi not configured");
        }
        return c;
    }

    /**
     * App-specific configuration plugged into the shared SponsorBlock code.
     */
    public interface Configuration {

        /** Undo-skip toast UI. */
        boolean undoToastEnabled();

        /** Include the {@code HIGHLIGHT} segment category in active iteration and API requests. */
        boolean includesHighlight();

        /**
         * Whether this host app supports creating new SponsorBlock segments. Gates loading of
         * the {@link SegmentCategory#UNSUBMITTED} category's persisted color and behavior.
         */
        boolean supportsSegmentCreation();

        /** Compact skip-button text. When false, position-aware text (beginning/middle/end) is used. */
        boolean compactSkipButtonEnabled();

        /** Send "segment viewed" API requests after auto-skipping (used for SponsorBlock stats). */
        boolean viewTrackingEnabled();

        @NonNull VideoInformationAdapter video();

        @NonNull SettingsAdapter settings();

        @NonNull CategorySettingsProvider categorySettings();

        @NonNull PlayerStateAdapter playerState();

        @NonNull UiBridge ui();

        /**
         * Channel whitelist adapter. Null disables the whitelist code path.
         */
        @Nullable ChannelWhitelistAdapter channelWhitelist();

        /**
         * Behaviors offered in the per-category settings dialog. {@code SKIP_AUTOMATICALLY_ONCE}
         * is excluded for {@code HIGHLIGHT} because single-point segments cannot be skipped
         * repeatedly. Hosts without a skip-button overlay should restrict the returned set.
         */
        default @NonNull CategoryBehaviour[] availableBehaviors(@NonNull SegmentCategory category) {
            if (category == SegmentCategory.HIGHLIGHT) {
                CategoryBehaviour[] all = CategoryBehaviour.values();
                CategoryBehaviour[] filtered = new CategoryBehaviour[all.length - 1];
                int j = 0;
                for (CategoryBehaviour b : all) {
                    if (b != CategoryBehaviour.SKIP_AUTOMATICALLY_ONCE) filtered[j++] = b;
                }
                return filtered;
            }
            return CategoryBehaviour.values();
        }
    }

    /**
     * Bridges the shared controller to the host app's video player.
     */
    public interface VideoInformationAdapter {
        long  getVideoTime();
        long  getVideoLength();
        float getPlaybackSpeed();
        /** @return true if the seek was accepted. */
        boolean seekTo(long position);
    }

    /**
     * Bridges the shared controller to the host app's persisted SponsorBlock settings.
     * Nullable getters indicate the host app does not expose the underlying setting; the
     * corresponding feature is skipped when null.
     */
    public interface SettingsAdapter {
        @NonNull BooleanSetting sbEnabled();
        @NonNull BooleanSetting toastOnSkip();
        @NonNull BooleanSetting toastOnConnectionError();
        @NonNull StringSetting  apiUrl();

        @Nullable BooleanSetting videoLengthWithoutSegments();
        @Nullable BooleanSetting toastOnWhitelistedChannel();
        @Nullable BooleanSetting autoHideSkipButton();
        @Nullable BooleanSetting userIsVip();
        @Nullable StringSetting  privateUserId();
        @Nullable FloatSetting   segmentMinDurationSeconds();
        @Nullable LongSetting    lastVipCheck();
        /** Gates the "segment viewed" API request after auto-skip; null treats as enabled. */
        @Nullable BooleanSetting trackSkipCount();
        /** Cumulative milliseconds saved by skipping. Updated after each non-recorded auto-skip. */
        @Nullable LongSetting    localTimeSavedMilliseconds();
        /** Cumulative number of segments skipped. Updated after each non-recorded auto-skip. */
        @Nullable IntegerSetting localTimeSavedNumberSegments();

        /** Adjusted skip-button auto-hide duration, in ms. */
        long autoHideSkipButtonDurationMs();

        /** Adjusted skipped-segment toast duration, in ms. */
        long toastOnSkipDurationMs();
    }

    /**
     * Resolves the host app's {@link StringSetting} backing each category's behavior and color.
     * The shared {@link SegmentCategory} enum stores no setting references — it asks this provider
     * at runtime so the same enum constants can be backed by different settings in each app.
     */
    public interface CategorySettingsProvider {
        @NonNull StringSetting behaviorFor(@NonNull SegmentCategory category);
        @NonNull StringSetting colorFor(@NonNull SegmentCategory category);
    }

    /**
     * Read-only view of the player state needed by the shared controller.
     * {@link #DEFAULT} returns conservative values that gate nothing — suitable when the host app
     * has no equivalent player-state concepts.
     */
    public interface PlayerStateAdapter {
        boolean isPlaying();
        boolean isPaused();
        boolean isPictureInPicture();
        boolean isInlineMinimal();
        boolean isPlayerTypeNoneOrHidden();
        boolean isShortsOpen();
        boolean playerControlsVisible();

        PlayerStateAdapter DEFAULT = new PlayerStateAdapter() {
            @Override public boolean isPlaying() { return true; }
            @Override public boolean isPaused() { return false; }
            @Override public boolean isPictureInPicture() { return false; }
            @Override public boolean isInlineMinimal() { return false; }
            @Override public boolean isPlayerTypeNoneOrHidden() { return false; }
            @Override public boolean isShortsOpen() { return false; }
            @Override public boolean playerControlsVisible() { return false; }
        };
    }

    /**
     * Bridges the shared controller to app-specific UI components.
     * {@link #NO_OP} is suitable for hosts without a player overlay.
     */
    public interface UiBridge {
        void showSkipSegmentButton(@NonNull SponsorSegment segment);
        void hideSkipSegmentButton();
        void setSkipSegment(@NonNull SponsorSegment segment);
        void showSkipHighlightButton(@NonNull SponsorSegment highlight);
        void hideSkipHighlightButton();
        void hideAll();
        @Nullable android.content.Context overlayContext();

        /**
         * Shown on submit/vote API errors that may carry long, server-supplied text.
         */
        void showErrorDialog(@NonNull String message);

        /**
         * Called after auto-skipping a category-{@code UNSUBMITTED} segment so the host app can
         * advance its segment-creation UI state.
         */
        void notifyNewSegmentPreviewed();

        /**
         * Reset host-app state related to in-progress segment creation. Called from
         * {@link SegmentPlaybackController#initialize()} so previewed times do not leak between videos.
         */
        void clearUnsubmittedSegmentTimes();

        UiBridge NO_OP = new UiBridge() {
            @Override public void showSkipSegmentButton(@NonNull SponsorSegment segment) {}
            @Override public void hideSkipSegmentButton() {}
            @Override public void setSkipSegment(@NonNull SponsorSegment segment) {}
            @Override public void showSkipHighlightButton(@NonNull SponsorSegment highlight) {}
            @Override public void hideSkipHighlightButton() {}
            @Override public void hideAll() {}
            @Override public @Nullable android.content.Context overlayContext() { return null; }
            @Override public void showErrorDialog(@NonNull String message) {
                app.morphe.extension.shared.Utils.showToastLong(message);
            }
            @Override public void notifyNewSegmentPreviewed() {}
            @Override public void clearUnsubmittedSegmentTimes() {}
        };
    }

    /**
     * Looks up per-channel SponsorBlock exclusions. Consulted before issuing a segments request
     * and after segments have been applied.
     */
    public interface ChannelWhitelistAdapter {
        boolean isChannelWhitelisted(@Nullable String channelId);
        boolean isCurrentChannelWhitelisted();
    }
}
