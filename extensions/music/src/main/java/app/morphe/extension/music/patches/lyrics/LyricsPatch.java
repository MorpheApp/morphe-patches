/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import android.media.MediaMetadata;
import android.media.session.PlaybackState;

import androidx.annotation.Nullable;

import app.morphe.extension.music.patches.lyrics.session.LockScreenLyrics;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.innertube.VideoActionBarElementOuterClass.ActionBarButtonCommand;
import app.morphe.extension.shared.innertube.VideoActionBarElementOuterClass.ActionBarButtonState;
import app.morphe.extension.shared.innertube.VideoActionBarElementOuterClass.ActionBarButtonViewModel;
import app.morphe.extension.shared.innertube.VideoActionBarElementOuterClass.ActionBarInnertubeCommand;
import app.morphe.extension.shared.innertube.VideoActionBarElementOuterClass.EngagementPanelBackgroundStyle;
import app.morphe.extension.shared.innertube.VideoActionBarElementOuterClass.EngagementPanelGlobalConfiguration;
import app.morphe.extension.shared.innertube.VideoActionBarElementOuterClass.EngagementPanelHeader;
import app.morphe.extension.shared.innertube.VideoActionBarElementOuterClass.EngagementPanelIdentifier;
import app.morphe.extension.shared.innertube.VideoActionBarElementOuterClass.EngagementPanelInitialState;
import app.morphe.extension.shared.innertube.VideoActionBarElementOuterClass.EngagementPanelResizeRestriction;
import app.morphe.extension.shared.innertube.VideoActionBarElementOuterClass.EngagementPanelSectionListRenderer;
import app.morphe.extension.shared.innertube.VideoActionBarElementOuterClass.EngagementPanelSurface;
import app.morphe.extension.shared.innertube.VideoActionBarElementOuterClass.EngagementPanelText;
import app.morphe.extension.shared.innertube.VideoActionBarElementOuterClass.EngagementPanelTitleHeaderRenderer;
import app.morphe.extension.shared.innertube.VideoActionBarElementOuterClass.ShowEngagementPanelEndpoint;
import app.morphe.extension.shared.innertube.VideoActionBarElementOuterClass.VideoActionBarButton;
import app.morphe.extension.shared.innertube.VideoActionBarElementOuterClass.VideoActionBarElement;
import app.morphe.extension.shared.innertube.VideoActionBarElementOuterClass.VideoActionBarModel;
import app.morphe.extension.shared.patches.components.ByteArrayFilterGroup;

@SuppressWarnings("unused")
public final class LyricsPatch {

    private static final ByteArrayFilterGroup VIDEO_ACTION_BAR_TEMPLATE = new ByteArrayFilterGroup(
            null,
            "video_action_bar.eml"
    );

    /** Part of the icon name of the Lyrics button, which the button has in any language. */
    private static final String LYRICS_BUTTON_ICON = "_quote_";

    /** The lyrics engagement panel, which the Lyrics button opens when the track has lyrics. */
    private static final String LYRICS_PANEL_TAG = "PAmusic_watch_lyrics_panel";

    /** Entity key of the background color the app gives the lyrics panel. */
    private static final String LYRICS_PANEL_BACKGROUND_COLOR_ENTITY_KEY =
            "EjtZVE1fV0FUQ0hfUEFHRV9FTkdBR0VNRU5UX1BBTkVMX0JBQ0tHUk9VTkRfQ09MT1JfRU5USVRZX0tFWSD9AygB";

    private LyricsPatch() {
    }

    /**
     * Injection point.
     * <p>
     * The server disables the Lyrics button of the action bar under the player when it has no
     * lyrics for the track, and tapping it only shows a toast. The button is enabled again with
     * the command it has when the track has lyrics, so the panel can show third-party lyrics.
     */
    @Nullable
    public static byte[] enableLyricsButton(@Nullable byte[] bytes) {
        if (!Settings.LYRICS_ENABLED.get() || bytes == null || !VIDEO_ACTION_BAR_TEMPLATE.check(bytes).isFiltered()) {
            return bytes;
        }

        try {
            VideoActionBarElement element = VideoActionBarElement.parseFrom(bytes);
            var type = element.getType();
            var component = type.getComponentType();
            var model = component.getModel();
            VideoActionBarModel actionBar = model.getVideoActionBarModel();

            for (int i = 0, count = actionBar.getButtonsCount(); i < count; i++) {
                VideoActionBarButton button = actionBar.getButtons(i);
                var holder = button.getButtonViewModel();
                ActionBarButtonViewModel viewModel = holder.getButtonViewModel();
                if (viewModel.getState() != ActionBarButtonState.BUTTON_STATE_DISABLED
                        || !viewModel.getIconName().contains(LYRICS_BUTTON_ICON)) {
                    continue;
                }

                Logger.printDebug(() -> "Enabling Lyrics button");
                ActionBarButtonViewModel enabledViewModel = viewModel.toBuilder()
                        .setState(ActionBarButtonState.BUTTON_STATE_ACTIVE)
                        .setOnTap(showLyricsPanelCommand(viewModel.getTitle()))
                        .clearOnDisabledTap()
                        .build();
                VideoActionBarButton enabledButton = button.toBuilder().setButtonViewModel(
                        holder.toBuilder().setButtonViewModel(enabledViewModel)).build();

                // When a track has lyrics, the app shows the Lyrics button right after the Like
                // and Dislike buttons. The button enabled here stays last, so it is moved there.
                final int lyricsIndex = actionBar.getButtons(0).getButtonViewModel()
                        .hasSegmentedLikeDislikeButtonViewModel() ? 1 : 0;
                VideoActionBarModel.Builder newActionBar = actionBar.toBuilder()
                        .removeButtons(i)
                        .addButtons(lyricsIndex, enabledButton);

                return element.toBuilder().setType(type.toBuilder().setComponentType(
                        component.toBuilder().setModel(model.toBuilder().setVideoActionBarModel(newActionBar)))
                ).build().toByteArray();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "enableLyricsButton failure", ex);
        }

        return bytes;
    }

    /**
     * Same command as the server gives the Lyrics button of a track with lyrics, without the
     * parameters of the panel content. The response of the track updates the content of the
     * panel, and the third-party lyrics are shown over it.
     */
    private static ActionBarButtonCommand showLyricsPanelCommand(String title) {
        EngagementPanelIdentifier identifier = EngagementPanelIdentifier.newBuilder()
                .setSurface(EngagementPanelSurface.ENGAGEMENT_PANEL_SURFACE_WATCH)
                .setTag(LYRICS_PANEL_TAG)
                .build();

        EngagementPanelSectionListRenderer panel = EngagementPanelSectionListRenderer.newBuilder()
                .setHeader(EngagementPanelHeader.newBuilder().setEngagementPanelTitleHeaderRenderer(
                        EngagementPanelTitleHeaderRenderer.newBuilder().setTitle(
                                EngagementPanelText.newBuilder().setSimpleText(title))))
                .setDisablePullRefresh(true)
                .setIsPersistent(true)
                .setIdentifier(identifier)
                .setAllowReplace(true)
                .setResizeRestriction(EngagementPanelResizeRestriction.ENGAGEMENT_PANEL_RESIZE_RESTRICTION_DOWN_ONLY)
                .setBackgroundStyle(EngagementPanelBackgroundStyle.ENGAGEMENT_PANEL_BACKGROUND_STYLE_USING_ENTITY_COLOR)
                .setBackgroundColorEntityKey(LYRICS_PANEL_BACKGROUND_COLOR_ENTITY_KEY)
                .build();

        return ActionBarButtonCommand.newBuilder().setInnertubeCommand(
                ActionBarInnertubeCommand.newBuilder().setShowEngagementPanelEndpoint(
                        ShowEngagementPanelEndpoint.newBuilder()
                                .setIdentifier(identifier)
                                .setGlobalConfiguration(EngagementPanelGlobalConfiguration.newBuilder()
                                        .setInitialState(EngagementPanelInitialState.newBuilder()
                                                .setEngagementPanelSectionListRenderer(panel))
                                        .setReuseModelAndState(true))
                )
        ).build();
    }

    /**
     * Injection point.
     */
    public static void onSetMetadata(MediaMetadata metadata) {
        try {
            LyricsManager.getInstance().onSetMetadata(metadata);
        } catch (Exception ex) {
            Logger.printException(() -> "onSetMetadata failure", ex);
        }
    }

    /**
     * Injection point. Immediately freezes word-sync when MedialibPlayer pauses audio.
     */
    public static void onPauseVideo() {
        Utils.runOnMainThreadNowOrLater(() -> {
            try {
                LyricsManager.getInstance().onPauseVideo();
            } catch (Exception ex) {
                Logger.printException(() -> "onPauseVideo failure", ex);
            }
        });
    }

    /**
     * Injection point. Immediately resumes word-sync when MedialibPlayer resumes audio.
     */
    public static void onPlayVideo() {
        Utils.runOnMainThreadNowOrLater(() -> {
            try {
                LyricsManager.getInstance().onPlayVideo();
            } catch (Exception ex) {
                Logger.printException(() -> "onPlayVideo failure", ex);
            }
        });
    }

    /**
     * Injection point.
     */
    public static void onSetPlaybackState(PlaybackState playbackState) {
        try {
            LyricsManager.getInstance().onSetPlaybackState(playbackState);
        } catch (Exception ex) {
            Logger.printException(() -> "onSetPlaybackState failure", ex);
        }
        try {
            LockScreenLyrics.onPlaybackState(playbackState);
        } catch (Exception ex) {
            Logger.printException(() -> "onSetPlaybackState lock screen failure", ex);
        }
    }
}
