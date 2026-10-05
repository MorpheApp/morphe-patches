/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3447
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.dearrow;

import static app.morphe.extension.shared.StringRef.str;

import android.net.Uri;

import androidx.annotation.Nullable;

import org.chromium.net.UrlRequest;
import org.chromium.net.UrlResponseInfo;
import org.chromium.net.impl.CronetUrlRequest;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.LithoRelayoutPatch;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.NavigationBar;
import app.morphe.extension.youtube.shared.PlayerType;

/**
 * DeArrow titles and alternative YouTube thumbnails.
 * <p>
 * Titles are replaced by {@link app.morphe.extension.youtube.patches.originaltitles.RestoreOriginalTitlesPatch},
 * which fetches them with {@link DeArrowBrandingRequest}.
 * <p>
 * Can show YouTube provided screen captures of beginning/middle/end of the video.
 * (ie: sd1.jpg, sd2.jpg, sd3.jpg).
 * <p>
 * Or can show crowdsourced thumbnails provided by DeArrow (<a href="http://dearrow.ajay.app">...</a>).
 * The DeArrow thumbnail is fetched with {@link DeArrowBrandingRequest}, and the original thumbnail is
 * used if the video has no crowdsourced thumbnail. The thumbnail cache API is not used without
 * a crowdsourced thumbnail, as it can return a random video frame that was cached for other clients.
 * <p>
 * Or can use DeArrow and fall back to screen captures if DeArrow is not available.
 * Any thumbnail the DeArrow thumbnail cache API provides is used, without waiting for the branding.
 * <p>
 * The thumbnail cache redirects to the fallback thumbnail if it has no thumbnail. If it cannot redirect,
 * then DeArrow is not used for the video for a while, and the Litho views are mounted again
 * to load the fallback thumbnail.
 * <p>
 * Still captures are used without first verifying if the image exists, so the UI loading time
 * is the same as using original thumbnails. Still captures are not available for live streams,
 * unreleased, and occasionally very old videos. If a still capture fails to load, then the quality
 * is remembered as not available and the Litho views are mounted again, which loads the thumbnail
 * again with a lower quality still capture or the original thumbnail.
 * DeArrow thumbnails that fail to load are also loaded again with the original thumbnail.
 */
@SuppressWarnings("unused")
public final class DeArrowPatch {

    // These must be class declarations if declared here,
    // otherwise the app will not load due to cyclic initialization errors.
    public static final class DeArrowThumbnailsAvailability implements Setting.Availability {
        public static boolean usingDeArrowThumbnailsAnywhere() {
            return Settings.DEARROW_THUMBNAIL_HOME.get().useDeArrow
                    || Settings.DEARROW_THUMBNAIL_SUBSCRIPTIONS.get().useDeArrow
                    || Settings.DEARROW_THUMBNAIL_LIBRARY.get().useDeArrow
                    || Settings.DEARROW_THUMBNAIL_PLAYER.get().useDeArrow
                    || Settings.DEARROW_THUMBNAIL_SEARCH.get().useDeArrow;
        }

        @Override
        public boolean isAvailable() {
            return usingDeArrowThumbnailsAnywhere();
        }

        @Override
        public List<Setting<?>> getParentSettings() {
            return List.of(
                    Settings.DEARROW_THUMBNAIL_HOME,
                    Settings.DEARROW_THUMBNAIL_SUBSCRIPTIONS,
                    Settings.DEARROW_THUMBNAIL_LIBRARY,
                    Settings.DEARROW_THUMBNAIL_PLAYER,
                    Settings.DEARROW_THUMBNAIL_SEARCH
            );
        }
    }

    /**
     * Available if DeArrow is used for titles or thumbnails.
     */
    public static final class DeArrowAvailability implements Setting.Availability {
        @Override
        public boolean isAvailable() {
            return Settings.DEARROW_TITLES.get()
                    || DeArrowThumbnailsAvailability.usingDeArrowThumbnailsAnywhere();
        }

        @Override
        public List<Setting<?>> getParentSettings() {
            return List.of(
                    Settings.DEARROW_TITLES,
                    Settings.DEARROW_THUMBNAIL_HOME,
                    Settings.DEARROW_THUMBNAIL_SUBSCRIPTIONS,
                    Settings.DEARROW_THUMBNAIL_LIBRARY,
                    Settings.DEARROW_THUMBNAIL_PLAYER,
                    Settings.DEARROW_THUMBNAIL_SEARCH
            );
        }
    }

    public static final class StillImagesAvailability implements Setting.Availability {
        public static boolean usingStillImagesAnywhere() {
            return Settings.DEARROW_THUMBNAIL_HOME.get().useStillImages
                    || Settings.DEARROW_THUMBNAIL_SUBSCRIPTIONS.get().useStillImages
                    || Settings.DEARROW_THUMBNAIL_LIBRARY.get().useStillImages
                    || Settings.DEARROW_THUMBNAIL_PLAYER.get().useStillImages
                    || Settings.DEARROW_THUMBNAIL_SEARCH.get().useStillImages;
        }

        @Override
        public boolean isAvailable() {
            return usingStillImagesAnywhere();
        }

        @Override
        public List<Setting<?>> getParentSettings() {
            return List.of(
                    Settings.DEARROW_THUMBNAIL_HOME,
                    Settings.DEARROW_THUMBNAIL_SUBSCRIPTIONS,
                    Settings.DEARROW_THUMBNAIL_LIBRARY,
                    Settings.DEARROW_THUMBNAIL_PLAYER,
                    Settings.DEARROW_THUMBNAIL_SEARCH
            );
        }
    }

    public enum ThumbnailOption {
        ORIGINAL(false, false),
        DEARROW(true, false),
        DEARROW_STILL_IMAGES(true, true),
        STILL_IMAGES(false, true);

        final boolean useDeArrow;
        final boolean useStillImages;

        ThumbnailOption(boolean useDeArrow, boolean useStillImages) {
            this.useDeArrow = useDeArrow;
            this.useStillImages = useStillImages;
        }
    }

    public enum ThumbnailStillTime {
        BEGINNING(1),
        MIDDLE(2),
        END(3);

        /**
         * The url alt image number. Such as the 2 in 'hq720_2.jpg'
         */
        final int altImageNumber;

        ThumbnailStillTime(int altImageNumber) {
            this.altImageNumber = altImageNumber;
        }
    }

    private static final Uri dearrowAPIURI;

    /**
     * The scheme and host of {@link #dearrowAPIURI}.
     */
    private static final String deArrowAPIURLPrefix;

    /**
     * How long to temporarily turn off DeArrow if it fails for any reason.
     */
    private static final long DEARROW_FAILURE_API_BACKOFF_MILLISECONDS = 5 * 60 * 1000; // 5 Minutes.

    /**
     * How long to wait for the DeArrow branding before using the original thumbnail.
     */
    private static final long DEARROW_BRANDING_TIMEOUT_MILLISECONDS = 2 * 1000;

    /**
     * How long until a crowdsourced thumbnail the thumbnail cache did not provide is tried again.
     */
    private static final long DEARROW_THUMBNAIL_RETRY_MILLISECONDS = 5 * 60 * 1000; // 5 Minutes.

    /**
     * Video id -> system time when the crowdsourced thumbnail the thumbnail cache did not provide
     * can be tried again.
     */
    private static final Map<String, Long> unavailableThumbnailRetryTimes =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(1000));

    /**
     * If non-zero, then the system time of when DeArrow API calls can resume.
     */
    private static volatile long timeToResumeDeArrowAPICalls;

    static {
        dearrowAPIURI = validateSettings();
        final int port = dearrowAPIURI.getPort();
        String portString = port == -1 ? "" : (":" + port);
        deArrowAPIURLPrefix = dearrowAPIURI.getScheme() + "://" + dearrowAPIURI.getHost() + portString + "/";
        Logger.printDebug(() -> "Using DeArrow API address: " + deArrowAPIURLPrefix);
    }

    /**
     * Fix any bad imported data.
     */
    private static Uri validateSettings() {
        Uri apiURI = Uri.parse(Settings.DEARROW_API_URL.get());
        // Cannot use unsecured 'http', otherwise the connections fail to start and no callbacks hooks are made.
        String scheme = apiURI.getScheme();
        if (scheme == null || scheme.equals("http") || apiURI.getHost() == null) {
            Utils.showToastLong("Invalid DeArrow API URL. Using default");
            Settings.DEARROW_API_URL.resetToDefault();
            return validateSettings();
        }
        return apiURI;
    }

    private static ThumbnailOption optionSettingForCurrentNavigation() {
        // Must check player type first, as search bar can be active behind the player.
        if (PlayerType.getCurrent().isMaximizedOrFullscreen()) {
            return Settings.DEARROW_THUMBNAIL_PLAYER.get();
        }

        // Must check second, as search can be from any tab.
        if (NavigationBar.isSearchBarActive()) {
            return Settings.DEARROW_THUMBNAIL_SEARCH.get();
        }

        // Avoid checking which navigation button is selected, if all other settings are the same.
        ThumbnailOption homeOption = Settings.DEARROW_THUMBNAIL_HOME.get();
        ThumbnailOption subscriptionsOption = Settings.DEARROW_THUMBNAIL_SUBSCRIPTIONS.get();
        ThumbnailOption libraryOption = Settings.DEARROW_THUMBNAIL_LIBRARY.get();
        if ((homeOption == subscriptionsOption) && (homeOption == libraryOption)) {
            return homeOption; // All are the same option.
        }

        NavigationBar.NavigationButton selectedNavButton = NavigationBar.NavigationButton.getSelectedNavigationButton();
        if (selectedNavButton == null) {
            // Unknown tab, treat as the home tab;
            return homeOption;
        }

        return switch (selectedNavButton) {
            case SUBSCRIPTIONS, NOTIFICATIONS -> subscriptionsOption;
            case LIBRARY -> libraryOption;
            // Home or explore tab.
            default -> homeOption;
        };
    }

    /**
     * Build the alternative thumbnail URL using YouTube provided still video captures.
     *
     * @param decodedURL Decoded original thumbnail request url.
     * @return The alternative thumbnail URL, or if not available NULL.
     */
    @Nullable
    private static String buildYouTubeVideoStillURL(DecodedThumbnailURL decodedURL,
                                                    ThumbnailQuality qualityToUse) {
        ThumbnailQuality quality = UnavailableQualities.getQualityToTry(decodedURL.videoId, qualityToUse);
        if (quality == null) {
            return null;
        }

        return decodedURL.createStillsURL(quality);
    }

    /**
     * Build the alternative thumbnail URL using DeArrow thumbnail cache.
     *
     * @param videoId ID of the video to get a thumbnail of.  Can be any video (regular or Short).
     * @param thumbnailTime Time in seconds of the video frame of the crowdsourced thumbnail,
     *                      or null to use any thumbnail the thumbnail cache has for the video.
     * @param fallbackURL URL the thumbnail cache redirects to if it has no thumbnail.
     *                    The thumbnail cache only redirects to URLs of the primary thumbnail domain.
     * @return The alternative thumbnail URL, without tracking parameters.
     */
    private static String buildDeArrowThumbnailURL(String videoId, @Nullable Double thumbnailTime,
                                                   String fallbackURL) {
        // Build thumbnail request URL.
        // See https://github.com/ajayyy/DeArrowThumbnailCache/blob/d5e9ae6844e214aeedfbb2ae8d563942f72dc0a1/app.py#L38
        Uri.Builder builder = dearrowAPIURI
                .buildUpon()
                .appendQueryParameter("videoID", videoId);
        if (thumbnailTime != null) {
            // The time of the crowdsourced thumbnail, which the thumbnail cache also uses
            // for requests without a time.
            builder.appendQueryParameter("time", String.valueOf(thumbnailTime))
                    .appendQueryParameter("officialTime", "true");
        }
        return builder
                .appendQueryParameter("redirectUrl", fallbackURL)
                .build()
                .toString();
    }

    /**
     * @return If the thumbnail cache recently did not provide the crowdsourced thumbnail of the video.
     */
    private static boolean isThumbnailUnavailable(String videoId) {
        Long retryTime = unavailableThumbnailRetryTimes.get(videoId);
        if (retryTime == null) {
            return false;
        }
        if (retryTime < System.currentTimeMillis()) {
            unavailableThumbnailRetryTimes.remove(videoId);
            return false;
        }
        return true;
    }

    /**
     * The thumbnail cache did not provide the crowdsourced thumbnail, such as a thumbnail
     * that failed to generate or is not generated yet.
     *
     * @param url DeArrow thumbnail URL.
     */
    private static void setThumbnailUnavailable(String url) {
        String videoId = Uri.parse(url).getQueryParameter("videoID");
        if (videoId == null) {
            return;
        }
        Logger.printDebug(() -> "DeArrow thumbnail not available for video: " + videoId);
        unavailableThumbnailRetryTimes.put(videoId,
                System.currentTimeMillis() + DEARROW_THUMBNAIL_RETRY_MILLISECONDS);
    }

    private static boolean urlIsDeArrow(String imageURL) {
        return imageURL.startsWith(deArrowAPIURLPrefix);
    }

    /**
     * @return If this client has not recently experienced any DeArrow API errors.
     */
    static boolean canUseDeArrowAPI() {
        if (timeToResumeDeArrowAPICalls == 0) {
            return true;
        }
        if (timeToResumeDeArrowAPICalls < System.currentTimeMillis()) {
            Logger.printDebug(() -> "Resuming DeArrow API calls");
            timeToResumeDeArrowAPICalls = 0;
            return true;
        }
        return false;
    }

    /**
     * Turns off DeArrow titles and thumbnails for a while.
     */
    static void handleDeArrowError(String url, int statusCode) {
        Logger.printDebug(() -> "Encountered DeArrow error.  URL: " + url);
        final long now = System.currentTimeMillis();
        if (timeToResumeDeArrowAPICalls < now) {
            timeToResumeDeArrowAPICalls = now + DEARROW_FAILURE_API_BACKOFF_MILLISECONDS;
            if (Settings.DEARROW_CONNECTION_TOAST.get()) {
                String toastMessage = (statusCode != 0)
                        ? str("morphe_dearrow_error", statusCode)
                        : str("morphe_dearrow_error_generic");
                Utils.showToastLong(toastMessage);
            }
            // Load the DeArrow thumbnails again, which now use the fallback thumbnails.
            LithoRelayoutPatch.remountListViews();
        }
    }

    /**
     * Injection point. Called off the main thread and by multiple threads at the same time.
     *
     * @param originalURL Image URL for all URL images loaded, including video thumbnails.
     */
    public static String overrideImageURL(String originalURL) {
        try {
            ThumbnailOption option = optionSettingForCurrentNavigation();

            if (option == ThumbnailOption.ORIGINAL) {
                return originalURL;
            }

            final var decodedURL = DecodedThumbnailURL.decodeImageURL(originalURL);
            if (decodedURL == null) {
                return originalURL; // Not a thumbnail.
            }

            Logger.printDebug(() -> "Original URL: " + decodedURL.sanitizedURL);

            ThumbnailQuality qualityToUse = ThumbnailQuality.getQualityToUse(decodedURL.imageQuality);
            if (qualityToUse == null) {
                // Thumbnail is a Short or a Storyboard image used for seekbar thumbnails (must not replace these).
                return originalURL;
            }

            String sanitizedReplacementURL;
            final boolean includeTracking;
            if (option.useDeArrow && canUseDeArrowAPI() && !isThumbnailUnavailable(decodedURL.videoId)) {
                includeTracking = false; // Do not include view tracking parameters with API call.
                if (option.useStillImages) {
                    // Any thumbnail of the thumbnail cache is used, and the still capture otherwise.
                    String stillURL = buildYouTubeVideoStillURL(decodedURL, qualityToUse);
                    sanitizedReplacementURL = buildDeArrowThumbnailURL(decodedURL.videoId, null,
                            stillURL != null ? stillURL : decodedURL.sanitizedURL);
                } else {
                    // Without a time, the thumbnail cache can return a random video frame.
                    Double thumbnailTime = DeArrowBrandingRequest.fetchThumbnailTime(
                            decodedURL.videoId, DEARROW_BRANDING_TIMEOUT_MILLISECONDS);
                    if (thumbnailTime == null) {
                        return originalURL; // No crowdsourced thumbnail.
                    }
                    sanitizedReplacementURL = buildDeArrowThumbnailURL(decodedURL.videoId, thumbnailTime,
                            decodedURL.sanitizedURL);
                }
            } else if (option.useStillImages) {
                includeTracking = true; // Include view tracking parameters if present.
                sanitizedReplacementURL = buildYouTubeVideoStillURL(decodedURL, qualityToUse);
                if (sanitizedReplacementURL == null) {
                    return originalURL; // Still capture is not available.  Return the untouched original url.
                }
            } else {
                return originalURL; // DeArrow is not available and video stills are not enabled.
            }

            // Do not log any tracking parameters.
            Logger.printDebug(() -> "Replacement URL: " + sanitizedReplacementURL);

            return includeTracking
                    ? sanitizedReplacementURL + decodedURL.viewTrackingParameters
                    : sanitizedReplacementURL;
        } catch (Exception ex) {
            Logger.printException(() -> "overrideImageURL failure", ex);
            return originalURL;
        }
    }

    /**
     * Injection point.
     * <p>
     * Cronet considers all completed connections as a success, even if the response is 404 or 5xx.
     */
    public static void handleCronetSuccess(UrlRequest request, UrlResponseInfo responseInfo) {
        try {
            final int statusCode = responseInfo.getHttpStatusCode();
            if (statusCode == Requester.HTTP_STATUS_CODE_SUCCESS) {
                return;
            }

            String url = responseInfo.getUrl();

            if (urlIsDeArrow(url)) {
                Logger.printDebug(() -> "handleCronetSuccess, statusCode: " + statusCode);
                if (statusCode == 304) {
                    // https://developer.mozilla.org/en-US/docs/Web/HTTP/Status/304
                    return; // Normal response.
                }
                if (statusCode == 204) {
                    // The thumbnail cache has no thumbnail, and does not redirect to fallback urls
                    // that are not of the primary thumbnail domain. Load the original thumbnail instead.
                    setThumbnailUnavailable(url);
                    LithoRelayoutPatch.remountListViews();
                    return;
                }
                handleDeArrowError(url, statusCode);
                return;
            }

            if (statusCode == 404) {
                // The still capture is not available. The video is:
                // - live stream
                // - upcoming unreleased video
                // - very old
                // - very low view count
                // - does not have the higher quality still capture
                // Take note of this, and load the image again with a lower quality or the original thumbnail.
                DecodedThumbnailURL decodedURL = DecodedThumbnailURL.decodeImageURL(url);
                if (decodedURL == null) {
                    return; // Not a thumbnail.
                }

                Logger.printDebug(() -> "handleCronetSuccess, image not available: " + decodedURL.sanitizedURL);

                ThumbnailQuality quality = ThumbnailQuality.altImageNameToQuality(decodedURL.imageQuality);
                if (quality == null) {
                    // Video is a short or a seekbar thumbnail, but somehow did not load.  Should not happen.
                    Logger.printDebug(() -> "Failed to recognize image quality of URL: " + decodedURL.sanitizedURL);
                    return;
                }

                UnavailableQualities.setQualityNotAvailable(decodedURL.videoId, quality);
                LithoRelayoutPatch.remountListViews();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Callback success error", ex);
        }
    }

    /**
     * Injection point.
     * <p>
     * To test failure cases, try changing the API URL to each of:
     * - A non-existent domain.
     * - A url path of something incorrect (ie: /v1/nonExistentEndPoint).
     * <p>
     * Cronet uses a very long timeout (several minutes), so if the API never responds
     * this hook can take a while to be called. But this does not appear to be a problem,
     * as the DeArrow API has not been observed to 'go silent' Instead if there's a problem
     * it returns an error code status response, which is handled in this patch.
     */
    public static void handleCronetFailure(UrlRequest request,
                                           @Nullable UrlResponseInfo responseInfo,
                                           IOException exception) {
        try {
            String url = ((CronetUrlRequest) request).getHookedUrl();
            if (urlIsDeArrow(url)) {
                Logger.printDebug(() -> "handleCronetFailure, exception: " + exception);
                final int statusCode = (responseInfo != null)
                        ? responseInfo.getHttpStatusCode()
                        : 0;
                handleDeArrowError(url, statusCode);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Callback failure error", ex);
        }
    }

    private enum ThumbnailQuality {
        // In order of lowest to highest resolution.
        DEFAULT("default", ""), // effective alt name is 1.jpg, 2.jpg, 3.jpg
        MQDEFAULT("mqdefault", "mq"),
        HQDEFAULT("hqdefault", "hq"),
        SDDEFAULT("sddefault", "sd"),
        HQ720("hq720", "hq720_"),
        MAXRESDEFAULT("maxresdefault", "maxres");

        /**
         * Lookup map of original name to enum.
         */
        private static final Map<String, ThumbnailQuality> originalNameToEnum = new HashMap<>();

        /**
         * Lookup map of alt name to enum.  ie: "hq720_1" to {@link #HQ720}.
         */
        private static final Map<String, ThumbnailQuality> altNameToEnum = new HashMap<>();

        static {
            for (ThumbnailQuality quality : values()) {
                originalNameToEnum.put(quality.originalName, quality);

                for (ThumbnailStillTime time : ThumbnailStillTime.values()) {
                    // 'custom' thumbnails set by the content creator.
                    // These show up in place of regular thumbnails
                    // and seem to be limited to the same [1, 3] range as the still captures.
                    originalNameToEnum.put(quality.originalName + "_custom_" + time.altImageNumber, quality);

                    altNameToEnum.put(quality.altImageName + time.altImageNumber, quality);
                }
            }
        }

        /**
         * Convert an alt image name to enum.
         * ie: "hq720_2" returns {@link #HQ720}.
         */
        @Nullable
        static ThumbnailQuality altImageNameToQuality(String altImageName) {
            return altNameToEnum.get(altImageName);
        }

        /**
         * Original quality to effective alt quality to use.
         * ie: "sddefault" returns {@link #HQ720}.
         */
        @Nullable
        static ThumbnailQuality getQualityToUse(String originalSize) {
            ThumbnailQuality quality = originalNameToEnum.get(originalSize);
            if (quality == null) {
                return null; // Not a thumbnail for a regular video.
            }

            return switch (quality) {
                // SD alt images have somewhat worse quality with washed out color and poor contrast.
                // But the 720 images look much better and don't suffer from these issues.
                // For unknown reasons, the 720 thumbnails are used only for the home feed,
                // while SD is used for the search and subscription feed
                // (even though search and subscriptions use the exact same layout as the home feed).
                // Of note, this image quality issue only appears with the alt thumbnail images,
                // and the regular thumbnails have identical color/contrast quality for all sizes.
                // Fix this by falling through and upgrading SD to 720.
                case SDDEFAULT, HQ720 -> HQ720;
                default -> quality;
            };
        }

        final String originalName;
        final String altImageName;

        ThumbnailQuality(String originalName, String altImageName) {
            this.originalName = originalName;
            this.altImageName = altImageName;
        }

        String getAltImageNameToUse() {
            return altImageName + Settings.DEARROW_THUMBNAIL_STILLS_TIME.get().altImageNumber;
        }
    }

    /**
     * Keeps track of which still capture qualities failed to load.
     */
    private static class UnavailableQualities {
        /**
         * After a quality fails to load, how long until the quality is tried again.
         * Intended for live streams and unreleased videos that are now finished and available
         * (and thus, the alt thumbnails are also now available).
         */
        private static final long NOT_AVAILABLE_TIMEOUT_MILLISECONDS = 10 * 60 * 1000; // 10 minutes.

        /**
         * Video id -> qualities that failed to load.
         */
        private static final Map<String, UnavailableQualities> unavailableVideoIdLookup =
                Collections.synchronizedMap(Utils.createSizeRestrictedMap(1000));

        /**
         * @return The quality to try for the video, which is the quality if it has not failed to load,
         *         a lower quality, or null if the original thumbnail must be used.
         */
        @Nullable
        static ThumbnailQuality getQualityToTry(String videoId, ThumbnailQuality quality) {
            UnavailableQualities unavailable = unavailableVideoIdLookup.get(videoId);
            if (unavailable == null) {
                return quality; // Unknown if it exists. Use the URL anyway and update afterward if loading fails.
            }
            return unavailable.getAvailableQuality(videoId, quality);
        }

        static void setQualityNotAvailable(String videoId, ThumbnailQuality quality) {
            UnavailableQualities unavailable = unavailableVideoIdLookup.computeIfAbsent(
                    videoId, key -> new UnavailableQualities());
            unavailable.setNotAvailable(videoId, quality);
        }

        /**
         * Lowest quality that failed to load. Higher qualities are assumed to also not be available.
         */
        @Nullable
        private ThumbnailQuality lowestQualityNotAvailable;

        /**
         * System time, of when to invalidate {@link #lowestQualityNotAvailable}.
         */
        private long timeToRetryLowestQuality;

        private synchronized void setNotAvailable(String videoId, ThumbnailQuality quality) {
            if (lowestQualityNotAvailable == null || lowestQualityNotAvailable.ordinal() > quality.ordinal()) {
                lowestQualityNotAvailable = quality;
                timeToRetryLowestQuality = System.currentTimeMillis() + NOT_AVAILABLE_TIMEOUT_MILLISECONDS;
            }
            Logger.printDebug(() -> quality + " not available for video: " + videoId);
        }

        @Nullable
        private synchronized ThumbnailQuality getAvailableQuality(String videoId, ThumbnailQuality quality) {
            if (lowestQualityNotAvailable == null || quality.ordinal() < lowestQualityNotAvailable.ordinal()) {
                return quality;
            }

            if (timeToRetryLowestQuality < System.currentTimeMillis()) {
                // Enough time has passed, and should try again.
                Logger.printDebug(() -> "Resetting lowest unavailable quality for: " + videoId);
                lowestQualityNotAvailable = null;
                return quality;
            }

            // A higher quality is not available, but SD is available for almost all videos with still captures.
            if (lowestQualityNotAvailable.ordinal() > ThumbnailQuality.SDDEFAULT.ordinal()) {
                return ThumbnailQuality.SDDEFAULT;
            }

            return null; // Use the original thumbnail.
        }
    }

    /**
     * YouTube video thumbnail url, decoded into its relevant parts.
     */
    private static class DecodedThumbnailURL {
        private static final String YOUTUBE_THUMBNAIL_DOMAIN = "https://i.ytimg.com/";

        @Nullable
        static DecodedThumbnailURL decodeImageURL(String url) {
            final int urlPathStartIndex = url.indexOf('/', "https://".length()) + 1;
            if (urlPathStartIndex <= 0) return null;

            final int urlPathEndIndex = url.indexOf('/', urlPathStartIndex);
            if (urlPathEndIndex < 0) return null;

            final int videoIdStartIndex = url.indexOf('/', urlPathEndIndex) + 1;
            if (videoIdStartIndex <= 0) return null;

            final int videoIdEndIndex = url.indexOf('/', videoIdStartIndex);
            if (videoIdEndIndex < 0) return null;

            final int imageSizeStartIndex = videoIdEndIndex + 1;
            final int imageSizeEndIndex = url.indexOf('.', imageSizeStartIndex);
            if (imageSizeEndIndex < 0) return null;

            int imageExtensionEndIndex = url.indexOf('?', imageSizeEndIndex);
            if (imageExtensionEndIndex < 0) imageExtensionEndIndex = url.length();

            return new DecodedThumbnailURL(url, urlPathStartIndex, urlPathEndIndex, videoIdStartIndex, videoIdEndIndex,
                    imageSizeStartIndex, imageSizeEndIndex, imageExtensionEndIndex);
        }

        /** Full usable url, but stripped of any tracking information. */
        final String sanitizedURL;
        /** URL path, such as 'vi' or 'vi_webp' */
        final String urlPath;
        final String videoId;
        /** Quality, such as hq720 or sddefault. */
        final String imageQuality;
        /** JPG or WEBP */
        final String imageExtension;
        /** User view tracking parameters, only present on some images. */
        final String viewTrackingParameters;

        DecodedThumbnailURL(String fullURL, int urlPathStartIndex, int urlPathEndIndex, int videoIdStartIndex, int videoIdEndIndex,
                            int imageSizeStartIndex, int imageSizeEndIndex, int imageExtensionEndIndex) {
            sanitizedURL = fullURL.substring(0, imageExtensionEndIndex);
            urlPath = fullURL.substring(urlPathStartIndex, urlPathEndIndex);
            videoId = fullURL.substring(videoIdStartIndex, videoIdEndIndex);
            imageQuality = fullURL.substring(imageSizeStartIndex, imageSizeEndIndex);
            imageExtension = fullURL.substring(imageSizeEndIndex + 1, imageExtensionEndIndex);
            viewTrackingParameters = (imageExtensionEndIndex == fullURL.length())
                    ? "" : fullURL.substring(imageExtensionEndIndex);
        }

        /**
         * @return The still capture URL, without view tracking parameters.
         */
        String createStillsURL(ThumbnailQuality qualityToUse) {
            // Images could be upgraded to webp if they are not already, but this fails quite often,
            // especially for new videos uploaded in the last hour.
            // And even if alt webp images do exist, sometimes they can load much slower than the original jpg alt images.
            // (as much as 4x slower network response has been observed, despite the alt webp image being a smaller file).
            // Many different "i.ytimage.com" domains exist such as "i9.ytimg.com",
            // but still captures are frequently not available on the other domains (especially newly uploaded videos).
            // So always use the primary domain for a higher success rate.
            return YOUTUBE_THUMBNAIL_DOMAIN + urlPath + '/'
                    + videoId + '/' + qualityToUse.getAltImageNameToUse() + '.' + imageExtension;
        }
    }
}
