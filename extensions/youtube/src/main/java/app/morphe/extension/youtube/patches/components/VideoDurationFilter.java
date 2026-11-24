/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5277
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.components;

import static app.morphe.extension.shared.StringRef.str;

import androidx.annotation.Nullable;

import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.components.BufferPhraseFilter;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.patches.components.StringFilterGroup;
import app.morphe.extension.youtube.patches.utils.ProtoNode;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Hides the videos of the home feed, the search results and the Subscriptions feed
 * that are shorter or longer than set.
 * <p>
 * The duration is the text of the length badge of the thumbnail, such as "45:41", which is
 * also present when the badge is not shown. Live streams and Shorts have no length badge,
 * so they are not hidden.
 */
@SuppressWarnings("unused")
public final class VideoDurationFilter extends BufferPhraseFilter {

    /**
     * Fields of the length badge of the classic feed video layout (video_lockup_with_attachment.eml):
     * videoWithContextModel.data.videoData.thumbnail.timestampText.
     */
    private static final int[] TIMESTAMP_PATH = {232954548, 18, 1, 1, 2};

    /**
     * Fields of the badge of the card layout (lockup.eml): thumbnailBadgeViewModel.text.
     */
    private static final int THUMBNAIL_BADGE_VIEW_MODEL_FIELD = 1128;
    private static final int THUMBNAIL_BADGE_TEXT_FIELD = 2;
    /**
     * Overlays that hold the length badge: thumbnail_bottom_overlay.eml and the badges
     * of playlists and mixes.
     */
    private static final int THUMBNAIL_BOTTOM_OVERLAY_FIELD = 1214;
    private static final int THUMBNAIL_OVERLAY_BADGE_FIELD = 1295;

    @Override
    protected void reparseIfNeeded() {
        // Nothing to parse. The settings are read for each video.
    }

    @Override
    protected boolean isActiveForFeedContext(ContextInterface contextInterface) {
        return KeywordContentFilter.isActiveForFeedContext(contextInterface, Settings.HIDE_VIDEOS_BY_DURATION_HOME,
                Settings.HIDE_VIDEOS_BY_DURATION_SEARCH, Settings.HIDE_VIDEOS_BY_DURATION_SUBSCRIPTIONS);
    }

    @Override
    @Nullable
    protected String matchBuffer(byte[] buffer, CharSequence path, StringFilterGroup matchedGroup,
                                 ContextInterface contextInterface) {
        final long minimumMinutes = Settings.HIDE_VIDEOS_BY_DURATION_MINIMUM.get();
        final long maximumMinutes = Settings.HIDE_VIDEOS_BY_DURATION_MAXIMUM.get();
        if (minimumMinutes <= 0 && maximumMinutes <= 0) {
            return null;
        }

        try {
            List<ProtoNode> root = ProtoNode.parse(buffer);
            if (root == null) {
                return null;
            }

            for (ProtoNode node : ProtoNode.textNodes(root)) {
                if (!isLengthBadgeText(node)) {
                    continue;
                }

                final String text = node.getText();
                final long seconds = parseDurationSeconds(text);
                if (seconds <= 0) {
                    continue;
                }

                if ((minimumMinutes > 0 && seconds < minimumMinutes * 60)
                        || (maximumMinutes > 0 && seconds > maximumMinutes * 60)) {
                    Logger.printDebug(() -> "Hiding video of duration: " + text);
                    return text;
                }

                // The first length badge is the duration of the video.
                return null;
            }
            return null;
        } catch (Exception ex) {
            Logger.printException(() -> "matchBuffer failure", ex);
            return null;
        }
    }

    /**
     * @return If the text is the length badge of the thumbnail, and not the text of
     *         another badge, such as the item count of a playlist, or a text of the metadata.
     */
    private static boolean isLengthBadgeText(ProtoNode node) {
        if (node.pathEndsWith(TIMESTAMP_PATH)) {
            return true;
        }
        if (!node.pathEndsWith(THUMBNAIL_BADGE_VIEW_MODEL_FIELD, THUMBNAIL_BADGE_TEXT_FIELD)) {
            return false;
        }

        ProtoNode badge = node.getParent(); // thumbnailBadgeViewModel
        ProtoNode badgeEntry = badge == null ? null : badge.getParent(); // badges or thumbnailBadges
        ProtoNode overlay = badgeEntry == null ? null : badgeEntry.getParent();
        if (overlay == null) {
            return false;
        }

        final int overlayField = overlay.getFieldNumber();
        return overlayField == THUMBNAIL_BOTTOM_OVERLAY_FIELD || overlayField == THUMBNAIL_OVERLAY_BADGE_FIELD;
    }

    /**
     * @return The duration in seconds, or 0 if the text is not a duration,
     *         such as "LIVE", "Scheduled" or "249 episodes".
     */
    static long parseDurationSeconds(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return 0;
        }

        String[] parts = trimmed.split(":", -1);
        if (parts.length > 3) {
            return 0;
        }

        long seconds = 0;
        for (String part : parts) {
            part = part.trim();
            if (part.isEmpty()) {
                return 0;
            }

            long value = 0;
            for (int i = 0, length = part.length(); i < length; i++) {
                final int digit = Character.digit(part.charAt(i), 10);
                if (digit < 0) {
                    return 0;
                }
                value = value * 10 + digit;
            }
            seconds = seconds * 60 + value;
        }
        return seconds;
    }

    @Override
    protected void onBroadFilterDetected(@Nullable String matched) {
        Utils.showSnackbarLong(str("morphe_hide_videos_by_duration_toast_broad"));
    }
}
