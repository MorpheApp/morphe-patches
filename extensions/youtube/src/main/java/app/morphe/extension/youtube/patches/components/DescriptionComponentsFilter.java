package app.morphe.extension.youtube.patches.components;

import static app.morphe.extension.youtube.patches.LayoutReloadObserverPatch.isActionBarVisible;

import app.morphe.extension.youtube.patches.components.LithoFilterPatch.BufferAsciiStrings;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.ConversionContext.ContextInterface;
import app.morphe.extension.youtube.shared.EngagementPanel;
import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.shared.ShortsPlayerState;

@SuppressWarnings("unused")
final class DescriptionComponentsFilter extends Filter {

    private static final String INFOCARDS_SECTION_PATH = "infocards_section.e";

    private final StringFilterGroup macroMarkersCarousel;
    private final ByteArrayFilterGroupList macroMarkersCarouselGroupList = new ByteArrayFilterGroupList();
    private final StringFilterGroup playlistSection;
    private final ByteArrayFilterGroupList playlistSectionGroupList = new ByteArrayFilterGroupList();
    private final StringFilterGroup featuredSection;
    private final ByteArrayFilterGroupList featuredSectionGroupList = new ByteArrayFilterGroupList();
    private final StringFilterGroup subscribeButton;
    private final StringFilterGroup shortsHowThisWasMadeSection;
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
                        Settings.HIDE_FEATURED_VIDEOS_SECTION,
                        "structured_description_video_lockup"
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

        final StringFilterGroup correctionsSection = new StringFilterGroup(
                Settings.HIDE_CORRECTIONS_SECTION,
                "error_corrections_section"
        );

        final StringFilterGroup transcriptSection = new StringFilterGroup(
                Settings.HIDE_TRANSCRIPT_SECTION,
                "transcript_section"
        );

        final StringFilterGroup howThisWasMadeSection = new StringFilterGroup(
                Settings.HIDE_HOW_THIS_WAS_MADE_SECTION,
                "how_this_was_made_section"
        );

        final StringFilterGroup courseProgressSection = new StringFilterGroup(
                Settings.HIDE_COURSE_PROGRESS_SECTION,
                "course_progress"
        );

        final StringFilterGroup hypePoints = new StringFilterGroup(
                Settings.HIDE_HYPE_POINTS,
                "hype_points_factoid"
        );

        final StringFilterGroup infoCardsSection = new StringFilterGroup(
                Settings.HIDE_INFO_CARDS_SECTION,
                INFOCARDS_SECTION_PATH
        );

        subscribeButton = new StringFilterGroup(
                Settings.HIDE_SUBSCRIBE_BUTTON,
                "subscribe_button"
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

        shortsHowThisWasMadeSection = new StringFilterGroup(
                Settings.HIDE_HOW_THIS_WAS_MADE_SECTION,
                "shelf_header.e",
                "cell_video_attribute.e"
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
                howThisWasMadeSection,
                hypePoints,
                infoCardsSection,
                macroMarkersCarousel,
                playlistSection,
                shortsHowThisWasMadeSection,
                subscribeButton,
                transcriptSection,
                videoDetails
        );
    }

    @Override
    boolean isFiltered(ContextInterface contextInterface,
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
        if (!EngagementPanel.isDescription() && !(PlayerType.getCurrent().isMaximizedOrFullscreen() || isActionBarVisible.get() || ShortsPlayerState.isOpen())) {
            return false;
        }

        if (matchedGroup == subscribeButton) {
            return path.startsWith(INFOCARDS_SECTION_PATH);
        }

        if (matchedGroup == featuredSection) {
            return featuredSectionGroupList.check(buffer).isFiltered();
        }

        if (matchedGroup == playlistSection) {
            if (contentIndex != 0) return false;
            return Settings.HIDE_EXPLORE_SECTION.get() || playlistSectionGroupList.check(buffer).isFiltered();
        }

        if (matchedGroup == macroMarkersCarousel) {
            return contentIndex == 0 && macroMarkersCarouselGroupList.check(buffer).isFiltered();
        }

        if (matchedGroup == shortsHowThisWasMadeSection) {
            return ShortsPlayerState.isOpen() && EngagementPanel.isDescription();
        }

        if (matchedGroup == videoDetails) {
            return videoDetailsBuffer.check(buffer).isFiltered();
        }

        return true;
    }
}
