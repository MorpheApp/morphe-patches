/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5227
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.components;

import static app.morphe.extension.shared.StringRef.str;

import androidx.annotation.Nullable;

import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.components.BufferPhraseFilter;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.patches.components.StringFilterGroup;
import app.morphe.extension.youtube.patches.FeedViewCountPatch;
import app.morphe.extension.youtube.patches.utils.CompactNumbers;
import app.morphe.extension.youtube.patches.utils.FeedVideoCardsPatch;
import app.morphe.extension.youtube.patches.utils.ProtoNode;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Hides the videos and Shorts of the home feed, the search results and the Subscriptions feed
 * that have fewer or more views than set.
 * <p>
 * The view count is the shortened view count of the metadata of the element of a video, such as
 * "4.7M", which is the same in every layout and is kept when the view count is not shown.
 * A shortened count is rounded, so 1.2K is 1200 views. Live streams show their viewers instead,
 * so they are not hidden.
 */
@SuppressWarnings("unused")
public final class ViewCountFilter extends BufferPhraseFilter {

    /**
     * Field numbers of the shortened view count inside the metadata of the video, such as "4.7M".
     */
    private static final int[] SHORTENED_VIEW_COUNT_PATH = {FeedViewCountPatch.METADATA_FIELD, 2, 1, 1};

    /**
     * Tag of the metadata of the video. The filter is called for every component of a card,
     * so the elements without it are not parsed.
     */
    private static final byte[] METADATA_TAG = encodeMetadataTag();

    private static byte[] encodeMetadataTag() {
        final int fieldNumber = FeedViewCountPatch.METADATA_FIELD;
        byte[] tag = new byte[CodedOutputStream.computeTagSize(fieldNumber)];
        try {
            CodedOutputStream.newInstance(tag).writeTag(fieldNumber, WireFormat.WIRETYPE_LENGTH_DELIMITED);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        return tag;
    }

    @Override
    protected void reparseIfNeeded() {
        // Nothing to parse. The settings are read for each video.
    }

    @Override
    protected boolean isActiveForFeedContext(ContextInterface contextInterface) {
        return KeywordContentFilter.isActiveForFeedContext(contextInterface, Settings.HIDE_VIDEOS_BY_VIEWS_HOME,
                Settings.HIDE_VIDEOS_BY_VIEWS_SEARCH, Settings.HIDE_VIDEOS_BY_VIEWS_SUBSCRIPTIONS);
    }

    @Override
    @Nullable
    protected String matchBuffer(byte[] buffer, StringFilterGroup matchedGroup,
                                 ContextInterface contextInterface) {
        final long minimum = Settings.HIDE_VIDEOS_BY_VIEWS_MINIMUM.get();
        final long maximum = Settings.HIDE_VIDEOS_BY_VIEWS_MAXIMUM.get();
        if (minimum <= 0 && maximum <= 0) {
            return null;
        }

        if (!FeedVideoCardsPatch.contains(buffer, METADATA_TAG)) {
            return null;
        }

        try {
            List<ProtoNode> root = ProtoNode.parse(buffer);
            if (root == null) {
                return null;
            }

            String viewCountText = null;
            for (ProtoNode node : ProtoNode.textNodes(root)) {
                String text = node.getText();
                if (FeedVideoCardsPatch.isLiveIconText(text)) {
                    return null;
                }
                if (viewCountText == null && node.pathEndsWith(SHORTENED_VIEW_COUNT_PATH)) {
                    viewCountText = text;
                }
            }
            if (viewCountText == null) {
                return null;
            }

            Long views = CompactNumbers.parse(viewCountText, Locale.getDefault());
            if (views == null) {
                String text = viewCountText;
                Logger.printDebug(() -> "Unknown view count: " + text);
                return null;
            }

            if ((minimum > 0 && views < minimum) || (maximum > 0 && views > maximum)) {
                return viewCountText;
            }
            return null;
        } catch (Exception ex) {
            Logger.printException(() -> "matchBuffer failure", ex);
            return null;
        }
    }

    @Override
    protected void onBroadFilterDetected(@Nullable String matched) {
        Utils.showSnackbarLong(str("morphe_hide_videos_by_views_toast_broad"));
    }
}
