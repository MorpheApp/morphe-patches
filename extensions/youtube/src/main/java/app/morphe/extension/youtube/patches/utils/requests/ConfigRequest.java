/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.utils.requests;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.youtube.innertube.ConfigResponseOuterClass.ConfigResponse;
import app.morphe.extension.youtube.innertube.ConfigResponseOuterClass.Context;
import app.morphe.extension.youtube.innertube.ConfigResponseOuterClass.GlobalConfigGroup;
import app.morphe.extension.youtube.innertube.ConfigResponseOuterClass.RawColdConfigGroup;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public class ConfigRequest {
    private static final int MAX_MILLISECONDS_TO_WAIT_FOR_FETCH = 20 * 1000;
    private static ConfigRequest configRequest;
    private final Future<String> future;

    private ConfigRequest(Map<String, String> requestHeader) {
        this.future = Utils.submitOnBackgroundThread(() -> send(requestHeader));
    }

    @Nullable
    public String getConfig() {
        try {
            if (BaseSettings.DEBUG.get() && !future.isDone() && Utils.isCurrentlyOnMainThread()) {
                Logger.printException(() -> "Debug: Blocking main thread");
            }
            return future.get(MAX_MILLISECONDS_TO_WAIT_FOR_FETCH, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            Logger.printInfo(() -> "getConfig timed out", ex);
        } catch (InterruptedException ex) {
            Logger.printException(() -> "getConfig interrupted", ex);
            Thread.currentThread().interrupt();
        } catch (ExecutionException ex) {
            Logger.printException(() -> "getConfig failure", ex);
        }
        return null;
    }

    public static void clear() {
        configRequest = null;
    }

    public static void fetchRequest(Map<String, String> requestHeader) {
        if (configRequest == null) {
            configRequest = new ConfigRequest(requestHeader);
        }
    }

    @Nullable
    public static ConfigRequest getRequest() {
        return configRequest;
    }

    private static void handleConnectionError(String toastMessage, @Nullable Exception ex) {
        Logger.printInfo(() -> toastMessage, ex);
    }

    @Nullable
    private static String parse(@NonNull HttpURLConnection connection) {
        try (InputStream inputStream = connection.getInputStream()) {
            ConfigResponse configResponse = ConfigResponse.parseFrom(inputStream);
            if (!configResponse.hasContext()) {
                Logger.printDebug(() -> "Context is empty");
                return null;
            }
            Context context = configResponse.getContext();
            if (!context.hasGlobalConfigGroup()) {
                Logger.printDebug(() -> "GlobalConfigGroup is empty");
                return null;
            }
            GlobalConfigGroup globalConfigGroup = context.getGlobalConfigGroup();
            if (!globalConfigGroup.hasRawColdConfigGroup()) {
                Logger.printDebug(() -> "RawColdConfigGroup is empty");
                return null;
            }
            RawColdConfigGroup rawColdConfigGroup = globalConfigGroup.getRawColdConfigGroup();
            if (!rawColdConfigGroup.hasConfigData()) {
                Logger.printDebug(() -> "ConfigData is empty");
                return null;
            }
            return rawColdConfigGroup.getConfigData();
        } catch (Exception e) {
            Logger.printException(() -> "Parse failed", e);
        }

        return null;
    }

    @Nullable
    private static String send(Map<String, String> requestHeader) {
        Utils.verifyOffMainThread();

        final long startTime = System.currentTimeMillis();
        Logger.printDebug(() -> "Fetching config request");

        try {
            byte[] requestBody = ConfigRoutes.configBody();
            HttpURLConnection connection = ConfigRoutes.getConnection(ConfigRoutes.GET_CONFIG, requestHeader);
            connection.setFixedLengthStreamingMode(requestBody.length);
            connection.getOutputStream().write(requestBody);
            int responseCode = connection.getResponseCode();
            if (responseCode == 200 && connection.getContentLength() != 0) {
                return parse(connection);
            }
            handleConnectionError("Config request failed with code: " + responseCode, null);
        } catch (SocketTimeoutException ex) {
            handleConnectionError("Connection timeout", ex);
        } catch (IOException ex) {
            handleConnectionError("Network error", ex);
        } catch (Exception ex) {
            Logger.printException(() -> "sendRequest failed", ex);
        } finally {
            Logger.printDebug(() -> "Fetched config request, took: " + (System.currentTimeMillis() - startTime) + "ms");
        }
        return null;
    }
}
