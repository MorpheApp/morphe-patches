/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3905
 * https://github.com/MorpheApp/morphe-patches/pull/4890
 * https://github.com/MorpheApp/morphe-patches/pull/4909
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.net.Uri;
import android.util.Base64;

import androidx.annotation.Nullable;

import com.google.protobuf.ByteString;
import com.google.protobuf.MessageLite;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.innertube.Offline.ButtonCommand;
import app.morphe.extension.shared.innertube.Offline.DownloadButtonColor;
import app.morphe.extension.shared.innertube.Offline.DownloadButtonLayout;
import app.morphe.extension.shared.innertube.Offline.DownloadButtonSize;
import app.morphe.extension.shared.innertube.Offline.DownloadButtonStyle;
import app.morphe.extension.shared.innertube.Offline.EntityKey;
import app.morphe.extension.shared.innertube.Offline.FlexibleAction;
import app.morphe.extension.shared.innertube.Offline.FlexibleActionsRow;
import app.morphe.extension.shared.innertube.Offline.FlexibleActionsViewModel;
import app.morphe.extension.shared.innertube.Offline.OfflineCommand;
import app.morphe.extension.shared.innertube.Offline.OfflineDownloadAction;
import app.morphe.extension.shared.innertube.Offline.OfflineDownloadActionCommand;
import app.morphe.extension.shared.innertube.Offline.OfflineFormat;
import app.morphe.extension.shared.innertube.Offline.OfflineFormatType;
import app.morphe.extension.shared.innertube.Offline.OfflinePlaylistAction;
import app.morphe.extension.shared.innertube.Offline.OfflinePlaylistEndpoint;
import app.morphe.extension.shared.innertube.Offline.OfflineabilityRenderer;
import app.morphe.extension.shared.innertube.Offline.PlaylistDownloadButtonViewModel;
import app.morphe.extension.shared.innertube.Offline.PlaylistDownloadStatusEntityId;
import app.morphe.extension.shared.patches.BaseInAppDownloadsPatch;
import app.morphe.extension.shared.patches.DownloadMode;
import app.morphe.extension.youtube.patches.utils.FeedVideoCardsPatch;
import app.morphe.extension.youtube.patches.utils.ProtoNode;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class InAppDownloadsPatch extends BaseInAppDownloadsPatch {

    /**
     * Key of the interaction logger, in the context a command is run with.
     */
    private static final String INTERACTION_LOGGER_KEY = "com.google.android.libraries.youtube.logging.interaction_logger";

    /**
     * Name of the template of the page headers, such as the header of a playlist page.
     */
    private static final byte[] PAGE_HEADER_TEMPLATE = "page_header".getBytes(StandardCharsets.US_ASCII);

    /**
     * Field of the buttons of a page header, in the proto of the element.
     */
    private static final int FLEXIBLE_ACTIONS_VIEW_MODEL_FIELD = 520990934;

    /**
     * Entities the Download button of a playlist reads the download status from,
     * with the keys the app gives them when the playlist is saved.
     */
    private static final int DOWNLOAD_STATUS_ENTITY_TYPE = 141;
    private static final int PLAYLIST_DOWNLOAD_STATUS_ENTITY_ID_TYPE = 306;
    private static final int GENERATION_STATUS_ENTITY_TYPE = 147;
    private static final String PLAYLIST_GENERATION_STATUS_ENTITY_ID = "playlist";
    private static final int ENTITY_PERSISTENCE = 1;

    private static final String PLAYLIST_DOWNLOAD_BUTTON_IDENTIFIER = "playlist-download-button";

    /**
     * Qualities of the quality picker, highest first. Premium offers up to 1080p,
     * but the app can also save the higher qualities.
     */
    private static final OfflineFormatType[] FORMAT_TYPES = {
            OfflineFormatType.OFFLINE_FORMAT_TYPE_HD_2160,
            OfflineFormatType.OFFLINE_FORMAT_TYPE_HD_1440,
            OfflineFormatType.OFFLINE_FORMAT_TYPE_HD_1080,
            OfflineFormatType.OFFLINE_FORMAT_TYPE_HD,
            OfflineFormatType.OFFLINE_FORMAT_TYPE_SD_480,
            OfflineFormatType.OFFLINE_FORMAT_TYPE_SD,
            OfflineFormatType.OFFLINE_FORMAT_TYPE_LD,
    };

    private static final String[] FORMAT_LABELS = {
            "2160p", "1440p", "1080p", "720p", "480p", "360p", "144p"
    };

    /**
     * Height of the highest download quality.
     */
    private static final int HIGHEST_QUALITY_HEIGHT = 2160;

    /**
     * Prefix of the names of {@link OfflineFormatType}, which the format type enum of the app has
     * without the prefix.
     */
    private static final String FORMAT_TYPE_NAME_PREFIX = "OFFLINE_FORMAT_TYPE_";

    /**
     * Names of the constants of the format type enum of the app.
     */
    private static final String QUAD_HD_FORMAT_TYPE_NAME = "HD_1440";
    private static final String ULTRA_HD_FORMAT_TYPE_NAME = "HD_2160";

    /**
     * Labels of the quality setting for the qualities above 1080p, which the app has no labels for.
     */
    private static final int QUAD_HD_LABEL_ID = ResourceUtils.getIdentifier(
            ResourceType.STRING, "morphe_offline_video_quality_1440p");
    private static final int ULTRA_HD_LABEL_ID = ResourceUtils.getIdentifier(
            ResourceType.STRING, "morphe_offline_video_quality_2160p");

    /**
     * Injection point.
     * <p>
     * Called for every command the app resolves.
     * The Download button asks the server which download action to take, and the server answers
     * with the Premium upsell. Instead, the video is saved directly.
     *
     * @param command Command that is resolved.
     * @return A command that saves the video, or null to keep the original.
     */
    @Nullable
    public static byte[] replaceDownloadActionCommand(@Nullable MessageLite command) {
        try {
            if (!isInAppDownloadMode() || command == null) {
                return null;
            }

            OfflineDownloadActionCommand downloadActionCommand =
                    OfflineDownloadActionCommand.parseFrom(command.toByteArray());
            if (!downloadActionCommand.hasDownloadAction()) {
                return null;
            }

            // Playlists are saved with their own endpoint, which is left as it is.
            OfflineDownloadAction downloadAction = downloadActionCommand.getDownloadAction();
            String videoId = downloadAction.getVideoId();
            if (videoId.isEmpty() || downloadAction.hasPlaylistId()) {
                return null;
            }

            Logger.printDebug(() -> "Replacing download action with saving the video: " + videoId);
            return saveVideoCommand(videoId, List.of()).toByteArray();
        } catch (Exception ex) {
            Logger.printException(() -> "replaceDownloadActionCommand failure", ex);
            return null;
        }
    }

    /**
     * Injection point.
     *
     * @param settingCategories            Setting categories of the server.
     * @param offlineSettingCategoryClass  Class of the offline setting category.
     * @return If the offline setting category must be added to the setting categories.
     */
    public static boolean needsOfflineSettingCategory(List<?> settingCategories,
                                                      Class<?> offlineSettingCategoryClass) {
        try {
            if (!isInAppDownloadMode()) {
                return false;
            }

            for (Object settingCategory : settingCategories) {
                if (offlineSettingCategoryClass.isInstance(settingCategory)) {
                    return false;
                }
            }

            Logger.printDebug(() -> "Adding offline setting category");
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "needsOfflineSettingCategory failure", ex);
            return false;
        }
    }

    /**
     * Injection point.
     * <p>
     * Called when the offline setting category is created, for the download quality setting,
     * which the server can turn off.
     *
     * @param original If the server enables the setting.
     */
    public static boolean unlockOfflineSetting(boolean original) {
        return original || isInAppDownloadMode();
    }

    /**
     * Injection point.
     * <p>
     * Called when the offline setting category is created. The 1080p option adds 1080p
     * in front of the download quality formats, which already have it.
     *
     * @param original If the server enables the 1080p option.
     */
    public static boolean isFullHdFormatOptionAvailable(boolean original) {
        return original && !isInAppDownloadMode();
    }

    /**
     * Injection point.
     * <p>
     * Called when the offline setting category is created, for the entries of the download quality setting.
     *
     * @param formatTypes Format types of the server, or an empty list to use the default qualities.
     * @param formatType  Default format type of the smart downloads quality, a constant of the
     *                    format type enum of the app.
     * @return The format types of every quality of the quality picker, highest first.
     */
    public static List<?> getDownloadQualityFormatTypes(List<?> formatTypes, @Nullable Object formatType) {
        try {
            if (!isInAppDownloadMode()) {
                return formatTypes;
            }
            if (!(formatType instanceof Enum<?> type)) {
                Logger.printException(() -> "Unknown format type: " + formatType);
                return formatTypes;
            }

            Object[] constants = type.getDeclaringClass().getEnumConstants();
            List<Object> qualities = new ArrayList<>(FORMAT_TYPES.length);
            for (OfflineFormatType offlineFormatType : FORMAT_TYPES) {
                qualities.add(appFormatType(constants, offlineFormatType));
            }
            return qualities;
        } catch (Exception ex) {
            Logger.printException(() -> "getDownloadQualityFormatTypes failure", ex);
            return formatTypes;
        }
    }

    /**
     * @return The constant of the format type enum of the app, which has the name without the prefix.
     */
    private static Object appFormatType(Object[] constants, OfflineFormatType type) {
        String name = type.name().substring(FORMAT_TYPE_NAME_PREFIX.length());
        for (Object constant : constants) {
            if (((Enum<?>) constant).name().equals(name)) {
                return constant;
            }
        }
        throw new IllegalArgumentException("No format type: " + name);
    }

    /**
     * Injection point.
     * <p>
     * Called once when the offline settings are created. The app hides the download qualities
     * that are taller than the shorter side of the screen, so a 720p screen can only save 720p.
     * Every screen is offered the qualities up to 2160p.
     *
     * @param maxHeight Height of the highest download quality the app offers.
     */
    public static int getMaxDownloadQualityHeight(int maxHeight) {
        return isInAppDownloadMode()
                ? Math.max(maxHeight, HIGHEST_QUALITY_HEIGHT)
                : maxHeight;
    }

    /**
     * Injection point.
     * <p>
     * Called for the label of a download quality, which the app has only up to 1080p.
     *
     * @param formatType Format type of the quality.
     * @return Resource id of the label, or 0 to keep the label of the app.
     */
    public static int getDownloadQualityLabel(@Nullable Object formatType) {
        if (!isInAppDownloadMode() || !(formatType instanceof Enum<?> type)) {
            return 0;
        }
        return switch (type.name()) {
            case QUAD_HD_FORMAT_TYPE_NAME -> QUAD_HD_LABEL_ID;
            case ULTRA_HD_FORMAT_TYPE_NAME -> ULTRA_HD_LABEL_ID;
            default -> 0;
        };
    }

    /**
     * Implemented by the app class that picks the streams of a download quality.
     */
    public interface DownloadStreamSelector {
        /**
         * The stream selection of the app, which uses the same device and server checks as playback.
         *
         * @return The stream of the quality, a stream of a lower quality, or null.
         */
        @Nullable
        Object patch_selectStream(int quality, int maxHeight, int audioQuality,
                                  Object streamingData, boolean audio, Object playerConfig);

        /**
         * @return The stream with its content length, which is fetched if the server did not give it.
         */
        Object patch_fetchContentLength(Object stream) throws IOException;
    }

    /**
     * Implemented by the app class of the streaming data of a video.
     */
    public interface StreamingDataInterface {
        /**
         * @return Every stream of the video.
         */
        List<?> patch_getFormatStreams();
    }

    /**
     * Implemented by the app class of a stream.
     */
    public interface FormatStreamInterface {
        /**
         * @return The format of the stream, a {@link VideoFormat.FormatInterface}.
         */
        Object patch_getFormat();
    }

    /**
     * Height of the highest quality that every device is given, if it has the stream.
     */
    private static final int FULL_HD_HEIGHT = 1080;

    /**
     * Sides of the qualities, as the app sorts streams into them.
     * A stream has the quality of the smallest of these it fits in, either way around.
     */
    private static final int[] QUALITY_LONG_SIDES = {256, 428, 640, 854, 1280, 1920, 2560, 3840, 5120, 7680};
    private static final int[] QUALITY_SHORT_SIDES = {144, 240, 360, 480, 720, 1080, 1440, 2160, 2880, 4320};

    /**
     * Codecs of the stream a quality is forced to, in the order they are tried after the codec
     * the app picked for the other qualities.
     */
    private static final String[] FORCED_STREAM_CODECS = {"vp9", "vp09", "av01", "avc1"};

    /**
     * Injection point.
     * <p>
     * Called for each quality of the quality picker of a download, to pick the video and audio
     * streams of the quality. The picker drops a quality whose stream is the same as one of a lower
     * quality, and the app picks a lower quality if the device or server checks rule out the stream
     * of the quality. These are the same checks as playback, such as which codecs the device can decode.
     *
     * @return The stream of the quality, or the stream the app picked.
     */
    @Nullable
    public static Object selectDownloadStream(Object selector, int quality, int maxHeight, int audioQuality,
                                              Object streamingData, boolean audio, Object playerConfig) {
        DownloadStreamSelector streamSelector = (DownloadStreamSelector) selector;
        Object stream = streamSelector.patch_selectStream(
                quality, maxHeight, audioQuality, streamingData, audio, playerConfig);
        if (audio) {
            return stream;
        }

        try {
            Object videoStream = getDownloadVideoStream(quality, maxHeight, streamingData, stream);
            return videoStream == stream
                    ? stream
                    : streamSelector.patch_fetchContentLength(videoStream);
        } catch (Exception ex) {
            Logger.printException(() -> "selectDownloadStream failure", ex);
            return stream;
        }
    }

    /**
     * Injection point.
     * <p>
     * Called when a download starts, for the video stream it saves. The download picks its streams
     * again with the same checks as the quality picker.
     *
     * @param stream        Video stream the app picked, or null.
     * @param quality       Height of the quality of the download.
     * @param streamingData Streaming data of the video.
     * @return The stream of the quality, or the stream the app picked.
     */
    @Nullable
    public static Object selectDownloadVideoStream(@Nullable Object stream, int quality, Object streamingData) {
        try {
            return getDownloadVideoStream(quality, Integer.MAX_VALUE, streamingData, stream);
        } catch (Exception ex) {
            Logger.printException(() -> "selectDownloadVideoStream failure", ex);
            return stream;
        }
    }

    /**
     * @param stream Video stream the app picked, or null.
     * @return A stream of the quality if the app picked none, and higher qualities are forced,
     *         otherwise the stream the app picked.
     */
    @Nullable
    private static Object getDownloadVideoStream(int quality, int maxHeight, Object streamingData,
                                                 @Nullable Object stream) {
        // A quality of 0 is audio only, which saves no video stream.
        if (quality <= 0 || !isInAppDownloadMode()) {
            return stream;
        }

        VideoFormat.FormatInterface format = stream == null ? null : formatOf(stream);
        if (format != null && qualityOf(format) == quality) {
            return stream;
        }

        List<Object> qualityStreams = new ArrayList<>();
        for (Object formatStream : ((StreamingDataInterface) streamingData).patch_getFormatStreams()) {
            VideoFormat.FormatInterface streamFormat = formatOf(formatStream);
            if (isVideo(streamFormat) && qualityOf(streamFormat) == quality) {
                qualityStreams.add(formatStream);
            }
        }
        if (qualityStreams.isEmpty()) {
            Logger.printDebug(() -> quality + "p is not available, the video has no stream of it");
            return stream;
        }

        Logger.printDebug(() -> {
            String picked = format == null
                    ? "no stream"
                    : qualityOf(format) + "p (" + describe(format) + "), so the quality picker drops it";
            return quality + "p streams " + describeStreams(qualityStreams)
                    + " are ruled out by the device or server checks. The app picked " + picked;
        });

        if (!Settings.DOWNLOAD_FORCE_4K.get() || quality <= FULL_HD_HEIGHT || quality > maxHeight) {
            return stream;
        }

        Object forcedStream = preferredStream(qualityStreams, format);
        Logger.printDebug(() -> "Forcing " + quality + "p stream: " + describe(formatOf(forcedStream)));
        return forcedStream;
    }

    private static VideoFormat.FormatInterface formatOf(Object stream) {
        return (VideoFormat.FormatInterface) ((FormatStreamInterface) stream).patch_getFormat();
    }

    private static boolean isVideo(VideoFormat.FormatInterface format) {
        String mimeType = format.patch_getMimeType();
        return mimeType != null && mimeType.startsWith("video/");
    }

    /**
     * @return The quality the app sorts the stream into, or -1 if it is larger than every quality.
     */
    private static int qualityOf(VideoFormat.FormatInterface format) {
        int width = format.patch_getWidth();
        int height = format.patch_getHeight();
        int longSide = Math.max(width, height);
        int shortSide = Math.min(width, height);
        for (int i = 0; i < QUALITY_SHORT_SIDES.length; i++) {
            if (longSide <= QUALITY_LONG_SIDES[i] && shortSide <= QUALITY_SHORT_SIDES[i]) {
                return QUALITY_SHORT_SIDES[i];
            }
        }
        return -1;
    }

    /**
     * @return The codec of the stream, such as "vp9" or "av01.0.12M.08", or an empty string.
     */
    private static String codecOf(VideoFormat.FormatInterface format) {
        String mimeType = format.patch_getMimeType();
        int start = mimeType == null ? -1 : mimeType.indexOf("codecs=\"");
        if (start < 0) {
            return "";
        }
        start += "codecs=\"".length();
        int end = mimeType.indexOf('"', start);
        return end < 0 ? mimeType.substring(start) : mimeType.substring(start, end);
    }

    /**
     * @return If the codec is a 10-bit HDR codec, which more devices cannot decode.
     */
    private static boolean isHdrCodec(String codec) {
        return codec.startsWith("vp9.2") || codec.startsWith("vp09.02")
                || (codec.startsWith("av01") && codec.contains(".10"));
    }

    /**
     * @param pickedFormat Format of the stream the app picked, whose codec is tried first.
     * @return The stream with the codec the app picked, or else the first codec of
     *         {@link #FORCED_STREAM_CODECS}. Streams that are not HDR come first.
     */
    private static Object preferredStream(List<Object> streams, @Nullable VideoFormat.FormatInterface pickedFormat) {
        List<String> codecs = new ArrayList<>();
        if (pickedFormat != null) {
            String pickedCodec = codecOf(pickedFormat);
            int end = pickedCodec.indexOf('.');
            codecs.add(end < 0 ? pickedCodec : pickedCodec.substring(0, end));
        }
        codecs.addAll(List.of(FORCED_STREAM_CODECS));

        for (boolean allowHdr : new boolean[]{false, true}) {
            for (String codecPrefix : codecs) {
                for (Object stream : streams) {
                    String codec = codecOf(formatOf(stream));
                    if (codec.startsWith(codecPrefix) && (allowHdr || !isHdrCodec(codec))) {
                        return stream;
                    }
                }
            }
        }
        return streams.get(0);
    }

    private static String describe(VideoFormat.FormatInterface format) {
        return "itag " + format.patch_getItag() + " " + format.patch_getWidth() + "x"
                + format.patch_getHeight() + " " + codecOf(format);
    }

    private static String describeStreams(List<Object> streams) {
        StringBuilder builder = new StringBuilder("[");
        for (Object stream : streams) {
            if (builder.length() > 1) {
                builder.append(", ");
            }
            builder.append(describe(formatOf(stream)));
        }
        return builder.append(']').toString();
    }

    /**
     * Injection point.
     * <p>
     * Called when the quality picker of a download is created. The picker opens collapsed to
     * 60% of the screen height, which cuts off the lowest qualities of the longer list.
     *
     * @return If the picker opens fully expanded.
     */
    public static boolean expandDownloadQualityPicker() {
        return isInAppDownloadMode();
    }

    /**
     * Injection point.
     * <p>
     * YouTube override of the base unlock, which additionally adds the default download types
     * the server omitted. The recommended downloads of the Downloads page list only 360p and 144p,
     * since the server omits the qualities it would not automatically download.
     * This also applies to the unlock of the base, which unlocks Premium locked formats
     * but keeps the entries of the server.
     * The same shared save method shows the quality picker of every download entry point,
     * so this keeps every entry point saving with the same qualities.
     * <p>
     * YouTube Music keeps the formats of the server and does not use this method.
     *
     * @param offlineability Offlineability renderer of the video or playlist.
     * @return The renderer with every quality available, or null to keep the original.
     */
    @Nullable
    public static byte[] unlockOfflineability(@Nullable Object offlineability) {
        try {
            if (!isInAppDownloadMode()) {
                return null;
            }

            byte[] unlocked = BaseInAppDownloadsPatch.unlockOfflineability(offlineability);
            byte[] source = unlocked;
            if (source == null && offlineability instanceof MessageLite message) {
                // The base kept the original: the server lists formats and none are Premium locked.
                source = message.toByteArray();
            }
            if (source == null) {
                return null;
            }

            // The base may have unlocked the Premium locked formats, but it always keeps the
            // entries of the server. The types the server omitted are added as well,
            // so the picker shows every quality.
            OfflineabilityRenderer renderer = OfflineabilityRenderer.parseFrom(source);
            List<OfflineFormat> merged = mergeMissingFormatTypes(
                    renderer.getFormatsList(), FORMAT_TYPES, FORMAT_LABELS);
            if (merged == null) {
                return unlocked;
            }

            Logger.printDebug(() -> "Adding offline formats the server omitted");
            return renderer.toBuilder()
                    .clearFormats()
                    .addAllFormats(merged)
                    .build()
                    .toByteArray();
        } catch (Exception ex) {
            Logger.printException(() -> "unlockOfflineability failure", ex);
            return null;
        }
    }

    /**
     * Implemented by the app class that loads the player response of saved videos.
     */
    public interface OfflinePlaybackWorker {
        /**
         * Sets the start time of a saved video played from Downloads
         * to the position saved in its Downloads entry.
         *
         * @param descriptor     Playback start descriptor.
         * @param playerResponse Player response model of the saved video.
         */
        void patch_setDownloadStartTime(Object descriptor, @Nullable Object playerResponse);
    }

    /**
     * Injection point.
     * <p>
     * Called when the player response of a saved video is loaded. The play command of a Downloads card
     * keeps the position from when the card was built, and the app can use an older position than the
     * one saved in Downloads, so a saved video can start at 0:00 or at an older position.
     */
    public static void setDownloadStartTime(OfflinePlaybackWorker worker,
                                            Object descriptor,
                                            @Nullable Object playerResponse) {
        try {
            worker.patch_setDownloadStartTime(descriptor, playerResponse);
        } catch (Exception ex) {
            Logger.printException(() -> "setDownloadStartTime failure", ex);
        }
    }

    /**
     * Injection point.
     * <p>
     * Called for every element the app parses.
     * The server only gives a Download button to the playlist page headers of Premium accounts,
     * so it's added to the headers of other accounts. The button saves the playlist in-app,
     * or opens the external downloader in External downloader mode.
     *
     * @param bytes Proto of the element.
     * @return The element with a Download button, or the original element.
     */
    public static byte[] addPlaylistDownloadButton(byte[] bytes) {
        try {
            if (Settings.DOWNLOAD_MODE.get() == DownloadMode.DEFAULT
                    || !FeedVideoCardsPatch.contains(bytes, PAGE_HEADER_TEMPLATE)) {
                return bytes;
            }

            List<ProtoNode> element = ProtoNode.parse(bytes);
            if (element == null) {
                return bytes;
            }

            for (ProtoNode actionsNode : ProtoNode.findMessages(element, FLEXIBLE_ACTIONS_VIEW_MODEL_FIELD)) {
                FlexibleActionsViewModel actions = withPlaylistDownloadButton(
                        FlexibleActionsViewModel.parseFrom(actionsNode.getPayload()));
                if (actions != null) {
                    actionsNode.setPayload(actions.toByteArray());
                    return ProtoNode.write(element);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "addPlaylistDownloadButton failure", ex);
        }

        return bytes;
    }

    /**
     * @return The buttons with a Download button in the row of the Play all button of a playlist,
     *         or null if the buttons are not of a playlist or already have a Download button.
     */
    @Nullable
    private static FlexibleActionsViewModel withPlaylistDownloadButton(FlexibleActionsViewModel actions) {
        int playlistRowIndex = -1;
        String playlistId = "";

        List<FlexibleActionsRow> rows = actions.getActionsRowsList();
        for (int i = 0; i < rows.size(); i++) {
            for (FlexibleAction action : rows.get(i).getActionsList()) {
                if (action.hasDownloadButtonViewModel()) {
                    return null;
                }
                String id = action.getButtonViewModel().getOnTap().getInnertubeCommand()
                        .getWatchEndpoint().getPlaylistId();
                if (playlistId.isEmpty() && !id.isEmpty()) {
                    playlistRowIndex = i;
                    playlistId = id;
                }
            }
        }
        if (playlistId.isEmpty()) {
            return null;
        }

        return actions.toBuilder()
                .setActionsRows(playlistRowIndex, rows.get(playlistRowIndex).toBuilder()
                        .addActions(playlistDownloadButton(playlistId)))
                .build();
    }

    /**
     * @return The Download button the server gives to the playlist page header of Premium accounts,
     *         which saves the playlist or removes it if it's saved.
     */
    private static FlexibleAction playlistDownloadButton(String playlistId) {
        Logger.printDebug(() -> "Adding download button to playlist: " + playlistId);

        OfflinePlaylistEndpoint endpoint = OfflinePlaylistEndpoint.newBuilder()
                .setPlaylistId(playlistId)
                .setAction(OfflinePlaylistAction.OFFLINE_PLAYLIST_ACTION_INFER_AUTOMATICALLY)
                .setOfflineability(offlineability(List.of()))
                .build();
        // Texts of the app, which all supported app versions have.
        String downloadableText = ResourceUtils.getString("accessibility_playlist_download");
        String inProgressText = ResourceUtils.getString("offline_button_progress_text");
        String completedText = ResourceUtils.getString("offline_button_complete_text");
        ByteString downloadStatusEntityId = PlaylistDownloadStatusEntityId.newBuilder()
                .setPlaylistId(playlistId)
                .setType(PLAYLIST_DOWNLOAD_STATUS_ENTITY_ID_TYPE)
                .build()
                .toByteString();

        PlaylistDownloadButtonViewModel button = PlaylistDownloadButtonViewModel.newBuilder()
                .setPlaylistId(playlistId)
                .setDownloadableText(downloadableText)
                .setInProgressText(inProgressText)
                .setCompletedText(completedText)
                .setDownloadableA11YText(downloadableText)
                .setInProgressA11YText(inProgressText)
                .setCompletedA11YText(completedText)
                .setDownloadStatusKey(entityKey(DOWNLOAD_STATUS_ENTITY_TYPE, downloadStatusEntityId))
                .setGenerationStatusKey(entityKey(GENERATION_STATUS_ENTITY_TYPE,
                        ByteString.copyFromUtf8(PLAYLIST_GENERATION_STATUS_ENTITY_ID)))
                .setLayout(DownloadButtonLayout.DOWNLOAD_BUTTON_LAYOUT_PLAYLIST_HEADER)
                .setIconColor(DownloadButtonColor.DOWNLOAD_BUTTON_COLOR_PLAYLIST_HEADER)
                .setTextColor(DownloadButtonColor.DOWNLOAD_BUTTON_COLOR_PLAYLIST_HEADER)
                .setButtonSize(DownloadButtonSize.DOWNLOAD_BUTTON_SIZE_DEFAULT)
                .setButtonStyle(DownloadButtonStyle.DOWNLOAD_BUTTON_STYLE_OVERLAY)
                .setOnTapCommand(ButtonCommand.newBuilder().setInnertubeCommand(
                        OfflineCommand.newBuilder().setOfflinePlaylistEndpoint(endpoint)))
                .setIdentifier(PLAYLIST_DOWNLOAD_BUTTON_IDENTIFIER)
                .build();
        return FlexibleAction.newBuilder().setDownloadButtonViewModel(button).build();
    }

    /**
     * @return The key of the entity, as the app encodes it.
     */
    private static String entityKey(int entityType, ByteString entityId) {
        EntityKey key = EntityKey.newBuilder()
                .setEntityId(entityId)
                .setEntityType(entityType)
                .setPersistence(ENTITY_PERSISTENCE)
                .build();
        return Uri.encode(Base64.encodeToString(key.toByteArray(), Base64.URL_SAFE | Base64.NO_WRAP));
    }

    /**
     * Saves a video the same as the Download button of the app, for videos that have no such button.
     * Must be called from the main thread.
     */
    public static void saveVideo(String videoId) {
        Logger.printDebug(() -> "Saving video: " + videoId);
        // If the formats of the video are loaded first, the command logs to the interaction logger
        // without checking it is set.
        Map<String, Object> context = new HashMap<>();
        context.put(INTERACTION_LOGGER_KEY, getNoOpInteractionLogger());
        if (!CommandRouterPatch.executeCommand(saveVideoCommand(videoId, List.of()).toByteArray(), context)) {
            Logger.printException(() -> "Could not save the video, no activity is shown: " + videoId);
        }
    }

    /**
     * @return The interaction logger of the app that logs nothing.
     */
    @Nullable
    @SuppressWarnings("SameReturnValue")
    private static Object getNoOpInteractionLogger() {
        return null; // Modified during patching.
    }
}
