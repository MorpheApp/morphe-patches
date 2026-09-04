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
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import app.morphe.extension.youtube.innertube.ConfigResponseOuterClass.ConfigResponse;
import app.morphe.extension.youtube.innertube.ConfigResponseOuterClass.Context;
import app.morphe.extension.youtube.innertube.ConfigResponseOuterClass.GlobalConfigGroup;
import app.morphe.extension.youtube.innertube.ConfigResponseOuterClass.RawColdConfigGroup;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class ConfigRequest {
    private static final int MAX_MILLISECONDS_TO_WAIT_FOR_FETCH = 20 * 1000;

    private record ConfigGroup(String coldConfigData, String coldHashData) {
    }

    public static void fetchRequest(Map<String, String> requestHeader) {
        CompletableFuture<ConfigGroup> future = CompletableFuture.supplyAsync(() -> send(requestHeader));
        try {
            ConfigGroup configGroup = future.get(MAX_MILLISECONDS_TO_WAIT_FOR_FETCH, TimeUnit.MILLISECONDS);
            if (configGroup != null) {
                String coldConfigData = configGroup.coldConfigData;
                String coldHashData = configGroup.coldHashData;
                Settings.INNERTUBE_COLD_CONFIG_DATA.save(coldConfigData);
                Settings.INNERTUBE_COLD_HASH_DATA.save(coldHashData);
            }
        } catch (TimeoutException ex) {
            Logger.printInfo(() -> "getConfigGroup timed out", ex);
            future.cancel(true);
        } catch (CancellationException ex) {
            Logger.printInfo(() -> "getConfigGroup was previously cancelled");
        } catch (InterruptedException ex) {
            Logger.printException(() -> "getConfigGroup interrupted", ex);
            future.cancel(true);
            Thread.currentThread().interrupt(); // Restore interrupt status flag.
        } catch (ExecutionException ex) {
            Logger.printException(() -> "getConfigGroup failure", ex);
        }
    }

    private static void handleConnectionError(String toastMessage, @Nullable Exception ex) {
        Logger.printInfo(() -> toastMessage, ex);
    }

    @Nullable
    private static ConfigGroup parse(@NonNull HttpURLConnection connection) {
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
            String coldHashData = globalConfigGroup.getColdHashData();
            if (!Utils.isNotEmpty(coldHashData)) {
                Logger.printDebug(() -> "ColdHashData is empty");
                return null;
            }
            if (!globalConfigGroup.hasRawColdConfigGroup()) {
                Logger.printDebug(() -> "RawColdConfigGroup is empty");
                return null;
            }
            RawColdConfigGroup rawColdConfigGroup = globalConfigGroup.getRawColdConfigGroup();
            String coldConfigData = rawColdConfigGroup.getConfigData();
            if (!Utils.isNotEmpty(coldConfigData)) {
                Logger.printDebug(() -> "ConfigData is empty");
                return null;
            }
            return new ConfigGroup(coldConfigData, coldHashData);
        } catch (Exception e) {
            Logger.printException(() -> "Parse failed", e);
        }

        return null;
    }

    @Nullable
    private static ConfigGroup send(Map<String, String> requestHeader) {
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
