package app.morphe.extension.shared.spoof.js;

import static app.morphe.extension.shared.Utils.isNotEmpty;

import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.Format;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.StreamingData;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.settings.BaseSettings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import java.net.URL;

/**
 * The functions used in this class are referenced below:
 * - <a href="https://github.com/TeamNewPipe/NewPipeExtractor/blob/d9e9911e78d5a6db45c1daeeea5280d10ca3f70d/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/YoutubeJavaScriptExtractor.java">TeamNewPipe/NewPipeExtractor#YoutubeJavaScriptExtractor</a>
 * - <a href="https://github.com/TeamNewPipe/NewPipeExtractor/blob/d9e9911e78d5a6db45c1daeeea5280d10ca3f70d/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/YoutubeJavaScriptPlayerManager.java">TeamNewPipe/NewPipeExtractor#YoutubeJavaScriptPlayerManager</a>
 * - <a href="https://github.com/TeamNewPipe/NewPipeExtractor/blob/d9e9911e78d5a6db45c1daeeea5280d10ca3f70d/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/YoutubeSignatureUtils.java">TeamNewPipe/NewPipeExtractor#YoutubeSignatureUtils</a>
 * - <a href="https://github.com/TeamNewPipe/NewPipeExtractor/blob/d9e9911e78d5a6db45c1daeeea5280d10ca3f70d/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/YoutubeThrottlingParameterUtils.java">TeamNewPipe/NewPipeExtractor#YoutubeThrottlingParameterUtils</a>
 */
public final class JavaScriptManager {
    /**
     * Regular expression pattern to find the signature timestamp.
     */
    private static final Pattern SIGNATURE_TIMESTAMP_PATTERN = Pattern.compile("signatureTimestamp[=:](\\d+)");
    /**
     * Regular expression pattern to find the 'n' parameter in streamingUrl.
     */
    private static final Pattern THROTTLING_PARAM_N_PATTERN = Pattern.compile("[&?]n=([^&]+)");
    /**
     * Regular expression pattern to find the 's' parameter in signatureCipher.
     */
    private static final Pattern THROTTLING_PARAM_S_PATTERN = Pattern.compile("s=([^&]+)");
    /**
     * Regular expression pattern to find the 'url' parameter in signatureCipher.
     */
    private static final Pattern THROTTLING_PARAM_URL_PATTERN = Pattern.compile("&url=([^&]+)");
    /**
     * Regular expression pattern to find variables used in JavaScript url.
     */
    private static final Pattern PLAYER_JS_HASH_PATTERN =
            Pattern.compile("player\\\\/([a-z0-9]{8})\\\\/");
    /**
     * Variant of JavaScript url.
     */
    private static final JavaScriptVariant PLAYER_JS_VARIANT = BaseSettings.SPOOF_VIDEO_STREAMS_JS_VARIANT.get();
    /**
     * Format of JavaScript url.
     */
    private static final String BASE_JS_PLAYER_URL_FORMAT = PLAYER_JS_VARIANT.format;
    /**
     * Url used to find variables used in JavaScript url.
     */
    private static final String IFRAME_API_URL = "https://www.youtube.com/iframe_api";
    /**
     * Player JavaScript is approximately 3MB in size,
     * So downloading it every time the app is launched results in unnecessary data usage.
     * Player JavaScript has a lifespan of approximately one month, and new versions are released from the server every 3 days.
     * Downloaded player JavaScript is saved in the cache directory and remains valid for approximately 3 days.
     */
    private static final long PLAYER_JS_CACHE_EXPIRATION_MILLISECONDS = 3 * 24 * 60 * 60 * 1000L; // 3 days.
    /**
     * User-agent of the TV client.
     */
    private static final String USER_AGENT =
            "Mozilla/5.0 (SMART-TV; Linux; Tizen 8.0) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/7.0 Chrome/108.0.5359.1 TV Safari/537.36";
    /**
     * Class used to deobfuscate, powered by SmartTube.
     */
    @Nullable
    private volatile static PlayerDataExtractor cachedPlayerDataExtractor = null;
    /**
     * Javascript contents.
     */
    @Nullable
    private volatile static String cachedPlayerJs = null;
    /**
     * Javascript file to be saved in the cache directory.
     */
    @Nullable
    private volatile static File cachedPlayerJsFile = null;
    /**
     * Javascript url hash.
     */
    @Nullable
    private volatile static String cachedPlayerJsHash = null;
    /**
     * Javascript url.
     */
    @Nullable
    private volatile static String cachedPlayerJsUrl = null;
    /**
     * Field value included when sending a request.
     */
    @Nullable
    private volatile static Integer cachedSignatureTimestamp = null;

    private JavaScriptManager() {
    }

    @Nullable
    private static PlayerDataExtractor getPlayerDataExtractor() {
        if (cachedPlayerDataExtractor == null) {
            String playerJs = getPlayerJs();
            if (isNotEmpty(playerJs)) {
                cachedPlayerDataExtractor = new PlayerDataExtractor(playerJs, Objects.requireNonNull(cachedPlayerJsHash));
            } else {
                Logger.printException(() -> "playerJs not found");
            }
        }

        return cachedPlayerDataExtractor;
    }

    @Nullable
    private static String getPlayerJs() {
        if (cachedPlayerJs == null) {
            String playerJsUrl = getPlayerJsUrl();
            if (isNotEmpty(playerJsUrl)) {
                File cacheFile = Objects.requireNonNull(cachedPlayerJsFile);
                String cacheFileName = cacheFile.getName();
                long currentTime = System.currentTimeMillis();

                // There is a player javascript saved in the cache and it was saved within 3 days.
                // Use the player javascript saved in the cache.
                if (cacheFile.exists() && (currentTime - cacheFile.lastModified()) < PLAYER_JS_CACHE_EXPIRATION_MILLISECONDS) {
                    String cachedData = readFromFile(cacheFile);
                    if (isNotEmpty(cachedData)) {
                        Logger.printDebug(() -> "Player js cache found: " + cacheFileName);
                        cachedPlayerJs = cachedData;
                        return cachedData;
                    }
                }

                // There is no player javascript save in the cache,
                // or if there is a player javascript, it has been more than 3 days since it was last saved.
                // Download the latest player javascript.
                String playerJs = downloadUrl(playerJsUrl);
                if (isNotEmpty(playerJs)) {
                    cachedPlayerJs = playerJs;
                    saveToFile(cacheFile, playerJs);
                    Logger.printDebug(() -> "Saved Player js cache: " + cacheFileName);
                }
            } else {
                Logger.printException(() -> "playerJsUrl not found");
            }
        }

        return cachedPlayerJs;
    }

    @Nullable
    private static String getPlayerJsUrl() {
        if (cachedPlayerJsUrl == null) {
            long currentTime = System.currentTimeMillis();
            long lastSavedTime = BaseSettings.SPOOF_VIDEO_STREAMS_JS_SAVED_MILLISECONDS.get();

            if (!BaseSettings.SPOOF_VIDEO_STREAMS_JS_HASH.isSetToDefault()
                    && currentTime - lastSavedTime < PLAYER_JS_CACHE_EXPIRATION_MILLISECONDS) {
                // There is a hash saved in the settings and it was saved within 3 days.
                // Use the hash saved in the settings.
                cachedPlayerJsHash = BaseSettings.SPOOF_VIDEO_STREAMS_JS_HASH.get();
                String playerJsHash = cachedPlayerJsHash;
                Logger.printDebug(() -> "Player js hash found in cache: " + playerJsHash);
            } else {
                // There is no hash save in the settings,
                // or if there is a hash, it has been more than 3 days since it was last saved.
                // To get the latest hash, fetch the iframe API.
                String iframeContent = downloadUrl(IFRAME_API_URL);
                if (isNotEmpty(iframeContent)) {
                    Matcher matcher = PLAYER_JS_HASH_PATTERN.matcher(iframeContent);
                    if (matcher.find()) {
                        cachedPlayerJsHash = matcher.group(1);
                        BaseSettings.SPOOF_VIDEO_STREAMS_JS_HASH.save(cachedPlayerJsHash);
                        BaseSettings.SPOOF_VIDEO_STREAMS_JS_SAVED_MILLISECONDS.save(currentTime);
                    } else {
                        Logger.printException(() -> "iframeContent not found");
                    }
                }
            }

            if (isNotEmpty(cachedPlayerJsHash)) {
                cachedPlayerJsFile = new File(Utils.getContext().getCacheDir(), "player_js_" + PLAYER_JS_VARIANT.name().toLowerCase() + "_" + cachedPlayerJsHash + ".js");
                cachedPlayerJsUrl = String.format(BASE_JS_PLAYER_URL_FORMAT, cachedPlayerJsHash);
            }
        }

        return cachedPlayerJsUrl;
    }

    @Nullable
    public static Integer getSignatureTimestamp() {
        if (cachedSignatureTimestamp == null) {
            try {
                String playerJs = getPlayerJs();
                if (isNotEmpty(playerJs)) {
                    Matcher matcher = SIGNATURE_TIMESTAMP_PATTERN.matcher(playerJs);
                    if (matcher.find()) {
                        String signatureTimestamp = matcher.group(1);
                        if (isNotEmpty(signatureTimestamp)) {
                            cachedSignatureTimestamp = Integer.parseInt(signatureTimestamp);
                        } else {
                            Logger.printException(() -> "SignatureTimestamp is null or empty");
                        }
                    } else {
                        Logger.printException(() -> "SignatureTimestamp not found");
                    }
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Failed to set SignatureTimestamp", ex);
            }
        }

        return cachedSignatureTimestamp;
    }

    @Nullable
    public static String getJavaScriptHash() {
        return cachedPlayerJsHash;
    }

    @NonNull
    public static String getJavaScriptVariant() {
        return PLAYER_JS_VARIANT.name();
    }

    @Nullable
    public static String downloadUrl(@NonNull String url) {
        String content = null;

        try {
            final long start = System.currentTimeMillis();
            content = Utils.submitOnBackgroundThread(() -> {
                final int connectionTimeoutMillis = 5000;
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setFixedLengthStreamingMode(0);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("User-Agent", USER_AGENT);
                connection.setConnectTimeout(connectionTimeoutMillis);
                connection.setReadTimeout(connectionTimeoutMillis);
                final int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    return Requester.parseStringAndDisconnect(connection);
                }
                connection.disconnect();
                return null;
            }).get();
            Logger.printDebug(() -> "Download took: " + (System.currentTimeMillis() - start) + "ms for url: " + url);
        } catch (ExecutionException | InterruptedException ex) {
            Logger.printException(() -> "Could not download url: " + url, ex);
        }

        return content;
    }

    /**
     * JavaScript clients require deciphering.
     * Deciphering is performed in format, adaptiveFormat, and serverAbrStreamingUrl.
     * Deciphering is performed by executing the player JavaScript with the V8 runtime.
     * A single execution of the V8 runtime requires significant computational time.
     * To minimize computational time, all query parameters requiring deciphering are stored in a single request (List),
     * and all query parameters are deobfuscated with a single run of the runtime.
     *
     * @param streamingData StreamingData containing obfuscated parameters.
     * @return              StreamingData builder containing deobfuscated parameters.
     */
    public static StreamingData.Builder getDeobfuscatedStreamingData(StreamingData streamingData) {
        StreamingData.Builder streamingDataBuilder = streamingData.toBuilder();
        String serverAbrStreamingUrl = streamingData.getServerAbrStreamingUrl();

        // Initialize streamingDataBuilder before adding formats.
        streamingDataBuilder.clearFormats();

        // Deobfuscate formats.
        deobfuscateFormat(
                streamingDataBuilder,
                streamingData.getFormatsList(),
                serverAbrStreamingUrl,
                false
        );

        // Initialize streamingDataBuilder before adding adaptiveFormats.
        streamingDataBuilder.clearAdaptiveFormats();

        // Deobfuscate adaptiveFormats.
        deobfuscateFormat(
                streamingDataBuilder,
                streamingData.getAdaptiveFormatsList(),
                serverAbrStreamingUrl,
                true
        );

        return streamingDataBuilder;
    }

    private static void deobfuscateFormat(StreamingData.Builder streamingDataBuilder,
                                         List<Format> formats,
                                         String serverAbrStreamingUrl,
                                         boolean isAdaptiveFormats) {
        PlayerDataExtractor playerDataExtractor = getPlayerDataExtractor();

        if (playerDataExtractor != null && formats != null && !formats.isEmpty()) {
            // In streamingData, all n-parameters have the same value.
            String obfuscatedNParameter = null;

            List<String> obfuscatedSParameters = new ArrayList<>();
            List<String> obfuscatedUrlParameters = new ArrayList<>();

            // formats or adaptiveFormats have a signatureCipher or a url.
            // Therefore, the computation time can be reduced by checking whether the first format has a signatureCipher or not.
            boolean hasSignatureCipher = isNotEmpty(formats.get(0).getSignatureCipher());

            for (Format format : formats) {
                // If a signatureCipher is present, the url field must be assembled while iterating over each format.
                if (hasSignatureCipher) {
                    String signatureCipher = format.getSignatureCipher();
                    Matcher sParamMatcher = THROTTLING_PARAM_S_PATTERN.matcher(signatureCipher);
                    Matcher urlParamMatcher = THROTTLING_PARAM_URL_PATTERN.matcher(signatureCipher);
                    if (sParamMatcher.find() && urlParamMatcher.find()) {
                        // The s-parameter from signatureCipher.
                        String obfuscatedSParameter = sParamMatcher.group(1);
                        // The url-parameter from signatureCipher.
                        String obfuscatedUrl = urlParamMatcher.group(1);
                        if (isNotEmpty(obfuscatedSParameter) && isNotEmpty(obfuscatedUrl)) {
                            obfuscatedUrl = decodeURL(obfuscatedUrl);

                            obfuscatedSParameters.add(decodeURL(obfuscatedSParameter));
                            obfuscatedUrlParameters.add(obfuscatedUrl);

                            if (obfuscatedNParameter == null) {
                                obfuscatedNParameter = getNQueryParameter(obfuscatedUrl);
                            }
                        }
                    }
                } else { // If a url is present, simply iterate over each format and replace the n-parameters.
                    String nQueryParameter = getNQueryParameter(format.getUrl());
                    if (isNotEmpty(nQueryParameter)) {
                        obfuscatedNParameter = nQueryParameter;
                        break;
                    }
                }
            }

            // streamingData always has one n-parameter.
            if (isNotEmpty(obfuscatedNParameter)) {
                var results = playerDataExtractor.bulkSigExtract(
                        Collections.singletonList(obfuscatedNParameter),
                        obfuscatedSParameters
                );

                // Since there is only one obfuscated n-parameter, there is also only one deobfuscated n-parameter
                String deobfuscatedNParameter = results.first.get(0);
                List<String> deobfuscatedSParameters = results.second;

                int i = 0;
                for (Format format : formats) {
                    Format.Builder formatBuilder = format.toBuilder();
                    String obfuscatedUrl = hasSignatureCipher
                            // Assemble the url.
                            ? obfuscatedUrlParameters.get(i) + "&sig=" + deobfuscatedSParameters.get(i)
                            : format.getUrl();
                    formatBuilder.setUrl(obfuscatedUrl.replace(obfuscatedNParameter, deobfuscatedNParameter));
                    formatBuilder.clearSignatureCipher();
                    Format newFormat = formatBuilder.build();

                    if (isAdaptiveFormats) {
                        streamingDataBuilder.addAdaptiveFormats(newFormat);
                    } else {
                        streamingDataBuilder.addFormats(newFormat);
                    }
                    i++;
                }

                streamingDataBuilder.setServerAbrStreamingUrl(isNotEmpty(serverAbrStreamingUrl)
                        ? serverAbrStreamingUrl.replace(obfuscatedNParameter, deobfuscatedNParameter)
                        : ""
                );
            }
        }
    }

    private static String getNQueryParameter(String url) {
        Matcher matcher = THROTTLING_PARAM_N_PATTERN.matcher(url);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String decodeURL(String urlDecoded) {
        try {
            //noinspection CharsetObjectCanBeUsed
            urlDecoded = URLDecoder.decode(urlDecoded, "UTF-8");
        } catch (UnsupportedEncodingException ex) {
            Logger.printException(() -> "Failed to decode url", ex);
        }
        return urlDecoded;
    }

    private static String readFromFile(File file) {
        StringBuilder sb = new StringBuilder();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                //noinspection ReadWriteStringCanBeUsed
                return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            } else {
                FileInputStream fis = new FileInputStream(file);
                BufferedReader reader = new BufferedReader(new InputStreamReader(fis));
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                return sb.toString();
            }
        } catch (IOException ex) {
            Logger.printException(() -> "Failed to read file", ex);
            return null;
        }
    }

    private static void saveToFile(File file, String content) {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            Logger.printException(() -> "Failed to save file", ex);
        }
    }
}