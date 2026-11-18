/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4404
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.queue;

import android.support.v7.widget.RecyclerView;
import android.view.View;

import androidx.annotation.Nullable;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.youtube.innertube.FeedCardLayoutOuterClass;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.patches.utils.ProtoNode;

/** Reads only the selected collection's identity, never a thumbnail's first video. */
public final class PlaylistCardSource {
    /** Native mounted NodeInfo exposes the actions of its own click handler. */
    public interface TapActions {
        @Nullable
        List<?> patch_getPlaylistTapActions();
    }

    /** Decodes a bound Elements command without executing the native tap. */
    public interface TapCommand {
        MessageLite patch_getPlaylistTapCommand();
    }

    /** A recognized collection without an ID must not fall back to its first video. */
    public record Selection(String playlistId, boolean playlist) {}
    private static final Pattern PLAYLIST_ID = Pattern.compile("[A-Za-z0-9_-]+");
    private static final String PLAYLIST_BROWSE_PREFIX = "VL";
    private static final int LOCKUP_CONTENT_TYPE_PLAYLIST = 2;
    // BrowseEndpoint's stable protocol extension and browseId field.
    private static final int BROWSE_ENDPOINT = 48687626;
    private static final int BROWSE_ID = 2;

    private PlaylistCardSource() {
    }

    static boolean isPlaylistId(String id) {
        return id != null && (id.equals("WL") || id.equals("LL") || id.length() > 2)
                && PLAYLIST_ID.matcher(id).matches();
    }

    /** A generated Mix start, rather than a position in an already playing list. */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    static boolean isMixStart(PlaylistQueueRenderer.WatchEndpoint watch) {
        return VideoInformation.isVideoId(watch.getVideoId())
                && watch.getPlaylistId().startsWith(NativePlaylistQueuePatch.MIX_PLAYLIST_ID_PREFIX)
                && !watch.hasIndex() && watch.getPlaylistSetVideoId().isEmpty();
    }

    @Nullable
    static Selection fromView(View sender) throws InvalidProtocolBufferException {
        int nodeInfoId = ResourceUtils.getIdIdentifier("component_node_info");
        if (nodeInfoId == 0) {
            return null;
        }
        // Only walk the tapped card's ancestors. Its shelf buffer can contain
        // unrelated cards, but each mounted click handler retains its own command.
        for (View view = sender; view != null && !(view instanceof RecyclerView);
             view = view.getParent() instanceof View parent ? parent : null) {
            if (view.getTag(nodeInfoId) instanceof TapActions owner) {
                Selection selection = fromTapActions(owner.patch_getPlaylistTapActions());
                if (selection != null) {
                    return selection;
                }
            }
        }
        return null;
    }

    @Nullable
    static Selection fromTapActions(@Nullable List<?> actions) throws InvalidProtocolBufferException {
        if (actions == null) {
            return null;
        }
        String found = "";
        for (Object action : actions) {
            if (!(action instanceof TapCommand tap)) {
                continue;
            }
            var command = PlaylistQueueRenderer.ElementsCommand.parseFrom(
                    tap.patch_getPlaylistTapCommand().toByteArray()).getInnertubeCommand();
            if (command.hasWatchEndpoint() || command.hasAlternateWatchEndpoint()) {
                // The caller has already classified this mounted card as a collection.
                // Mix cards start through Watch, with the first video as their seed.
                if (!command.hasWatchEndpoint() || !isMixStart(command.getWatchEndpoint())) {
                    return new Selection("", false);
                }
                String id = command.getWatchEndpoint().getPlaylistId();
                if (!found.isEmpty() && !found.equals(id)) {
                    return new Selection("", true);
                }
                found = id;
            }
            var fields = ProtoNode.parse(command.toByteArray());
            if (fields == null) {
                continue;
            }
            for (var field : fields) {
                if (field.getFieldNumber() != BROWSE_ENDPOINT || field.children == null) {
                    continue;
                }
                // Read only the direct BrowseEndpoint, never a menu's nested
                // Share, Download or Premium navigation commands.
                String browseId = "";
                for (var value : field.children) {
                    if (value.getFieldNumber() == BROWSE_ID && value.isText()) {
                        browseId = value.getText();
                    }
                }
                if (!browseId.startsWith(PLAYLIST_BROWSE_PREFIX)) {
                    return new Selection("", false);
                }
                String playlistId = browseId.substring(PLAYLIST_BROWSE_PREFIX.length());
                if (!isPlaylistId(playlistId)) {
                    return new Selection("", false);
                }
                if (!found.isEmpty() && !found.equals(playlistId)) {
                    return new Selection("", true);
                }
                found = playlistId;
            }
        }
        return found.isEmpty() ? null : new Selection(found, true);
    }

    static Selection fromElement(byte[] bytes, boolean collectionCard) throws InvalidProtocolBufferException {
        // Native library cards can supply their renderer directly, without an
        // Elements envelope or a mounted ComponentHost. Its own ID and Browse
        // target must agree; a video's surrounding playlist is insufficient.
        Selection nativeSource = fromRenderer(bytes);
        if (nativeSource != null) {
            return nativeSource;
        }
        var model = FeedCardLayoutOuterClass.Element.parseFrom(bytes).getType().getComponentType().getModel();
        if (model.hasLockupModel()) {
            var card = model.getLockupModel().getViewModel().getLockupViewModel();
            // Native contentType 1 is a video, 2 is a playlist. A music video
            // can carry a Mix command, so its endpoint alone is insufficient.
            boolean playlist = card.getContentType() == LOCKUP_CONTENT_TYPE_PLAYLIST;
            return new Selection(playlist && isPlaylistId(card.getContentId()) ? card.getContentId() : "", playlist);
        }
        if (model.hasVideoWithContextModel()) {
            var data = model.getVideoWithContextModel().getData();
            var thumbnail = data.getVideoData().getThumbnail();
            boolean countedPlaylist = !thumbnail.getVideoCountText().isEmpty();
            if (!countedPlaylist && (!collectionCard || !thumbnail.getTimestampText().isEmpty())) {
                return new Selection("", false);
            }
            var command = PlaylistQueueRenderer.ElementsCommand.parseFrom(data.getOnTap()).getInnertubeCommand();
            if (collectionCard && thumbnail.getIsMix() && !command.hasWatchEndpoint()
                    && !command.hasAlternateWatchEndpoint() && !command.hasWatchPlaylistEndpoint()) {
                // Some Mix taps wrap a dialog/watch choice instead of exposing Watch directly.
                // The selected Mix's own drag URL identifies the list without choosing a branch.
                return new Selection(mixPlaylistId(data.getVideoData().getUrl()), true);
            }
            if (!countedPlaylist && (!command.hasWatchEndpoint() || !isMixStart(command.getWatchEndpoint()))) {
                return new Selection("", false);
            }
            String id = command.hasWatchEndpoint() ? command.getWatchEndpoint().getPlaylistId()
                    : command.hasAlternateWatchEndpoint() ? command.getAlternateWatchEndpoint().getPlaylistId()
                    : command.getWatchPlaylistEndpoint().getPlaylistId();
            return new Selection(isPlaylistId(id) ? id : fromNavigation(data.getOnTap().toByteArray()), true);
        }
        String id = collectionCard ? fromNavigation(bytes) : "";
        return new Selection(id, collectionCard);
    }

    /** Only an unindexed native Mix watch link can supply a missing collection target. */
    private static String mixPlaylistId(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException ex) {
            Logger.printDebug(() -> "Invalid native Mix card URL", ex);
            return "";
        }
        String host = uri.getHost();
        String query = uri.getRawQuery();
        if (!"https".equals(uri.getScheme()) || host == null
                || !(host.equals("youtube.com") || host.endsWith(".youtube.com"))
                || !"/watch".equals(uri.getRawPath()) || query == null) {
            return "";
        }
        String videoId = "";
        String playlistId = "";
        // YouTube's generated video and playlist IDs are already URL-safe.
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            switch (pair[0]) {
                case "index" -> {
                    return "";
                }
                case "v" -> {
                    if (!videoId.isEmpty() || pair.length != 2 || !VideoInformation.isVideoId(pair[1])) {
                        return "";
                    }
                    videoId = pair[1];
                }
                case "list" -> {
                    if (!playlistId.isEmpty() || pair.length != 2 || !pair[1].startsWith(NativePlaylistQueuePatch.MIX_PLAYLIST_ID_PREFIX) || !isPlaylistId(pair[1])) {
                        return "";
                    }
                    playlistId = pair[1];
                }
            }
        }
        return videoId.isEmpty() ? "" : playlistId;
    }

    @Nullable
    private static Selection fromRenderer(byte[] bytes) {
        List<ProtoNode> nodes = ProtoNode.parse(bytes);
        if (nodes == null) {
            return null;
        }
        String id = "";
        for (ProtoNode node : nodes) {
            // Native playlistRenderer.playlistId is field 1. Do not accept
            // arbitrary nested strings, canonical URLs or thumbnail video IDs.
            if (node.getFieldNumber() == 1 && node.isText()) {
                if (!id.isEmpty() || !isPlaylistId(node.getText())) {
                    return new Selection("", false);
                }
                id = node.getText();
            }
        }
        if (id.isEmpty()) {
            return null;
        }
        Set<String> targets = new HashSet<>();
        collectPlaylistIds(nodes, targets);
        boolean playlist = targets.size() == 1 && targets.contains(id);
        return new Selection(playlist ? id : "", playlist);
    }

    static String fromNavigation(byte[] bytes) {
        List<ProtoNode> nodes = ProtoNode.parse(bytes);
        Set<String> ids = new HashSet<>();
        if (nodes != null) {
            collectPlaylistIds(nodes, ids);
        }
        return ids.size() == 1 ? ids.iterator().next() : "";
    }

    private static void collectPlaylistIds(List<ProtoNode> nodes, Set<String> playlistIds) {
        for (ProtoNode node : nodes) {
            if (node.children == null) {
                continue;
            }
            if (node.getFieldNumber() == BROWSE_ENDPOINT) {
                for (ProtoNode field : node.children) {
                    if (field.getFieldNumber() == BROWSE_ID && field.isText()) {
                        String browseId = field.getText();
                        if (browseId.startsWith(PLAYLIST_BROWSE_PREFIX)) {
                            String playlistId = browseId.substring(PLAYLIST_BROWSE_PREFIX.length());
                            if (isPlaylistId(playlistId)) {
                                playlistIds.add(playlistId);
                            }
                        }
                    }
                }
            }
            collectPlaylistIds(node.children, playlistIds);
        }
    }
}
