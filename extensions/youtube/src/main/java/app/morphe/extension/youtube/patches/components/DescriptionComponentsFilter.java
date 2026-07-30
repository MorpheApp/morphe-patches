/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.components;

import app.morphe.extension.shared.patches.components.BufferAsciiStrings;
import app.morphe.extension.shared.patches.components.ByteArrayFilterGroup;
import app.morphe.extension.shared.patches.components.ByteArrayFilterGroupList;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.patches.components.Filter;
import app.morphe.extension.shared.patches.components.StringFilterGroup;
import app.morphe.extension.youtube.patches.LayoutReloadObserverPatch;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.EngagementPanel;
import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.shared.ShortsPlayerState;

@SuppressWarnings("unused")
public final class DescriptionComponentsFilter extends Filter {

    private static final String INFOCARDS_SECTION_PATH = "infocards_section.e";

    private final StringFilterGroup featuredSection;
    private final ByteArrayFilterGroupList featuredSectionGroupList = new ByteArrayFilterGroupList();
    private final StringFilterGroup hashtagSection;
    private final ByteArrayFilterGroup hashtagSectionBuffer;
    private final StringFilterGroup macroMarkersCarousel;
    private final ByteArrayFilterGroupList macroMarkersCarouselGroupList = new ByteArrayFilterGroupList();
    private final StringFilterGroup playlistSection;
    private final ByteArrayFilterGroupList playlistSectionGroupList = new ByteArrayFilterGroupList();
    private final StringFilterGroup shortsHowThisWasMadeSection;
    private final StringFilterGroup subscribeButton;
    private final StringFilterGroup videoDetails;
    private final ByteArrayFilterGroup videoDetailsBuffer;

    public DescriptionComponentsFilter() {
        final StringFilterGroup aiGeneratedVideoSummarySection = new StringFilterGroup(
                Settings.HIDE_AI_GENERATED_VIDEO_SUMMARY_SECTION,
                "cell_expandable_metadata.e"
        );

        final StringFilterGroup askSection = new StringFilterGroup(
                Settings.HIDE_ASK_SECTION,
                "input_composer_button.e",
                "youchat_entrypoint.e"
        );

        final StringFilterGroup correctionsSection = new StringFilterGroup(
                Settings.HIDE_CORRECTIONS_SECTION,
                "error_corrections_section"
        );

        final StringFilterGroup courseProgressSection = new StringFilterGroup(
                Settings.HIDE_COURSE_PROGRESS_SECTION,
                "course_progress"
        );

        featuredSection = new StringFilterGroup(
                null,
                "compact_infocard.e"
        );

        featuredSectionGroupList.addAll(
                new ByteArrayFilterGroup(
                        Settings.HIDE_FEATURED_CHANNELS_SECTION,
                        "structured_description_channel_lockup"
                ),
                new ByteArrayFilterGroup(
                        Settings.HIDE_FEATURED_LINKS_SECTION,
                        "media_lockup"
                ),
                new ByteArrayFilterGroup(
                        Settings.HIDE_FEATURED_PLAYLISTS_SECTION,
                        "structured_description_playlist_lockup"
                ),
                new ByteArrayFilterGroup(
                        Settings.HIDE_FEATURED_VIDEOS_SECTION,
                        "structured_description_video_lockup"
                )
        );

        hashtagSection = new StringFilterGroup(
                Settings.HIDE_HASHTAG_SECTION,
                "|CellType|ScrollableContainerType|"
        );

        hashtagSectionBuffer = new ByteArrayFilterGroup(
                null,
                "FEhashtag"
        );

        final StringFilterGroup howThisWasMadeSection = new StringFilterGroup(
                Settings.HIDE_HOW_THIS_WAS_MADE_SECTION,
                "how_this_was_made_section"
        );

        final StringFilterGroup hypePoints = new StringFilterGroup(
                Settings.HIDE_HYPE_POINTS,
                "hype_points_factoid"
        );

        final StringFilterGroup infoCardsSection = new StringFilterGroup(
                Settings.HIDE_INFO_CARDS_SECTION,
                INFOCARDS_SECTION_PATH
        );

        final StringFilterGroup lensSection = new StringFilterGroup(
                Settings.HIDE_SEARCH_INSIDE_THIS_VIDEO_SECTION,
                "lens_section.e"
        );

        macroMarkersCarousel = new StringFilterGroup(
                null,
                "macro_markers_carousel.e"
        );

        macroMarkersCarouselGroupList.addAll(
                new ByteArrayFilterGroup(
                        Settings.HIDE_CHAPTERS_SECTION,
                        "chapters_horizontal_shelf",
                        "auto-chapters",
                        "description-chapters"
                ),
                new ByteArrayFilterGroup(
                        Settings.HIDE_KEY_CONCEPTS_SECTION,
                        "learning_concept_macro_markers_carousel_shelf",
                        "learning-concept"
                )
        );

        playlistSection = new StringFilterGroup(
                // YT v20.14.43 doesn't use any buffer for Courses and Podcasts.
                // So this component is also needed.
                null,
                "playlist_section.e"
        );

        playlistSectionGroupList.addAll(
                new ByteArrayFilterGroup(
                        Settings.HIDE_EXPLORE_COURSE_SECTION,
                        "yt_outline_creator_academy",
                        "yt_outline_experimental_graduation_cap"
                ),
                new ByteArrayFilterGroup(
                        Settings.HIDE_EXPLORE_PODCAST_SECTION,
                        "FEpodcasts_destination",
                        "yt_outline_experimental_podcast"
                )
        );

        shortsHowThisWasMadeSection = new StringFilterGroup(
                Settings.HIDE_HOW_THIS_WAS_MADE_SECTION,
                "shelf_header.e",
                "cell_video_attribute.e"
        );

        final StringFilterGroup transcriptSection = new StringFilterGroup(
                Settings.HIDE_TRANSCRIPT_SECTION,
                "transcript_section"
        );

        subscribeButton = new StringFilterGroup(
                Settings.HIDE_SUBSCRIBE_BUTTON,
                "subscribe_button"
        );

        videoDetails = new StringFilterGroup(
                null,
                "linear_layout.e"
        );

        videoDetailsBuffer = new ByteArrayFilterGroup(
                Settings.HIDE_VIDEO_DETAILS_SECTION,
                "section_header"
        );

        addPathCallbacks(
                aiGeneratedVideoSummarySection,
                askSection,
                correctionsSection,
                courseProgressSection,
                featuredSection,
                hashtagSection,
                howThisWasMadeSection,
                hypePoints,
                infoCardsSection,
                lensSection,
                macroMarkersCarousel,
                playlistSection,
                shortsHowThisWasMadeSection,
                subscribeButton,
                transcriptSection,
                videoDetails
        );
    }

    @Override
    public boolean isFiltered(ContextInterface contextInterface,
                              String identifier,
                              String accessibility,
                              String path,
                              byte[] buffer,
                              BufferAsciiStrings asciiStrings,
                              StringFilterGroup matchedGroup,
                              FilterContentType contentType,
                              int contentIndex) {
        // Immediately after the layout is refreshed, litho components are updated before the UI is drawn.
        // In this case, EngagementPanel.isDescription() cannot be used, and isActionBarVisible.get() should be used.
        if (!EngagementPanel.isDescription() && !(PlayerType.getCurrent().isMaximizedOrFullscreen() ||
                LayoutReloadObserverPatch.isActionBarVisible.get() || ShortsPlayerState.isOpen())) {
            return false;
        }

        if (matchedGroup == featuredSection) {
            return featuredSectionGroupList.check(buffer).isFiltered();
        }

        if (matchedGroup == hashtagSection) {
            return hashtagSectionBuffer.check(buffer).isFiltered();
        }

        if (matchedGroup == macroMarkersCarousel) {
            return contentIndex == 0 && macroMarkersCarouselGroupList.check(buffer).isFiltered();
        }

        if (matchedGroup == playlistSection) {
            if (contentIndex != 0) return false;
            return Settings.HIDE_EXPLORE_SECTION.get() || playlistSectionGroupList.check(buffer).isFiltered();
        }

        if (matchedGroup == shortsHowThisWasMadeSection) {
            return ShortsPlayerState.isOpen() && EngagementPanel.isDescription();
        }

        if (matchedGroup == subscribeButton) {
            return path.startsWith(INFOCARDS_SECTION_PATH);
        }

        if (matchedGroup == videoDetails) {
            return videoDetailsBuffer.check(buffer).isFiltered();
        }

        return true;
    }
}
