/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5293
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import androidx.annotation.Nullable;

import app.morphe.extension.music.patches.components.MusicActionButtonsFilter;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.innertube.MusicVideoActionBarElementOuterClass.ActionBarButtonCommand;
import app.morphe.extension.shared.innertube.MusicVideoActionBarElementOuterClass.ActionBarButtonState;
import app.morphe.extension.shared.innertube.MusicVideoActionBarElementOuterClass.ActionBarButtonViewModel;
import app.morphe.extension.shared.innertube.MusicVideoActionBarElementOuterClass.ActionBarInnertubeCommand;
import app.morphe.extension.shared.innertube.MusicVideoActionBarElementOuterClass.EngagementPanelBackgroundStyle;
import app.morphe.extension.shared.innertube.MusicVideoActionBarElementOuterClass.EngagementPanelGlobalConfiguration;
import app.morphe.extension.shared.innertube.MusicVideoActionBarElementOuterClass.EngagementPanelHeader;
import app.morphe.extension.shared.innertube.MusicVideoActionBarElementOuterClass.EngagementPanelIdentifier;
import app.morphe.extension.shared.innertube.MusicVideoActionBarElementOuterClass.EngagementPanelInitialState;
import app.morphe.extension.shared.innertube.MusicVideoActionBarElementOuterClass.EngagementPanelResizeRestriction;
import app.morphe.extension.shared.innertube.MusicVideoActionBarElementOuterClass.EngagementPanelSectionListRenderer;
import app.morphe.extension.shared.innertube.MusicVideoActionBarElementOuterClass.EngagementPanelSurface;
import app.morphe.extension.shared.innertube.MusicVideoActionBarElementOuterClass.EngagementPanelText;
import app.morphe.extension.shared.innertube.MusicVideoActionBarElementOuterClass.EngagementPanelTitleHeaderRenderer;
import app.morphe.extension.shared.innertube.MusicVideoActionBarElementOuterClass.ShowEngagementPanelEndpoint;
import app.morphe.extension.shared.innertube.MusicVideoActionBarElementOuterClass.VideoActionBarButton;
import app.morphe.extension.shared.innertube.MusicVideoActionBarElementOuterClass.VideoActionBarElement;
import app.morphe.extension.shared.innertube.MusicVideoActionBarElementOuterClass.VideoActionBarModel;
import app.morphe.extension.shared.patches.components.ByteArrayFilterGroup;

/**
 * Enables the Lyrics button of the action bar under the player for tracks without official lyrics.
 */
@SuppressWarnings("unused")
public final class LyricsButtonPatch {

    private static final ByteArrayFilterGroup VIDEO_ACTION_BAR_TEMPLATE = new ByteArrayFilterGroup(
            null,
            "video_action_bar.eml"
    );

    /** The lyrics engagement panel, which the Lyrics button opens when the track has lyrics. */
    private static final String LYRICS_PANEL_TAG = "PAmusic_watch_lyrics_panel";

    /** Entity key of the background color the app gives the lyrics panel. */
    private static final String LYRICS_PANEL_BACKGROUND_COLOR_ENTITY_KEY =
            "EjtZVE1fV0FUQ0hfUEFHRV9FTkdBR0VNRU5UX1BBTkVMX0JBQ0tHUk9VTkRfQ09MT1JfRU5USVRZX0tFWSD9AygB";

    private LyricsButtonPatch() {
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
        try {
            if (!Settings.LYRICS_ENABLED.get() || bytes == null
                    || !VIDEO_ACTION_BAR_TEMPLATE.check(bytes).isFiltered()) {
                return bytes;
            }

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
                        || !viewModel.getIconName().contains(MusicActionButtonsFilter.LYRICS_ICON)) {
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

                // The server puts a disabled Lyrics button last. Move it to where the button is
                // when the track has lyrics, which is right after the Like and Dislike buttons.
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
}
