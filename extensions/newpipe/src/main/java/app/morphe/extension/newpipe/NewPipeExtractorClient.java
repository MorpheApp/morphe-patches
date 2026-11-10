/*
 * Copyright 2026 Morphe.
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 *
 * Extractor initialization follows r-i-s-u/NewPipe, commit
 * 8884ce5d0e3d31be61b239fae5f7140fd3802b87, App.kt and PoTokenProviderImpl.kt.
 */
package app.morphe.extension.newpipe;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.services.youtube.InnertubeClientRequestInfo;
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper;
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.spoof.potoken.PoTokenGenerator;
import app.morphe.extension.shared.spoof.potoken.PoTokenResult;

/** Resolves a YouTube item using the same extractor as NewPipe Downloader. */
public final class NewPipeExtractorClient {
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0";
    private static final int TIMEOUT_MS = 30_000;
    private static final int MAX_RESPONSE_BYTES = 12 * 1024 * 1024;
    private static final PoTokenGenerator tokenGenerator = new PoTokenGenerator();
    private static boolean initialized;
    private static String tokenVisitor;
    private static PoTokenResult lastToken;

    private NewPipeExtractorClient() {
    }

    private static synchronized void initialize() {
        if (initialized) return;
        NewPipe.init(new HttpDownloader());
        YoutubeStreamExtractor.setPoTokenProvider(NewPipeExtractorClient::tokenForVideo);
        initialized = true;
    }

    private static synchronized org.schabi.newpipe.extractor.services.youtube.PoTokenResult tokenForVideo(
            String videoId) {
        try {
            if (tokenVisitor == null || (lastToken != null && lastToken.isExpired())) {
                InnertubeClientRequestInfo client = InnertubeClientRequestInfo.ofWebClient();
                client.clientInfo.clientVersion = YoutubeParsingHelper.getClientVersion();
                tokenVisitor = YoutubeParsingHelper.getVisitorDataFromInnertube(
                        client, NewPipe.getPreferredLocalization(), NewPipe.getPreferredContentCountry(),
                        YoutubeParsingHelper.getYouTubeHeaders(), YoutubeParsingHelper.YOUTUBEI_V1_URL,
                        null, false);
            }
            lastToken = tokenGenerator.getWebClientPoToken(videoId, tokenVisitor);
            if (lastToken == null) return null;
            return new org.schabi.newpipe.extractor.services.youtube.PoTokenResult(
                    tokenVisitor, lastToken.playerRequestPoToken, lastToken.streamingDataPoToken);
        } catch (Exception ex) {
            Logger.printException(() -> "NewPipe PoToken generation failed", ex);
            return null;
        }
    }

    /** Must be called off the main thread. */
    public static StreamInfo resolve(String videoId) throws Exception {
        if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}")) {
            throw new IllegalArgumentException("Invalid video ID");
        }
        initialize();
        return StreamInfo.getInfo("https://www.youtube.com/watch?v=" + videoId);
    }

    private static final class HttpDownloader extends Downloader {
        @Override
        public Response execute(Request request) throws IOException, ReCaptchaException {
            HttpURLConnection connection = (HttpURLConnection) new URL(request.url()).openConnection();
            try {
                connection.setConnectTimeout(TIMEOUT_MS);
                connection.setReadTimeout(TIMEOUT_MS);
                connection.setRequestMethod(request.httpMethod());
                connection.setRequestProperty("User-Agent", USER_AGENT);
                for (Map.Entry<String, List<String>> header : request.headers().entrySet()) {
                    List<String> values = header.getValue();
                    for (int index = 0; index < values.size(); index++) {
                        if (index == 0) connection.setRequestProperty(header.getKey(), values.get(index));
                        else connection.addRequestProperty(header.getKey(), values.get(index));
                    }
                }
                byte[] data = request.dataToSend();
                if (data != null) {
                    connection.setDoOutput(true);
                    try (OutputStream output = connection.getOutputStream()) {
                        output.write(data);
                    }
                }
                final int status = connection.getResponseCode();
                if (status == 429) throw new ReCaptchaException("reCaptcha requested", request.url());
                InputStream body = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                String text = "";
                if (body != null) {
                    if ("gzip".equalsIgnoreCase(connection.getContentEncoding())) {
                        body = new GZIPInputStream(body);
                    }
                    try (InputStream input = body; ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                        byte[] chunk = new byte[8192];
                        int count;
                        while ((count = input.read(chunk)) != -1) {
                            if (buffer.size() + count > MAX_RESPONSE_BYTES) {
                                throw new IOException("Extractor response is too large");
                            }
                            buffer.write(chunk, 0, count);
                        }
                        text = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
                    }
                }
                Map<String, List<String>> headers = new HashMap<>(connection.getHeaderFields());
                headers.remove(null);
                return new Response(status, connection.getResponseMessage(), headers, text,
                        connection.getURL().toString());
            } finally {
                connection.disconnect();
            }
        }
    }
}
