/*
 * Copyright 2026 Morphe.
 *
 * https://github.com/MorpheApp/morphe-patches/pull/1881
 * https://github.com/MorpheApp/morphe-patches/pull/4698
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches;

import androidx.annotation.Nullable;

import com.google.protobuf.MessageLite;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.innertube.Offline.OfflineCaptionTrack;
import app.morphe.extension.shared.innertube.Offline.OfflineCaptionTracklist;
import app.morphe.extension.shared.innertube.Offline.OfflineCaptions;
import app.morphe.extension.shared.innertube.Offline.OfflineCommand;
import app.morphe.extension.shared.innertube.Offline.OfflineFormat;
import app.morphe.extension.shared.innertube.Offline.OfflineFormatAvailability;
import app.morphe.extension.shared.innertube.Offline.OfflineFormatLabel;
import app.morphe.extension.shared.innertube.Offline.OfflineFormatType;
import app.morphe.extension.shared.innertube.Offline.OfflinePlayerConfig;
import app.morphe.extension.shared.innertube.Offline.OfflinePlayerResponse;
import app.morphe.extension.shared.innertube.Offline.OfflinePlaylistAction;
import app.morphe.extension.shared.innertube.Offline.OfflinePlaylistEndpoint;
import app.morphe.extension.shared.innertube.Offline.OfflineStreamFormat;
import app.morphe.extension.shared.innertube.Offline.OfflineStreamingData;
import app.morphe.extension.shared.innertube.Offline.OfflineUpsellCommand;
import app.morphe.extension.shared.innertube.Offline.OfflineUpsellResponse;
import app.morphe.extension.shared.innertube.Offline.OfflineVideoAction;
import app.morphe.extension.shared.innertube.Offline.OfflineVideoEndpoint;
import app.morphe.extension.shared.innertube.Offline.OfflineabilityContainer;
import app.morphe.extension.shared.innertube.Offline.OfflineabilityRenderer;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerGestureConfig;
import app.morphe.extension.shared.innertube.SavedVideo.OfflineState;
import app.morphe.extension.shared.innertube.SavedVideo.OfflineStateAction;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.sponsorblock.SponsorBlockOfflineStorage;
import app.morphe.extension.shared.spoof.ClientType;
import app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch;
import app.morphe.extension.shared.spoof.requests.StreamingDataRequest;

/**
 * Saves videos and songs with the offline downloads of the app, for both YouTube and YouTube Music.
 */
@SuppressWarnings("unused")
public class BaseInAppDownloadsPatch {

    /**
     * Qualities offered when the server does not list any, highest first.
     */
    protected static final OfflineFormatType[] DEFAULT_FORMAT_TYPES = {
            OfflineFormatType.OFFLINE_FORMAT_TYPE_HD_1080,
            OfflineFormatType.OFFLINE_FORMAT_TYPE_HD,
            OfflineFormatType.OFFLINE_FORMAT_TYPE_SD_480,
            OfflineFormatType.OFFLINE_FORMAT_TYPE_SD,
            OfflineFormatType.OFFLINE_FORMAT_TYPE_LD,
    };

    protected static final String[] DEFAULT_FORMAT_LABELS = {
            "1080p", "720p", "480p", "360p", "144p"
    };

    /**
     * Clients that answer with stream urls, which the app can save.
     * The visionOS streams need no JavaScript challenge, whose unsolved streams the server sends slowly.
     */
    protected static final List<ClientType> DOWNLOAD_CLIENTS = List.of(
            ClientType.VISIONOS_1_03,
            ClientType.VISIONOS_1_02,
            ClientType.ANDROID_VR_DASH,
            ClientType.TV_DASH,
            ClientType.ANDROID_CREATOR,
            ClientType.TV_SIMPLY
    );

    /**
     * How long a saved video stays valid before the app asks the server about it again.
     */
    protected static final int OFFLINE_POLICY_LIFETIME_SECONDS = (int) TimeUnit.DAYS.toSeconds(365);

    /**
     * Opus audio is not saved, the other audio and all video formats are.
     */
    protected static final String UNSAVABLE_AUDIO_MIME_TYPE_PREFIX = "audio/webm";

    /**
     * Format of the caption urls of the app. The caption urls of visionOS have no format, and the server
     * then answers with a legacy format whose text is escaped twice, so the saved captions show
     * entities such as {@code &#39;} instead of an apostrophe.
     * The format is not a signed parameter, so the url stays valid.
     */
    protected static final String CAPTION_URL_FORMAT = "&fmt=srv3";

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

    protected static boolean isInAppDownloadMode() {
        return SharedYouTubeSettings.DOWNLOAD_MODE.get() == DownloadMode.IN_APP;
    }

    /**
     * Implemented by the app class of the streaming data of a video.
     */
    public interface StreamingDataInterface {
        /**
         * @return Every stream of the video.
         */
        List<FormatStreamInterface> patch_getFormatStreams();
    }

    /**
     * Implemented by the app class of a stream.
     */
    public interface FormatStreamInterface {
        int patch_getItag();

        int patch_getWidth();

        int patch_getHeight();

        @Nullable
        String patch_getMimeType();

        /**
         * @return The extra tags of the stream, such as its audio track. The streams of an audio
         *         track have the same tags in each codec.
         */
        @Nullable
        String patch_getXtags();
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
     * @param formatType A constant of the format type enum of the app.
     * @param types      Format types to return the constants of.
     * @return The constants of the format type enum of the app, in the order of the types.
     */
    protected static List<Object> appFormatTypes(Enum<?> formatType, OfflineFormatType[] types) {
        Object[] constants = formatType.getDeclaringClass().getEnumConstants();
        List<Object> formatTypes = new ArrayList<>(types.length);
        for (OfflineFormatType type : types) {
            formatTypes.add(appFormatType(constants, type));
        }
        return formatTypes;
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
     * Called when a download starts, for the video stream it saves. The app picks a lower quality
     * if the device or server checks rule out the stream of the quality. These are the same checks
     * as playback, such as which codecs the device can decode.
     *
     * @param stream        Video stream the app picked, or null.
     * @param quality       Height of the quality of the download.
     * @param streamingData Streaming data of the video.
     * @return The stream of the quality, or the stream the app picked.
     */
    @Nullable
    public static FormatStreamInterface selectDownloadVideoStream(@Nullable FormatStreamInterface stream, int quality,
                                                                  StreamingDataInterface streamingData) {
        try {
            return getDownloadVideoStream(quality, Integer.MAX_VALUE, streamingData, stream);
        } catch (Exception ex) {
            Logger.printException(() -> "selectDownloadVideoStream failure", ex);
            return stream;
        }
    }

    /**
     * Injection point.
     * <p>
     * Called when a download starts, for the audio stream it saves with the video stream.
     * The app can pair a WebM video stream, such as VP9, with an MP4 audio stream (AAC).
     * Android has no muxer for a file with both, so the export of the download would be two files.
     * The audio stream is then replaced with the stream of the same audio track in the
     * container of the video stream, with about the same bitrate.
     *
     * @param audioStream   Audio stream the app picked, or null.
     * @param videoStream   Video stream that is saved, or null if the download is audio only.
     * @param streamingData Streaming data of the video.
     * @return An audio stream in the container of the video stream, or the stream the app picked.
     */
    @Nullable
    public static FormatStreamInterface selectDownloadAudioStream(@Nullable FormatStreamInterface audioStream,
                                                                  @Nullable FormatStreamInterface videoStream,
                                                                  StreamingDataInterface streamingData) {
        try {
            if (audioStream == null || videoStream == null || !isInAppDownloadMode()) {
                return audioStream;
            }

            if (isWebm(audioStream) == isWebm(videoStream)) {
                return audioStream;
            }

            String xtags = audioStream.patch_getXtags();
            List<FormatStreamInterface> streams = streamingData.patch_getFormatStreams();
            for (int itag : otherContainerAudioItags(audioStream.patch_getItag())) {
                for (FormatStreamInterface stream : streams) {
                    if (stream.patch_getItag() == itag && Objects.equals(xtags, stream.patch_getXtags())) {
                        Logger.printDebug(() -> "Replacing audio itag " + audioStream.patch_getItag()
                                + " with itag " + itag + " to match the container of video " + describe(videoStream));
                        return stream;
                    }
                }
            }

            Logger.printDebug(() -> "No audio stream matches the container of video " + describe(videoStream)
                    + ", audio itag " + audioStream.patch_getItag() + " is saved");
            return audioStream;
        } catch (Exception ex) {
            Logger.printException(() -> "selectDownloadAudioStream failure", ex);
            return audioStream;
        }
    }

    /**
     * @return Itags of the audio streams in the other container with about the same bitrate,
     *         Opus for AAC and AAC for Opus, in the order they are preferred.
     */
    private static int[] otherContainerAudioItags(int itag) {
        return switch (itag) {
            case 139 -> new int[]{249, 250};
            case 140 -> new int[]{251, 250};
            case 141 -> new int[]{774, 251};
            case 249 -> new int[]{139};
            case 250 -> new int[]{139, 140};
            case 251 -> new int[]{140};
            case 774 -> new int[]{141, 140};
            default -> new int[0];
        };
    }

    private static boolean isWebm(FormatStreamInterface stream) {
        String mimeType = stream.patch_getMimeType();
        return mimeType != null && mimeType.contains("/webm");
    }

    /**
     * @param stream Video stream the app picked, or null.
     * @return A stream of the quality if the app picked none, and higher qualities are forced,
     *         otherwise the stream the app picked.
     */
    @Nullable
    protected static FormatStreamInterface getDownloadVideoStream(int quality, int maxHeight,
                                                                  StreamingDataInterface streamingData,
                                                                  @Nullable FormatStreamInterface stream) {
        // A quality of 0 is audio only, which saves no video stream.
        if (quality <= 0 || !isInAppDownloadMode()) {
            return stream;
        }

        if (stream != null && qualityOf(stream) == quality) {
            return stream;
        }

        List<FormatStreamInterface> qualityStreams = new ArrayList<>();
        for (FormatStreamInterface formatStream : streamingData.patch_getFormatStreams()) {
            if (isVideo(formatStream) && qualityOf(formatStream) == quality) {
                qualityStreams.add(formatStream);
            }
        }
        if (qualityStreams.isEmpty()) {
            Logger.printDebug(() -> quality + "p is not available, the video has no stream of it");
            return stream;
        }

        Logger.printDebug(() -> {
            String picked = stream == null
                    ? "no stream"
                    : qualityOf(stream) + "p (" + describe(stream) + ")";
            return quality + "p streams " + describeStreams(qualityStreams)
                    + " are ruled out by the device or server checks. The app picked " + picked;
        });

        if (!SharedYouTubeSettings.DOWNLOAD_FORCE_4K.get() || quality <= FULL_HD_HEIGHT || quality > maxHeight) {
            return stream;
        }

        FormatStreamInterface forcedStream = preferredStream(qualityStreams, stream);
        Logger.printDebug(() -> "Forcing " + quality + "p stream: " + describe(forcedStream));
        return forcedStream;
    }

    private static boolean isVideo(FormatStreamInterface stream) {
        String mimeType = stream.patch_getMimeType();
        return mimeType != null && mimeType.startsWith("video/");
    }

    /**
     * @return The quality the app sorts the stream into, or -1 if it is larger than every quality.
     */
    private static int qualityOf(FormatStreamInterface stream) {
        int width = stream.patch_getWidth();
        int height = stream.patch_getHeight();
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
    private static String codecOf(FormatStreamInterface stream) {
        String mimeType = stream.patch_getMimeType();
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
     * @param pickedStream Stream the app picked, whose codec is tried first.
     * @return The stream with the codec the app picked, or else the first codec of
     *         {@link #FORCED_STREAM_CODECS}. Streams that are not HDR come first.
     */
    private static FormatStreamInterface preferredStream(List<FormatStreamInterface> streams,
                                                         @Nullable FormatStreamInterface pickedStream) {
        List<String> codecs = new ArrayList<>();
        if (pickedStream != null) {
            String pickedCodec = codecOf(pickedStream);
            int end = pickedCodec.indexOf('.');
            codecs.add(end < 0 ? pickedCodec : pickedCodec.substring(0, end));
        }
        codecs.addAll(List.of(FORCED_STREAM_CODECS));

        for (boolean allowHdr : new boolean[]{false, true}) {
            for (String codecPrefix : codecs) {
                for (FormatStreamInterface stream : streams) {
                    String codec = codecOf(stream);
                    if (codec.startsWith(codecPrefix) && (allowHdr || !isHdrCodec(codec))) {
                        return stream;
                    }
                }
            }
        }
        return streams.get(0);
    }

    private static String describe(FormatStreamInterface stream) {
        return "itag " + stream.patch_getItag() + " " + stream.patch_getWidth() + "x"
                + stream.patch_getHeight() + " " + codecOf(stream);
    }

    private static String describeStreams(List<FormatStreamInterface> streams) {
        StringBuilder builder = new StringBuilder("[");
        for (FormatStreamInterface stream : streams) {
            if (builder.length() > 1) {
                builder.append(", ");
            }
            builder.append(describe(stream));
        }
        return builder.append(']').toString();
    }

    /**
     * Injection point.
     * <p>
     * Called before a video or playlist is saved, or before the quality picker is shown.
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

            OfflineabilityRenderer renderer = offlineability instanceof MessageLite message
                    ? OfflineabilityRenderer.parseFrom(message.toByteArray())
                    : OfflineabilityRenderer.getDefaultInstance();
            if (renderer.getOfflineable()
                    && renderer.getFormatsCount() > 0
                    && !hasLockedFormat(renderer.getFormatsList())) {
                return null;
            }

            Logger.printDebug(() -> "Unlocking offlineability, offlineable: " + renderer.getOfflineable());
            return renderer.toBuilder()
                    .setOfflineable(true)
                    .clearNotOfflineableDialog()
                    .clearFormats()
                    .addAllFormats(unlockedFormats(renderer.getFormatsList()))
                    .build()
                    .toByteArray();
        } catch (Exception ex) {
            Logger.printException(() -> "unlockOfflineability failure", ex);
            return null;
        }
    }

    /**
     * Injection point.
     * <p>
     * Called with the response of the offline upsell endpoint, which offers Premium instead of saving the video.
     *
     * @param command  Command of the offline upsell endpoint.
     * @param response Response of the endpoint.
     * @return A response that saves the video instead, or null to keep the original.
     */
    @Nullable
    public static byte[] replaceOfflineUpsell(@Nullable MessageLite command, @Nullable Object response) {
        try {
            if (!isInAppDownloadMode()
                    || command == null
                    || !(response instanceof MessageLite responseMessage)) {
                return null;
            }

            OfflineUpsellResponse upsellResponse = OfflineUpsellResponse.parseFrom(responseMessage.toByteArray());
            if (!upsellResponse.getContent().hasOfflineUpsellRenderer()) {
                return null;
            }

            String videoId = OfflineUpsellCommand.parseFrom(command.toByteArray())
                    .getOfflineUpsellEndpoint().getVideoId();
            if (videoId.isEmpty()) {
                Logger.printDebug(() -> "Offline upsell command has no video id");
                return null;
            }

            Logger.printDebug(() -> "Replacing offline upsell with saving the video: " + videoId);
            return upsellResponse.toBuilder()
                    .clearContent()
                    .setCommand(saveVideoCommand(videoId,
                            upsellResponse.getContent().getOfflineUpsellRenderer().getFormatsList()))
                    .build()
                    .toByteArray();
        } catch (Exception ex) {
            Logger.printException(() -> "replaceOfflineUpsell failure", ex);
            return null;
        }
    }

    /**
     * Injection point.
     * <p>
     * Called off the main thread with the player response the app saves a video with.
     * If the server declines to save the video, the streams are fetched with another client.
     *
     * @param playerResponse Player response proto of the video.
     * @return A player response that can be saved, or null to keep the original.
     */
    @Nullable
    public static byte[] replaceDeclinedOfflinePlayerResponse(@Nullable Object playerResponse) {
        try {
            if (!isInAppDownloadMode()
                    || !(playerResponse instanceof MessageLite message)) {
                return null;
            }

            OfflinePlayerResponse original = OfflinePlayerResponse.parseFrom(message.toByteArray());
            if (!original.hasOfflineState()) {
                return null;
            }

            String videoId = original.hasVideoDetails()
                    ? original.getVideoDetails().getVideoId()
                    : "";

            final boolean allowed = original.getOfflineState().getActionValue()
                    == OfflineStateAction.OFFLINE_STATE_ACTION_OK_VALUE;
            if (allowed && original.getStreamingData().getAdaptiveFormatsCount() > 0) {
                if (!videoId.isEmpty()) {
                    SponsorBlockOfflineStorage.fetchAndSaveSegments(videoId);
                }
                return null;
            }

            if (videoId.isEmpty()) {
                Logger.printDebug(() -> "Declined offline player response has no video id");
                return null;
            }

            StreamingDataRequest.StreamData streams = StreamingDataRequest.fetchRequestForDownload(
                    videoId, DOWNLOAD_CLIENTS, DOWNLOAD_CLIENTS.get(0)).getStream();
            if (streams == null) {
                Logger.printDebug(() -> "No streams to save the video with: " + videoId);
                return null;
            }

            // The declined response lacks the parts the app needs to select the streams to save,
            // such as the player config. The response of the other client is used instead,
            // and only the offline state token and player config are kept from the declined response.
            OfflinePlayerResponse.Builder builder = OfflinePlayerResponse.parseFrom(streams.streamingData()).toBuilder();
            builder.setStreamingData(savableStreamingData(builder.getStreamingData()))
                    .setOfflineState(playableOfflineState(original.getOfflineState().getToken()));
            if (builder.hasCaptions()) {
                builder.setCaptions(srv3Captions(builder.getCaptions()));
            }

            // Without the ExoPlayer config the app only looks at the progressive formats, which are not savable,
            // and the playback speed menu is unavailable.
            // The player config of the app's own response fits the device, so it's kept if there is one.
            OfflinePlayerConfig playerConfig = original.hasPlayerConfig()
                    ? original.getPlayerConfig()
                    : builder.getPlayerConfig();
            PlayerGestureConfig.Builder gestures = playerConfig.getPlayerGestureConfig().toBuilder();
            if (!gestures.hasDownAndOutPortraitAllowed()) {
                gestures.setDownAndOutPortraitAllowed(true);
            }
            if (!gestures.hasDownAndOutLandscapeAllowed()) {
                gestures.setDownAndOutLandscapeAllowed(true);
            }
            builder.setPlayerConfig(playerConfig.toBuilder()
                    .setPlayerGestureConfig(gestures)
                    .setExoPlayerConfig(playerConfig.getExoPlayerConfig().toBuilder()
                            .setUseExoPlayer(true)
                            .setEnableVariableSpeedPlayback(true)));

            SponsorBlockOfflineStorage.fetchAndSaveSegments(videoId);

            Logger.printDebug(() -> "Replaced declined offline player response of: " + videoId
                    + " savable formats: " + countSavableFormats(builder.getStreamingData())
                    + " of: " + builder.getStreamingData().getAdaptiveFormatsCount());
            return builder.build().toByteArray();
        } catch (Exception ex) {
            Logger.printException(() -> "replaceDeclinedOfflinePlayerResponse failure", ex);
            return null;
        }
    }

    /**
     * Injection point.
     * <p>
     * Called off the main thread with the offline state the server gives a saved video when the app
     * refreshes its saved videos, which the server schedules about every 12 hours.
     * The server disables saved videos that need Premium to be saved, such as music videos,
     * and the app then shows an error instead of playing them.
     *
     * @param offlineState New offline state of a saved video.
     * @return An offline state that keeps the video playable, or null to keep the original.
     */
    @Nullable
    public static byte[] replaceDisabledOfflineState(@Nullable MessageLite offlineState) {
        try {
            if (!isInAppDownloadMode() || offlineState == null) {
                return null;
            }

            OfflineState state = OfflineState.parseFrom(offlineState.toByteArray());
            if (state.getAction() != OfflineStateAction.OFFLINE_STATE_ACTION_DISABLE) {
                return null;
            }

            Logger.printDebug(() -> "Replacing disabled offline state with reason: " + state.getDisabledReason());
            return playableOfflineState(state.getToken()).toByteArray();
        } catch (Exception ex) {
            Logger.printException(() -> "replaceDisabledOfflineState failure", ex);
            return null;
        }
    }

    /**
     * Injection point.
     * <p>
     * Called after the player response model is created from the replaced player response.
     *
     * @param playerResponseModel The created model, or null if the app could not parse the replaced player response.
     */
    public static void onOfflinePlayerResponseReplaced(@Nullable Object playerResponseModel) {
        SpoofVideoStreamsPatch.setKeepStreamsOnThread(false);
        if (playerResponseModel == null) {
            Logger.printException(() -> "Could not create the player response model of the replaced player response");
        }
    }

    /**
     * @return Offline state that keeps a saved video playable, without asking the server again for a long time.
     */
    protected static OfflineState playableOfflineState(String token) {
        return OfflineState.newBuilder()
                .setToken(token)
                .setPolicyLifetimeSeconds(OFFLINE_POLICY_LIFETIME_SECONDS)
                .setAction(OfflineStateAction.OFFLINE_STATE_ACTION_OK)
                .build();
    }

    /**
     * @return A command of the offline video endpoint that saves the video with the formats.
     */
    protected static OfflineCommand saveVideoCommand(String videoId, List<OfflineFormat> formats) {
        OfflineVideoEndpoint endpoint = OfflineVideoEndpoint.newBuilder()
                .setVideoId(videoId)
                .setAction(OfflineVideoAction.OFFLINE_VIDEO_ACTION_ADD)
                .setOfflineability(offlineability(formats))
                .build();
        return OfflineCommand.newBuilder().setOfflineVideoEndpoint(endpoint).build();
    }

    /**
     * @return A command of the offline playlist endpoint that saves the playlist with the formats.
     */
    protected static OfflineCommand savePlaylistCommand(
            String playlistId,
            List<OfflineFormat> formats
    ) {
        String normalizedId = playlistId.startsWith("VL") ? playlistId.substring(2) : playlistId;
        OfflinePlaylistEndpoint endpoint = OfflinePlaylistEndpoint.newBuilder()
                .setPlaylistId(normalizedId)
                .setAction(OfflinePlaylistAction.OFFLINE_PLAYLIST_ACTION_ADD)
                .setOfflineability(offlineability(formats))
                .build();
        return OfflineCommand.newBuilder().setOfflinePlaylistEndpoint(endpoint).build();
    }

    /**
     * @return Offlineability that saves with the formats.
     */
    protected static OfflineabilityContainer offlineability(List<OfflineFormat> formats) {
        OfflineabilityRenderer renderer = OfflineabilityRenderer.newBuilder()
                .setOfflineable(true)
                .addAllFormats(unlockedFormats(formats))
                .build();
        return OfflineabilityContainer.newBuilder()
                .setOfflineabilityRenderer(renderer)
                .build();
    }

    protected static boolean hasLockedFormat(List<OfflineFormat> formats) {
        for (OfflineFormat format : formats) {
            if (isLocked(format)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return The formats with the Premium formats available, or the default formats if there are none.
     */
    protected static List<OfflineFormat> unlockedFormats(List<OfflineFormat> formats) {
        List<OfflineFormat> unlocked = new ArrayList<>();
        if (formats.isEmpty()) {
            for (int i = 0; i < DEFAULT_FORMAT_TYPES.length; i++) {
                unlocked.add(OfflineFormat.newBuilder()
                        .setName(OfflineFormatLabel.newBuilder().setSimpleText(DEFAULT_FORMAT_LABELS[i]))
                        .setFormatType(DEFAULT_FORMAT_TYPES[i])
                        .setAvailability(OfflineFormatAvailability.OFFLINE_FORMAT_AVAILABILITY_FREE)
                        .build());
            }
            return unlocked;
        }

        for (OfflineFormat format : formats) {
            unlocked.add(isLocked(format)
                    ? format.toBuilder()
                            .setAvailability(OfflineFormatAvailability.OFFLINE_FORMAT_AVAILABILITY_FREE)
                            .build()
                    : format);
        }
        return unlocked;
    }

    /**
     * @param types  Format types to offer, highest first.
     * @param labels Labels of the format types.
     * @return The formats with the types added that the server omitted, sorted highest first,
     * or null if the server already lists every type.
     */
    @Nullable
    protected static List<OfflineFormat> mergeMissingFormatTypes(List<OfflineFormat> formats,
                                                                 OfflineFormatType[] types,
                                                                 String[] labels) {
        boolean hasMissingType = false;
        for (OfflineFormatType type : types) {
            if (indexOfFormatType(formats, type) < 0) {
                hasMissingType = true;
                break;
            }
        }
        if (!hasMissingType) {
            return null;
        }

        List<OfflineFormat> merged = new ArrayList<>(formats.size() + types.length);
        List<OfflineFormat> remaining = new ArrayList<>(formats);
        for (int i = 0; i < types.length; i++) {
            OfflineFormatType type = types[i];
            int index = indexOfFormatType(remaining, type);
            if (index >= 0) {
                merged.add(remaining.remove(index));
            } else {
                merged.add(OfflineFormat.newBuilder()
                        .setName(OfflineFormatLabel.newBuilder().setSimpleText(labels[i]))
                        .setFormatType(type)
                        .setAvailability(OfflineFormatAvailability.OFFLINE_FORMAT_AVAILABILITY_FREE)
                        .build());
            }
        }
        // Types the server gives that are not part of the ladder, such as audio only.
        merged.addAll(remaining);
        return merged;
    }

    private static int indexOfFormatType(List<OfflineFormat> formats, OfflineFormatType type) {
        for (int i = 0; i < formats.size(); i++) {
            if (formats.get(i).getFormatType() == type) {
                return i;
            }
        }
        return -1;
    }

    protected static boolean isLocked(OfflineFormat format) {
        return format.getAvailability() == OfflineFormatAvailability.OFFLINE_FORMAT_AVAILABILITY_PREMIUM_LOCKED;
    }

    protected static int countSavableFormats(OfflineStreamingData streamingData) {
        int count = 0;
        for (OfflineStreamFormat format : streamingData.getAdaptiveFormatsList()) {
            if (format.getOfflineEligible()) {
                count++;
            }
        }
        return count;
    }

    protected static OfflineStreamingData savableStreamingData(OfflineStreamingData streamingData) {
        OfflineStreamingData.Builder builder = streamingData.toBuilder();
        for (int i = 0, count = builder.getAdaptiveFormatsCount(); i < count; i++) {
            OfflineStreamFormat format = builder.getAdaptiveFormats(i);
            String mimeType = format.getMimeType();
            if (!mimeType.isEmpty() && !mimeType.startsWith(UNSAVABLE_AUDIO_MIME_TYPE_PREFIX)) {
                builder.setAdaptiveFormats(i, format.toBuilder().setOfflineEligible(true));
            }
        }
        return builder.build();
    }

    protected static OfflineCaptions srv3Captions(OfflineCaptions captions) {
        OfflineCaptionTracklist.Builder tracklist = captions.getPlayerCaptionsTracklistRenderer().toBuilder();
        for (int i = 0, count = tracklist.getCaptionTracksCount(); i < count; i++) {
            OfflineCaptionTrack track = tracklist.getCaptionTracks(i);
            String baseUrl = track.getBaseUrl().replaceAll("&fmt=[^&]*", "") + CAPTION_URL_FORMAT;
            tracklist.setCaptionTracks(i, track.toBuilder().setBaseUrl(baseUrl));
        }
        return captions.toBuilder().setPlayerCaptionsTracklistRenderer(tracklist).build();
    }
}
