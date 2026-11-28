/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5484
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.cast;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.Nullable;

import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Lists Cast devices in the app's own Cast menu, and lets the app's casting UI control a cast to the
 * Default Media Receiver.
 * <p>
 * The app connects to a TV through a lounge: a channel to YouTube's servers that relays messages such
 * as setPlaylist, play, pause and seekTo to the TV, and nowPlaying and onStateChange back. The Cast
 * devices are added to the app as paired lounge screens. When one is chosen, the channel of its session
 * does not connect to the servers. It answers as the TV would, and plays the video with
 * {@link CastAdFreeService}.
 */
@SuppressWarnings("unused")
public final class CastMenuLounge {

    /**
     * Added to the class of the MDX screen manager, whose screens the app lists in its Cast menu.
     */
    public interface ScreenManagerInterface {
        /**
         * Adds a paired screen with the name and ids.
         *
         * @return The screen.
         */
        Object patch_addScreen(String name, String screenId, String loungeDeviceId);

        /**
         * Removes a screen that {@link #patch_addScreen} added.
         */
        void patch_removeScreen(Object screen);
    }

    /**
     * Added to the class of a paired screen.
     */
    public interface ScreenInterface {
        /**
         * @return The {@link ScreenInfoInterface} of the screen.
         */
        Object patch_getScreenInfo();
    }

    /**
     * Added to the class of the screen information of a paired screen.
     */
    public interface ScreenInfoInterface {
        String patch_getLoungeDeviceId();
    }

    /**
     * Added to the class of the cloud channel, the connection of a session to the lounge.
     */
    public interface CloudChannelInterface {
        /**
         * Gives a lounge message to the session, as if the lounge sent it.
         */
        void patch_receiveMessage(String method, JSONObject data);

        /**
         * @return The parameters the session connects with, such as the video and position to play.
         */
        @Nullable
        Map<String, String> patch_getConnectParams();
    }

    /**
     * An already completed future, the result of a message sent to the fake lounge.
     */
    private static final class CompletedFuture implements ListenableFuture<Object> {
        @Override
        public void addListener(Runnable listener, Executor listenerExecutor) {
            listenerExecutor.execute(listener);
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        @Nullable
        public Object get() {
            return null;
        }

        @Override
        @Nullable
        public Object get(long timeout, TimeUnit unit) {
            return null;
        }
    }

    private static final String SCREEN_ID_PREFIX = "morphe-cast-screen-";
    private static final String DEVICE_ID_PREFIX = "morphe-cast-device-";
    private static final String SCREEN_NAME_SUFFIX = " (no ads)";
    /**
     * State of a connected cloud channel.
     */
    private static final int CHANNEL_CONNECTED = 2;
    /**
     * Lounge player states, as in the YouTube IFrame API.
     */
    private static final int STATE_ENDED = 0;
    private static final int STATE_PLAYING = 1;
    private static final int STATE_PAUSED = 2;
    private static final int STATE_BUFFERING = 3;
    /**
     * A scan of at least this long finds every device of the network.
     */
    private static final long FULL_SCAN_MILLISECONDS = 10000;
    /**
     * Key of the discovery request of a media route provider.
     */
    private static final String ACTIVE_SCAN_KEY = "activeScan";

    private static final int CPN_LENGTH = 16;
    private static final Random random = new Random();
    private static final ListenableFuture<Object> COMPLETED_FUTURE = new CompletedFuture();
    /**
     * The found Cast devices, by the lounge device id of their screen.
     */
    private static final Map<String, CastDeviceFinder.CastDevice> devicesById = new ConcurrentHashMap<>();
    /**
     * The screens added for the devices, by the lounge device id. Changed on the main thread, and read by
     * the route provider of the app.
     */
    private static final Map<String, Object> screensById = new ConcurrentHashMap<>();
    /**
     * The lounge device ids of the devices found by the current scan. Used on the main thread.
     */
    private static final Set<String> seenIds = new HashSet<>();
    private static long finderStartMillis;
    /**
     * How long after the Cast menu closed a session that connects was started by picking its device.
     */
    private static final long CAST_MENU_PICK_MILLISECONDS = 10_000;
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Nullable
    private static volatile ScreenManagerInterface screenManager;
    @Nullable
    private static CastDeviceFinder finder;
    /**
     * If the Cast menu of the app is open. Used on the main thread.
     */
    private static volatile boolean scanRequested;
    /**
     * When the Cast menu of the app last closed, such as when a device was picked in it.
     */
    private static volatile long castMenuClosedMillis;
    /**
     * If the local network permission was asked for in this run of the app. Used on the main thread.
     */
    private static boolean permissionRequested;
    /**
     * The channel of the session of one of our screens, and the lounge device id of that screen.
     */
    @Nullable
    private static volatile CloudChannelInterface channel;
    private static volatile String channelDeviceId = "";
    @Nullable
    private static volatile CastDeviceFinder.CastDevice device;
    private static volatile String videoId = "";
    private static volatile double startSeconds;
    private static volatile double durationSeconds;
    /**
     * Client playback nonce: the id of a playback, new for each video. The app keeps the time and duration
     * of a playback until the nonce changes.
     */
    private static volatile String cpn = "";

    // region Injection points

    /**
     * Injection point: end of the constructor of the MDX screen manager.
     */
    public static void setScreenManager(ScreenManagerInterface manager) {
        if (!Settings.CAST_AD_FREE_CAST_MENU.get()) {
            return;
        }
        Utils.runOnMainThread(() -> screenManager = manager);
    }

    /**
     * Injection point: start of the discovery request change of the media route provider of the screens.
     * The request is active while the Cast menu of the app is open.
     */
    public static void onRouteDiscoveryRequest(@Nullable Bundle request) {
        if (!Settings.CAST_AD_FREE_CAST_MENU.get()) {
            return;
        }
        final boolean activeScan = request != null && request.getBoolean(ACTIVE_SCAN_KEY);
        Utils.runOnMainThread(() -> {
            if (scanRequested && !activeScan) {
                castMenuClosedMillis = System.currentTimeMillis();
            }
            scanRequested = activeScan;
            if (activeScan) {
                startFinder();
            } else {
                stopFinder();
            }
        });
    }

    /**
     * Injection point: description of the route of a screen in the Cast menu.
     *
     * @return No description for our screens, which the menu would show as "Playing YouTube".
     */
    public static String getRouteDescription(Object screen, String original) {
        if (screenManager == null) {
            return original;
        }
        try {
            // Checked by id, as the app builds the route of a screen while the screen is added.
            if (screen instanceof ScreenInterface screenWithInfo
                    && screenWithInfo.patch_getScreenInfo() instanceof ScreenInfoInterface screenInfo
                    && isOurDeviceId(screenInfo.patch_getLoungeDeviceId())) {
                return "";
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getRouteDescription failure", ex);
        }
        return original;
    }

    /**
     * Injection point: start of removing a paired screen. The app regularly replaces the paired screens
     * with the ones of the account, which would remove ours.
     *
     * @return If the screen is ours, and is kept.
     */
    public static boolean keepScreen(ScreenInfoInterface screenInfo) {
        try {
            return !devicesById.isEmpty() && devicesById.containsKey(screenInfo.patch_getLoungeDeviceId());
        } catch (Exception ex) {
            Logger.printException(() -> "keepScreen failure", ex);
            return false;
        }
    }

    /**
     * Injection point: start of a cloud session connecting its channel to the screen.
     */
    public static void onSessionConnect(CloudChannelInterface cloudChannel, ScreenInfoInterface screenInfo) {
        if (!Settings.CAST_AD_FREE_CAST_MENU.get()) {
            return;
        }
        try {
            String deviceId = screenInfo.patch_getLoungeDeviceId();
            if (!isOurDeviceId(deviceId)) {
                return;
            }
            Logger.printDebug(() -> "Session connecting to our screen: " + deviceId);
            channel = cloudChannel;
            channelDeviceId = deviceId;
        } catch (Exception ex) {
            Logger.printException(() -> "onSessionConnect failure", ex);
        }
    }

    /**
     * Injection point: start of connecting a cloud channel.
     *
     * @return If the channel is ours. The original connect is then skipped, and {@link #connect} called.
     */
    public static boolean isOurChannel(Object cloudChannel) {
        return cloudChannel == channel;
    }

    /**
     * Injection point: connects our channel, instead of the original connect.
     */
    public static void connect(Object cloudChannel) {
        try {
            CloudChannelInterface ours = channel;
            if (cloudChannel != ours || ours == null) {
                return;
            }
            readConnectParams(ours);
            executor.execute(CastMenuLounge::onConnected);
        } catch (Exception ex) {
            Logger.printException(() -> "connect failure", ex);
        }
    }

    /**
     * Injection point: start of the state getter of a cloud channel.
     *
     * @return The state, or -1 for the original.
     */
    public static int state(Object cloudChannel) {
        return cloudChannel == channel ? CHANNEL_CONNECTED : -1;
    }

    /**
     * Injection point: start of sending a message on a cloud channel.
     *
     * @return A completed future if the channel is ours, or null for the original.
     */
    @Nullable
    public static ListenableFuture<Object> send(Object cloudChannel, Enum<?> method, @Nullable Map<String, String> params) {
        if (cloudChannel != channel) {
            return null;
        }
        try {
            final String name = method.name();
            Logger.printDebug(() -> "App to device: " + name + " " + params);
            executor.execute(() -> onCommand(name, params));
        } catch (Exception ex) {
            Logger.printException(() -> "send failure", ex);
        }
        return COMPLETED_FUTURE;
    }

    /**
     * Injection point: start of disconnecting a cloud channel.
     *
     * @return If the channel is ours, and the original disconnect is skipped.
     */
    public static boolean disconnect(Object cloudChannel) {
        if (cloudChannel != channel) {
            return false;
        }
        try {
            Logger.printDebug(() -> "App disconnected from " + channelDeviceId);
            channel = null;
            channelDeviceId = "";
            Context context = Utils.getContext();
            if (CastAdFreeService.isRunning()) {
                Utils.runOnMainThread(() -> CastAdFreeService.stop(context));
            }
        } catch (Exception ex) {
            Logger.printException(() -> "disconnect failure", ex);
        }
        return true;
    }

    // endregion

    /**
     * Called by the cast service when the cast ends, such as when it is stopped on the device.
     * The app then ends its session, as when a TV leaves the lounge.
     */
    static void onCastEnded() {
        if (channel != null) {
            executor.execute(() -> sendToApp("SCREEN_DISCONNECTED", new JSONObject()));
        }
    }

    /**
     * @return If the app has a session connected to a device of the Cast menu. The session stays connected when a
     *         video ends, so the app can cast the next one.
     */
    public static boolean isConnected() {
        return channel != null;
    }

    /**
     * Called by the cast service with each media status of the receiver.
     */
    static void onMediaStatus(JSONObject status) {
        if (channel == null) {
            return;
        }
        executor.execute(() -> {
            try {
                final String playerState = status.optString("playerState");
                // A video that is replaced by the next one also becomes idle, but it did not end.
                if (playerState.equals("IDLE") && !status.optString("idleReason").equals("FINISHED")) {
                    return;
                }
                final double currentTime = status.optDouble("currentTime");
                final int state = switch (playerState) {
                    case "PLAYING" -> STATE_PLAYING;
                    case "PAUSED" -> STATE_PAUSED;
                    case "IDLE" -> STATE_ENDED;
                    default -> STATE_BUFFERING;
                };
                JSONObject media = status.optJSONObject("media");
                if (media != null && media.has("duration") && media.optDouble("duration") != durationSeconds) {
                    // The app takes the duration of a new video from its now playing message.
                    durationSeconds = media.optDouble("duration");
                    sendToApp("NOW_PLAYING", nowPlaying(currentTime, state));
                }
                sendToApp("ON_STATE_CHANGED", stateParams(currentTime, state));
            } catch (JSONException ex) {
                Logger.printException(() -> "onMediaStatus failure", ex);
            }
        });
    }

    private static void startFinder() {
        if (finder != null || screenManager == null) {
            return;
        }
        Context context = Utils.getContext();
        // The app finds its own Cast devices without the permission, and asks for it only once a device is picked.
        // Asked once per run of the app, so a user who declines is not asked every time the menu opens.
        if (!CastDeviceFinder.hasLocalNetworkPermission(context)) {
            if (!permissionRequested) {
                permissionRequested = true;
                CastDeviceFinder.requestLocalNetworkPermission(() -> {
                    if (scanRequested) {
                        startFinder();
                    }
                });
            }
            return;
        }
        Logger.printDebug(() -> "Finding Cast devices");
        seenIds.clear();
        finderStartMillis = System.currentTimeMillis();
        finder = new CastDeviceFinder(context, CastMenuLounge::addScreen);
        finder.start();
    }

    /**
     * Stops finding Cast devices. After a scan long enough to find every device, the screens of the devices
     * that were not found are removed, such as of a device that is switched off.
     */
    private static void stopFinder() {
        if (finder == null) {
            return;
        }
        finder.stop();
        finder = null;
        if (System.currentTimeMillis() - finderStartMillis < FULL_SCAN_MILLISECONDS || screenManager == null) {
            return;
        }
        for (String id : devicesById.keySet()) {
            Object screen = screensById.get(id);
            if (!seenIds.contains(id) && !id.equals(channelDeviceId) && screen != null) {
                Logger.printDebug(() -> "Removing screen of a device not found: " + id);
                // The screen is no longer kept once its device is removed.
                devicesById.remove(id);
                screensById.remove(id);
                screenManager.patch_removeScreen(screen);
            }
        }
    }

    private static void addScreen(CastDeviceFinder.CastDevice castDevice) {
        try {
            String address = castDevice.host().getHostAddress();
            if (address == null || screenManager == null) {
                return;
            }
            String id = address.replace('.', '-').replace(':', '-');
            String deviceId = DEVICE_ID_PREFIX + id;
            seenIds.add(deviceId);
            if (devicesById.put(deviceId, castDevice) != null) {
                return;
            }
            Logger.printDebug(() -> "Adding screen: " + castDevice.name());
            screensById.put(deviceId, screenManager.patch_addScreen(castDevice.name() + SCREEN_NAME_SUFFIX,
                    SCREEN_ID_PREFIX + id, deviceId));
        } catch (Exception ex) {
            Logger.printException(() -> "addScreen failure", ex);
        }
    }

    /**
     * Takes the video and position the app connects with, from its setPlaylist or connect parameters.
     */
    private static void readConnectParams(CloudChannelInterface cloudChannel) throws JSONException {
        videoId = "";
        startSeconds = 0;
        durationSeconds = 0;
        @Nullable Map<String, String> params = cloudChannel.patch_getConnectParams();
        Logger.printDebug(() -> "Connect parameters: " + (params == null ? null : params.keySet()));
        if (params == null) {
            return;
        }
        String id = params.get("videoId");
        String time = params.get("currentTime");
        String states = params.get("setStatesParams");
        if (states != null) {
            JSONObject playlist = new JSONObject(states).optJSONObject("setPlaylistParams");
            if (playlist != null) {
                id = playlist.optString("videoId");
                time = playlist.optString("currentTime");
            }
        }
        if (id != null && !id.isEmpty()) {
            videoId = id;
            startSeconds = parseDouble(time);
            cpn = newCpn();
        }
    }

    private static void onConnected() {
        try {
            @Nullable CastDeviceFinder.CastDevice found = devicesById.get(channelDeviceId);
            if (found == null || (videoId.isEmpty() && !isPickedInCastMenu())) {
                // A session the app restores when it starts, of the last device it was connected to.
                Logger.printDebug(() -> "Nothing to play on " + channelDeviceId + ", disconnecting");
                sendToApp("SCREEN_DISCONNECTED", new JSONObject());
                return;
            }
            device = found;
            JSONArray devices = new JSONArray().put(new JSONObject()
                    .put("app", "lb-v4")
                    .put("name", found.name())
                    .put("id", channelDeviceId)
                    .put("type", "LOUNGE_SCREEN")
                    .put("clientName", "tvhtml5")
                    .put("capabilities", ""));
            // No queue id: with one, the app edits a queue on YouTube's servers instead of sending setPlaylist.
            sendToApp("LOUNGE_STATUS", new JSONObject().put("devices", devices.toString()));
            sendToApp("ON_VOLUME_CHANGED", new JSONObject().put("volume", "100").put("muted", "false"));
            if (videoId.isEmpty()) {
                // Picked without a video, such as on the home feed. The app sends the video that is played next.
                Logger.printDebug(() -> "Connected to " + found.name() + " without a video");
                sendToApp("NOW_PLAYING", new JSONObject());
                return;
            }
            sendToApp("NOW_PLAYING", nowPlaying(startSeconds, STATE_BUFFERING));
            startCast(found, videoId, startSeconds);
        } catch (Exception ex) {
            Logger.printException(() -> "onConnected failure", ex);
        }
    }

    /**
     * @return If the lounge device id is of a screen this patch added.
     */
    private static boolean isOurDeviceId(@Nullable String loungeDeviceId) {
        return loungeDeviceId != null && loungeDeviceId.startsWith(DEVICE_ID_PREFIX);
    }

    /**
     * @return If the device of the session was just picked in the Cast menu, and the session was not restored.
     */
    private static boolean isPickedInCastMenu() {
        return scanRequested || System.currentTimeMillis() - castMenuClosedMillis < CAST_MENU_PICK_MILLISECONDS;
    }

    private static void onCommand(String name, @Nullable Map<String, String> params) {
        try {
            switch (name) {
                case "SET_PLAYLIST", "SET_VIDEO" -> {
                    String id = params == null ? null : params.get("videoId");
                    final double time = parseDouble(params == null ? null : params.get("currentTime"));
                    CastDeviceFinder.CastDevice castDevice = device;
                    if (id != null && !id.isEmpty() && !id.equals(videoId) && castDevice != null) {
                        videoId = id;
                        startSeconds = time;
                        durationSeconds = 0;
                        cpn = newCpn();
                        startCast(castDevice, id, time);
                    }
                    sendToApp("NOW_PLAYING", nowPlaying(time, STATE_BUFFERING));
                }
                case "PLAY" -> CastAdFreeService.command(CastConnection::play);
                case "PAUSE" -> CastAdFreeService.command(CastConnection::pause);
                case "SEEK_TO" -> {
                    final double time = parseDouble(params == null ? null : params.get("newTime"));
                    CastAdFreeService.command(connection -> connection.seek(time));
                }
                case "SET_VOLUME" -> {
                    String volume = params == null ? null : params.get("volume");
                    final double level = parseDouble(volume) / 100;
                    CastAdFreeService.command(connection -> connection.setVolume(level));
                    sendToApp("ON_VOLUME_CHANGED", new JSONObject()
                            .put("volume", volume == null ? "100" : volume).put("muted", "false"));
                }
                case "STOP_VIDEO", "STOP" -> {
                    Context context = Utils.getContext();
                    Utils.runOnMainThread(() -> CastAdFreeService.stop(context));
                }
                case "SET_SUBTITLES_TRACK" -> {
                    // Without a track, the app turned captions off.
                    @Nullable String vssId = params == null ? null : params.get("vss_id");
                    CastAdFreeService.setCaptions(vssId == null ? "" : vssId);
                    sendToApp("ON_SUBTITLES_TRACK_CHANGED", params == null ? new JSONObject() : new JSONObject(params));
                }
                case "GET_NOW_PLAYING" -> sendToApp("NOW_PLAYING", nowPlaying(startSeconds, STATE_BUFFERING));
                default -> Logger.printDebug(() -> "Not handled: " + name);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onCommand failure: " + name, ex);
        }
    }

    private static void startCast(CastDeviceFinder.CastDevice castDevice, String id, double start) {
        Context context = Utils.getContext();
        Utils.runOnMainThread(() -> CastAdFreeService.start(context, castDevice, id, start));
    }

    private static JSONObject nowPlaying(double time, int state) throws JSONException {
        return stateParams(time, state).put("videoId", videoId).put("listId", "");
    }

    private static JSONObject stateParams(double time, int state) throws JSONException {
        return new JSONObject()
                .put("currentTime", format(time))
                .put("duration", format(durationSeconds))
                .put("loadedTime", format(time))
                .put("seekableStartTime", "0")
                .put("seekableEndTime", format(durationSeconds))
                .put("state", String.valueOf(state))
                .put("cpn", cpn);
    }

    private static void sendToApp(String method, JSONObject data) {
        CloudChannelInterface cloudChannel = channel;
        if (cloudChannel == null) {
            return;
        }
        try {
            Logger.printDebug(() -> "Device to app: " + method + " " + data);
            cloudChannel.patch_receiveMessage(method, data);
        } catch (Exception ex) {
            Logger.printException(() -> "sendToApp failure: " + method, ex);
        }
    }

    private static String newCpn() {
        final String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        StringBuilder nonce = new StringBuilder(CPN_LENGTH);
        for (int i = 0; i < CPN_LENGTH; i++) {
            nonce.append(chars.charAt(random.nextInt(chars.length())));
        }
        return nonce.toString();
    }

    private static double parseDouble(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            Logger.printInfo(() -> "Not a number: " + value, ex);
            return 0;
        }
    }

    private static String format(double seconds) {
        return String.format(Locale.US, "%.3f", seconds);
    }
}
