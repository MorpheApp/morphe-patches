package app.morphe.extension.shared.spoof.js;

import static app.morphe.extension.shared.Utils.isNotEmpty;

import android.os.Build;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;

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
     * Typically, there are 10 to 30 available formats for a video.
     * Each format has a different streaming url, but the 'n' parameter in the response is the same.
     * If the obfuscated 'n' parameter and the deobfuscated 'n' parameter are put in a Map,
     * the remaining 9 to 29 streaming urls can be deobfuscated quickly using the values put in the Map.
     */
    @NonNull
    private static final Map<String, String> CACHED_THROTTLING_PARAMETERS = Collections.synchronizedMap(
            Utils.createSizeRestrictedMap(50));
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
    private static final Pattern PLAYER_JS_IDENTIFIER_PATTERN =
            Pattern.compile("player\\\\/([a-z0-9]{8})\\\\/");
    /**
     * Format of JavaScript url.
     */
    private static final String BASE_JS_PLAYER_URL_FORMAT =
            "https://www.youtube.com/s/player/%s/tv-player-ias.vflset/tv-player-ias.js";
    /**
     * Url used to find variables used in JavaScript url.
     */
    private static final String IFRAME_API_URL = "https://www.youtube.com/iframe_api";
    /**
     * Player JavaScript is approximately 3MB in size,
     * So downloading it every time the app is launched results in unnecessary data usage.
     * Player JavaScript has a lifespan of approximately one month, and new versions are released from the server every week.
     * Downloaded player JavaScript is saved in the cache directory and remains valid for approximately one week.
     */
    private static final long PLAYER_JS_CACHE_EXPIRATION_MILLISECONDS = 7 * 24 * 60 * 60 * 1000L; // 7 days.
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
     * Javascript url identifier.
     */
    @Nullable
    private volatile static String cachedPlayerJsIdentifier = null;
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
                cachedPlayerDataExtractor = new PlayerDataExtractor(playerJs, Objects.requireNonNull(cachedPlayerJsIdentifier));
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
                long currentTime = System.currentTimeMillis();

                if (cacheFile.exists() && (currentTime - cacheFile.lastModified()) < PLAYER_JS_CACHE_EXPIRATION_MILLISECONDS) {
                    String cachedData = readFromFile(cacheFile);
                    if (isNotEmpty(cachedData)) {
                        Logger.printDebug(() -> "Player js cache found: " + cachedPlayerJsIdentifier);
                        cachedPlayerJs = cachedData;
                        return cachedData;
                    }
                }

                String playerJs = downloadUrl(playerJsUrl);
                if (isNotEmpty(playerJs)) {
                    cachedPlayerJs = playerJs;
                    saveToFile(cacheFile, playerJs);
                    Logger.printDebug(() -> "Saved Player js cache: " + cachedPlayerJsIdentifier);
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
            String iframeContent = downloadUrl(IFRAME_API_URL);
            if (isNotEmpty(iframeContent)) {
                Matcher matcher = PLAYER_JS_IDENTIFIER_PATTERN.matcher(iframeContent);
                if (matcher.find()) {
                    cachedPlayerJsIdentifier = matcher.group(1);
                    cachedPlayerJsFile = new File(Utils.getContext().getCacheDir(), "player_js_" + cachedPlayerJsIdentifier + ".js");
                    cachedPlayerJsUrl = String.format(BASE_JS_PLAYER_URL_FORMAT, cachedPlayerJsIdentifier);
                } else {
                    Logger.printException(() -> "iframeContent not found");
                }
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

    @Nullable
    public static String deobfuscateStreamingUrl(
            @NonNull String videoId,
            @Nullable String url,
            @Nullable String signatureCipher) {
        String streamUrl = null;
        if (isNotEmpty(url)) {
            streamUrl = url;
        } else if (isNotEmpty(signatureCipher)) {
            streamUrl = getUrlWithThrottlingParameterObfuscated(
                    videoId,
                    signatureCipher
            );
        }
        if (isNotEmpty(streamUrl)) {
            return getUrlWithThrottlingParameterDeobfuscated(
                    videoId,
                    streamUrl
            );
        }
        return null;
    }

    /**
     * Convert signatureCipher to streaming url with obfuscated 'n' parameter.
     * <p>
     *
     * @param videoId         Current video id.
     * @param signatureCipher The 'signatureCipher' included in the response.
     * @return Streaming url with obfuscated 'n' parameter.
     */
    @Nullable
    private static String getUrlWithThrottlingParameterObfuscated(@NonNull String videoId, @NonNull String signatureCipher) {
        try {
            PlayerDataExtractor playerDataExtractor = getPlayerDataExtractor();
            if (playerDataExtractor != null) {
                Matcher paramSMatcher = THROTTLING_PARAM_S_PATTERN.matcher(signatureCipher);
                Matcher paramUrlMatcher = THROTTLING_PARAM_URL_PATTERN.matcher(signatureCipher);
                if (paramSMatcher.find() && paramUrlMatcher.find()) {
                    // The 's' parameter from signatureCipher.
                    String sParam = paramSMatcher.group(1);
                    // The 'url' parameter from signatureCipher.
                    String urlParam = paramUrlMatcher.group(1);
                    if (isNotEmpty(sParam) && isNotEmpty(urlParam)) {
                        // The 'sig' parameter converted by javascript rules.
                        String decodedSigParm = playerDataExtractor.extractSig(decodeURL(sParam));
                        if (isNotEmpty(decodedSigParm)) {
                            String decodedUriParm = decodeURL(urlParam);
                            Logger.printDebug(() -> "Converted signatureCipher to obfuscatedUrl, videoId: " + videoId);
                            return decodedUriParm + "&sig=" + decodedSigParm;
                        }
                    }
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to convert signatureCipher, videoId: " + videoId + ", signatureCipher: " + signatureCipher, ex);
        }

        Logger.printDebug(() -> "Failed to convert signatureCipher, videoId: " + videoId);
        return null;
    }

    /**
     * Deobfuscates the obfuscated 'n' parameter to a valid streaming url.
     * <p>
     *
     * @param videoId       Current video id.
     * @param obfuscatedUrl Streaming url with obfuscated 'n' parameter.
     * @return Deobfuscated streaming url.
     */
    @Nullable
    private static String getUrlWithThrottlingParameterDeobfuscated(@NonNull String videoId, @NonNull String obfuscatedUrl) {
        try {
            // Obfuscated url is empty.
            if (!isNotEmpty(obfuscatedUrl)) {
                Logger.printDebug(() -> "obfuscatedUrl is empty, videoId: " + videoId);
                return obfuscatedUrl;
            }

            // The 'n' parameter from obfuscatedUrl.
            String obfuscatedNParams = getThrottlingParameterFromStreamingUrl(obfuscatedUrl);

            // The 'n' parameter is null or empty.
            if (!isNotEmpty(obfuscatedNParams)) {
                Logger.printDebug(() -> "'n' parameter not found in obfuscated streaming url, videoId: " + videoId);
                return obfuscatedUrl;
            }

            // If the deobfuscated 'n' parameter is in the Map, return it.
            String deobfuscatedNParam = CACHED_THROTTLING_PARAMETERS.get(obfuscatedNParams);
            if (deobfuscatedNParam != null) {
                Logger.printDebug(() -> "Cached 'n' parameter found, videoId: " + videoId + ", deobfuscatedNParams: " + deobfuscatedNParam);
                return replaceNParam(obfuscatedUrl, obfuscatedNParams, deobfuscatedNParam);
            }

            // Deobfuscate the 'n' parameter.
            Pair<String, String> deobfuscatedNParamPairs = decodeNParam(obfuscatedUrl, obfuscatedNParams);
            String deobfuscatedUrl = deobfuscatedNParamPairs.first;
            String deobfuscatedNParams = deobfuscatedNParamPairs.second;
            if (!deobfuscatedNParams.isEmpty()) {
                // If the 'n' parameter obfuscation was successful, put it in the map.
                CACHED_THROTTLING_PARAMETERS.put(obfuscatedNParams, deobfuscatedNParams);
                Logger.printDebug(() -> "Deobfuscated the 'n' parameter, videoId: " + videoId + ", obfuscatedNParams: " + obfuscatedNParams + ", deobfuscatedNParams: " + deobfuscatedNParams);
                return deobfuscatedUrl;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to deobfuscate 'n' parameters, videoId: " + videoId + ", obfuscatedUrl: " + obfuscatedUrl, ex);
        }

        Logger.printDebug(() -> "Failed to deobfuscate 'n' parameter, videoId: " + videoId);
        return obfuscatedUrl;
    }

    /**
     * Extract the 'n' parameter from the streaming Url.
     * <p>
     *
     * @param streamingUrl The streaming url.
     * @return The 'n' parameter.
     */
    @Nullable
    private static String getThrottlingParameterFromStreamingUrl(@NonNull String streamingUrl) {
        if (streamingUrl.contains("&n=") || streamingUrl.contains("?n=")) {
            final Matcher matcher = THROTTLING_PARAM_N_PATTERN.matcher(streamingUrl);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return "";
    }

    /**
     * Replace the 'n' parameter.
     *
     * @param obfuscatedUrl       Streaming url with obfuscated 'n' parameter.
     * @param obfuscatedNParams   Obfuscated 'n' parameter.
     * @param deObfuscatedNParams Deobfuscated 'n' parameter.
     * @return Deobfuscated streaming url.
     */
    @NonNull
    private static String replaceNParam(@NonNull String obfuscatedUrl, @NonNull String obfuscatedNParams, @NonNull String deObfuscatedNParams) {
        return obfuscatedUrl.replaceFirst("n=" + obfuscatedNParams, "n=" + deObfuscatedNParams);
    }

    /**
     * Deobfuscate the 'n' parameter.
     * <p>
     *
     * @param obfuscatedUrl     Streaming url with obfuscated 'n' parameter.
     * @param obfuscatedNParams Obfuscated 'n' parameter.
     * @return Deobfuscated Pair(Deobfuscated streaming url, Deobfuscated 'n' parameter).
     */
    @NonNull
    private static Pair<String, String> decodeNParam(@NonNull String obfuscatedUrl, @NonNull String obfuscatedNParams) {
        try {
            PlayerDataExtractor playerDataExtractor = getPlayerDataExtractor();
            if (playerDataExtractor != null) {
                // The 'n' parameter deobfuscated by javascript rules.
                String deObfuscatedNParams = playerDataExtractor.extractNSig(obfuscatedNParams);
                if (isNotEmpty(deObfuscatedNParams)) {
                    String deObfuscatedUrl = replaceNParam(obfuscatedUrl, obfuscatedNParams, deObfuscatedNParams);
                    return new Pair<>(deObfuscatedUrl, deObfuscatedNParams);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to deobfuscate 'n' parameter, obfuscatedUrl: " + obfuscatedUrl + ", obfuscatedNParams: " + obfuscatedNParams, ex);
        }

        return new Pair<>(obfuscatedUrl, "");
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