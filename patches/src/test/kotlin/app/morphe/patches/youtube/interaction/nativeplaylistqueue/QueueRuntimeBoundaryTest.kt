/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.interaction.nativeplaylistqueue

import com.google.protobuf.GeneratedMessageLite
import java.io.File
import java.net.URLClassLoader
import javax.tools.ToolProvider
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/** Tests compiled queue ownership and feedback timing across external Android boundaries. */
class QueueRuntimeBoundaryTest {
    @Test
    fun `native queue is active only for the native queue type`() = withFixture("queueTypeSelection")

    @Test
    fun `handoff waits for setting cache and rejects late responses without restoring old session`() =
        withFixture("acceptedHandoff")

    @Test
    fun `failed detach retains ownership for retry but cancels pending work`() = withFixture("failedDetach")

    @Test
    fun `handoff never clears a foreign backend`() = withFixture("foreignBackend")

    @Test
    fun `other queue types cancel a pending first video start`() = withFixture("pendingStart")

    @Test
    fun `local Undo follows normal confirmation timing and replacement`() = withFixture("normalUndoTiming")

    @Test
    fun `local Undo follows accessible confirmation timing`() = withFixture("accessibleUndoTiming")

    @Test
    fun `closing an owned player restores the queue on a new backend at its saved position`() =
        withFixture("closedPlayerRestore")

    @Test
    fun `closed restore releases its inline block on cancellation and timeout without releasing newer opens`() =
        withFixture("closedRestoreInlineLifetime")

    @Test
    fun `first video startup blocks an active feed preview until its player is accepted`() = withFixture("videoPreviewStart")

    @Test
    fun `playlist and Mix startup block feed previews through Next and Last`() = withFixture("playlistPreviewStart")

    @Test
    fun `fresh startup releases feed protection on failure timeout and supersession`() = withFixture("previewStartLifetime")

    @Test
    fun `new playback and provider handoff invalidate closed player Undo`() = withFixture("closedPlayerSuperseded")

    @Test
    fun `foreign playback and unavailable position never create close Undo`() = withFixture("closedPlayerGuards")

    @Test
    fun `single row removal is silent and still protects the playing row`() = withFixture("singleRemovalSilent")

    @Test
    fun `menu Clear Undo reattaches without reopening or seeking playback`() = withFixture("menuClearUndo")

    @Test
    fun `Reload offers Clear Undo on the reloaded player without reopening it again`() = withFixture("reloadQueueUndo")

    @Test
    fun `Reload feedback expires and cannot replace newer playback or queue ownership`() = withFixture("reloadQueueLifetime")

    @Test
    fun `Reload leaves other providers and foreign or unavailable playback unchanged`() = withFixture("reloadQueueGuards")

    @Test
    fun `individual music taps remove only automatic Mix navigation from an owned queue`() = withFixture("musicVideoTap")

    @Test
    fun `music tap normalization preserves deliberate playlists and unavailable or foreign queue playback`() =
        withFixture("musicVideoTapGuards")

    @Test
    fun `stock queue actions route to Native without opening the account queue dialog`() = withFixture("stockQueueRouting")

    @Test
    fun `Morphe and Local stock actions keep their existing destinations`() = withFixture("otherQueueRouting")

    @Test
    fun `single video Last rejects stale actions after its queue is cleared`() = withFixture("staleLastAction")

    @Test
    fun `stock Next preserves the playing playlist without reopening current playback`() =
        withFixture("playlistNextHandoff")

    @Test
    fun `stock Next preserves the accepted Mix batch and minimized presentation`() = withFixture("playlistMixHandoff")

    @Test
    fun `creating a queue from current video inserts it once but later additions allow duplicates`() =
        withFixture("firstQueueCurrentVideo")

    @Test
    fun `failed in-place attachment preserves the old playlist and playback`() = withFixture("playlistHandoffFailed")

    @Test
    fun `converted playlist rejects late source responses until deliberate new playlist navigation`() =
        withFixture("convertedPlaylistLifetime")

    @Test
    fun `converted playlist supports Clear Undo without restoring old playlist navigation`() =
        withFixture("convertedPlaylistUndo")

    @Test
    fun `queue header width follows committed ownership and restores on ordinary playlist reuse`() =
        withFixture("queueTitleOwnership")

    @Test
    fun `ordinary playlist row menus supply their own selected ID in Native mode`() = withFixture("playlistRowContext")

    @Test
    fun `Mix choice preserves Watch and appends fetched entries without reopening playback`() = withFixture("mixChoice")

    @Test
    fun `Mix choice ignores music and foreign queues and rejects stale choices and completions`() = withFixture("mixChoiceGuards")

    @Test
    fun `idle playlist startup loads one window and only its native list requests another`() = withFixture("idlePlaylistPaging")

    @Test
    fun `default playback windows extend around the playing source position without scroll fetching`() =
        withFixture("playbackWindowGrowth", fetchOnScroll = false)

    @Test
    fun `playback window completion follows the latest confirmed selection`() = withFixture("playbackWindowSelection", fetchOnScroll = false)

    @Test
    fun `enabling scroll loading and clearing reject pending playback windows`() = withFixture("playbackWindowLifetime", fetchOnScroll = false)

    @Test
    fun `boundary navigation takes priority over playback window loading`() = withFixture("playbackWindowBoundary", fetchOnScroll = false)

    @Test
    fun `playlist requests cancel on leave and reject an account change before delivery`() = withFixture("playlistRequestLifetime")

    @Test
    fun `unloaded playback boundary replays the original autoplay request`() = withFixture("playlistBoundaryReplay")

    @Test
    fun `whole queue operations finish the source and leave the native loading frame`() = withFixture("playlistWholeOperations")

    @Test
    fun `whole queue Save transfers every fetched row to the native picker`() = withFixture("playlistSaveOperation")

    @Test
    fun `incoming Next retains primary paging and inserts after playback advances`() = withFixture("activePlaylistNext")

    @Test
    fun `incoming Last transfers pagination and resumes the original boundary request`() = withFixture("activePlaylistLast")

    @Test
    fun `playlist additions reject stale account and cleared queue completions`() = withFixture("activePlaylistLifetime")

    @Test
    fun `failed playlist addition preserves the current queue and restores its content`() = withFixture("activePlaylistFailure")

    @Test
    fun `playing playlist conversion reuses whole playlist Next insertion`() = withFixture("playingPlaylistNext")

    @Test
    fun `playing playlist conversion reuses whole playlist Last handoff`() = withFixture("playingPlaylistLast")

    @Test
    fun `conversion recovers the prefix and selects the exact duplicate occurrence`() = withFixture("playingPlaylistPrefix")

    @Test
    fun `conversion rejects deliberate new watches replaced models and changed accounts`() = withFixture("playingPlaylistLifetime")

    @Test
    fun `failed prefix loading keeps ordinary playback and its playlist intact`() = withFixture("playingPlaylistFailure")

    @Test
    fun `standalone playback accepts whole playlists without reopening the player`() = withFixture("standalonePlaylist")

    @Test
    fun `single video Last preserves every row of a playing playlist`() = withFixture("playingVideoLast")

    @Test
    fun `metadata uses the app locale and rejects a result after it changes`() = withFixture("metadataLocale")

    @Test
    fun `rebuilt queue rows retain native theme palettes and text captures do not erase them`() =
        withFixture("nativeRowPalettes")

    @Test
    fun `merged playlists share one native footer without changing playable positions`() =
        withFixture("hiddenVideosFooter")

    // NativePlaylistQueuePatch depends on Android settings, scheduling and UI classes.
    // Isolate those boundaries while executing its actual compiled coordinator.
    private fun withFixture(scenario: String, fetchOnScroll: Boolean = true) {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "extensions/youtube/src/main/proto").isDirectory }
        val classes = File(root,
            "extensions/youtube/build/intermediates/javac/release/compileReleaseJavaWithJavac/classes")
        val protobuf = File(GeneratedMessageLite::class.java.protectionDomain.codeSource.location.toURI())
        val output = createTempDirectory("playlist-queue-runtime").toFile()
        // Only external settings/UI/scheduling boundaries are replaced. Queue state,
        // preference callback, cancellation, release, feedback and timeout guards are real.
        val stubs = mapOf(
            "SharedPreferences" to """
                package android.content;
                public interface SharedPreferences {
                    interface OnSharedPreferenceChangeListener {
                        void onSharedPreferenceChanged(SharedPreferences prefs, String key);
                    }
                }
            """,
            "Bundle" to "package android.os; public class Bundle {}",
            "AccessibilityManager" to """
                package android.view.accessibility;
                public class AccessibilityManager {
                    public boolean enabled, touchExploration;
                    public boolean isEnabled() { return enabled; }
                    public boolean isTouchExplorationEnabled() { return touchExploration; }
                }
            """,
            "Context" to """
                package android.content;
                public class Context {
                    public static final String ACCESSIBILITY_SERVICE = "accessibility";
                    public final android.view.accessibility.AccessibilityManager accessibility =
                        new android.view.accessibility.AccessibilityManager();
                    public Object getSystemService(String service) { return accessibility; }
                    public android.graphics.drawable.Drawable getDrawable(int id) { return null; }
                }
            """,
            "Activity" to """
                package android.app;
                public class Activity extends android.content.Context {
                    public boolean isFinishing() { return false; }
                    public boolean isDestroyed() { return false; }
                }
            """,
            "Drawable" to "package android.graphics.drawable; public class Drawable {}",
            "View" to """
                package android.view;
                public class View {
                    public static final int GONE = 8;
                    public interface OnClickListener { void onClick(View view); }
                    public int getVisibility() { return 0; }
                    public void setVisibility(int value) {}
                    public View findViewById(int id) { return null; }
                    public void setOnClickListener(OnClickListener listener) {}
                }
            """,
            "ViewGroup" to """
                package android.view;
                public class ViewGroup extends View {
                    public static class LayoutParams {
                        public static final int WRAP_CONTENT = -2;
                        public int width;
                        public LayoutParams(int width) { this.width = width; }
                    }
                    public int getChildCount() { return 0; }
                    public View getChildAt(int index) { throw new AssertionError("Unexpected UI binding"); }
                }
            """,
            "TextPaint" to """
                package android.text;
                public class TextPaint { public float desiredWidth = 83.25f; }
            """,
            "Layout" to """
                package android.text;
                public class Layout {
                    public static float getDesiredWidth(CharSequence text, TextPaint paint) { return paint.desiredWidth; }
                }
            """,
            "TextView" to """
                package android.widget;
                public class TextView extends android.view.View {
                    public final android.view.ViewGroup.LayoutParams params = new android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                    public int layoutRequests;
                    public final android.text.TextPaint paint = new android.text.TextPaint();
                    public CharSequence getText() { return "Queue"; }
                    public android.text.TextPaint getPaint() { return paint; }
                    public int getCompoundPaddingLeft() { return 2; }
                    public int getCompoundPaddingRight() { return 3; }
                    public android.view.ViewGroup.LayoutParams getLayoutParams() { return params; }
                    public void setLayoutParams(android.view.ViewGroup.LayoutParams value) {
                        if (value != params) throw new AssertionError("Keep the native layout parameters");
                        layoutRequests++;
                    }
                }
            """,
            "Pair" to """
                package android.util;
                public class Pair<F, S> {
                    public final F first; public final S second;
                    public Pair(F first, S second) { this.first = first; this.second = second; }
                }
            """,
            "ResourceType" to "package app.morphe.extension.shared; public enum ResourceType { ID, DRAWABLE }",
            "ResourceUtils" to """
                package app.morphe.extension.shared;
                public class ResourceUtils {
                    public static int getIdentifier(ResourceType type, String name) { return 0; }
                }
            """,
            "FeedVideoCardsPatch" to """
                package app.morphe.extension.youtube.patches.utils;
                public class FeedVideoCardsPatch {
                    // External mounted UI boundary, observed separately on the phone.
                    public static boolean individual = true;
                    public static boolean isIndividualVideoTap(android.view.View view) { return individual; }
                    public static boolean isPlaylistTap(android.view.View view) { return !individual; }
                }
            """,
            "AuthUtils" to """
                package app.morphe.extension.shared.innertube.utils;
                public class AuthUtils {
                    public static String account = "test";
                    public static java.util.Map<String, String> getRequestHeader() { return java.util.Map.of("account", account); }
                }
            """,
            "Requester" to """
                package app.morphe.extension.shared.requests;
                public class Requester {
                    public static java.util.Locale locale = java.util.Locale.getDefault();
                    public static java.util.Locale getAppLocale() { return locale; }
                }
            """,
            "MixPlaylistRequest" to """
                package app.morphe.extension.youtube.patches.queue;
                public class MixPlaylistRequest {
                    public static java.util.List<VideoMetadata> result;
                    static java.util.List<VideoMetadata> fetch(String playlist, String video, java.util.Map<String, String> headers) {
                        if (app.morphe.extension.shared.Utils.mainThread) throw new AssertionError("Network on UI thread");
                        return result;
                    }
                }
            """,
            "PlaylistMetadataRequest" to """
                package app.morphe.extension.youtube.patches.queue;
                public class PlaylistMetadataRequest {
                    static java.util.Locale requestedLocale;
                    static VideoMetadata result;
                    static VideoMetadata fetch(String video, String title, java.util.Locale locale) {
                        app.morphe.extension.shared.Utils.verifyOffMainThread();
                        requestedLocale = locale;
                        return result;
                    }
                }
            """,
            "FlyoutUtils" to """
                package app.morphe.extension.youtube.patches.utils;
                import android.util.Pair;
                import java.util.*;
                public class FlyoutUtils {
                    public static String video = "ZYXWVUTSRQP", button = "QUEUE_PLAY_NEXT";
                    public static final List<Pair<String, Integer>> stock = new ArrayList<>();
                    public static List<Pair<String, Integer>> getVisibleFlyoutButtons() { return stock; }
                    public interface FlyoutMenuVideoIdInterface { String patch_getVideoId(); }
                    public static int contexts;
                    public static void extractFlyoutIdFromMap(Map<?, ?> context) {
                        contexts++;
                        video = ((FlyoutMenuVideoIdInterface) context.get(
                            "com.google.android.libraries.youtube.innertube.endpoint.tag")).patch_getVideoId();
                    }
                    public static String getCurrentButtonName() { return button; }
                    public static String getFlyoutVideoId() { return video; }
                    public static String getFlyoutPlaylistId() { return ""; }
                    public static String nativePlaylist = "";
                    public static boolean nativePlaylistMenu;
                    public static String getNativeQueuePlaylistId() { return nativePlaylist; }
                    public static boolean isNativeQueuePlaylistMenu() { return nativePlaylistMenu; }
                    public static void setNativeQueuePlaylistId(String id) { nativePlaylist = id; }
                    public static String getFlyoutChannelName() { return "Channel"; }
                    public static void resetCurrentButtonIndex() {}
                    public static void dismissFlyout() {}
                    public static int addFlyoutButton(Object panel, android.graphics.drawable.Drawable icon,
                            String text, android.view.View.OnClickListener action, int index) {
                        return index + 1;
                    }
                }
            """,
            "PlaylistPatch" to """
                package app.morphe.extension.youtube.patches.utils;
                public class PlaylistPatch {
                    public enum QueueManager { OPEN_QUEUE; public final int drawableId = 0; }
                    public static int dialogs;
                    public static void prepareDialogBuilder(android.content.Context context, String video) { dialogs++; }
                }
            """,
            "LocalQueueSheet" to """
                package app.morphe.extension.youtube.patches.utils;
                public class LocalQueueSheet { public static void show(android.app.Activity activity) {} }
            """,
            "ChannelIdRequest" to """
                package app.morphe.extension.youtube.patches.utils.requests;
                public class ChannelIdRequest { public static String getVideoTitleIfAvailable(String video) { return video; } }
            """,
            "PlayerType" to """
                package app.morphe.extension.youtube.shared;
                public enum PlayerType {
                    NONE, HIDDEN, INLINE_MINIMAL, WATCH_WHILE_MINIMIZED, WATCH_WHILE_MAXIMIZED,
                    WATCH_WHILE_SLIDING_MINIMIZED_DISMISSED, WATCH_WHILE_SLIDING_FULLSCREEN_DISMISSED;
                    public static PlayerType current = NONE;
                    public static PlayerType getCurrent() { return current; }
                    public boolean isNoneOrHidden() { return this == NONE || this == HIDDEN; }
                }
            """,
            "ShortsPlayerState" to """
                package app.morphe.extension.youtube.shared;
                public class ShortsPlayerState {
                    public static boolean open;
                    public static boolean isOpen() { return open; }
                }
            """,
            "VideoInformation" to """
                package app.morphe.extension.youtube.patches;
                public class VideoInformation {
                    public static String id = "abcdefghijk", playlist = "";
                    public static long time = 75432;
                    public static String getVideoId() { return id; }
                    public static String getPlaylistId() { return playlist; }
                    public static String getVideoTitle() { return "Current video"; }
                    public static String getChannelName() { return "Channel"; }
                    public static long getVideoTime() { return time; }
                }
            """,
            "LoadVideoPatch" to """
                package app.morphe.extension.youtube.patches;
                public class LoadVideoPatch {
                    public static String url;
                    public interface PlayerInterface { void patch_dismissPlayer(); }
                    public static int opens, blocks, releases, activeBlock, dismissals;
                    public static boolean previewPlaying, failOpen;
                    public static PlayerInterface getPlayerInterface() { return () -> dismissals++; }
                    public static int blockInlinePlaybackUntilPlayerOpens() {
                        return activeBlock = ++blocks;
                    }
                    public static void releaseInlinePlaybackBlock(int token) {
                        if (token != 0 && token == activeBlock) { activeBlock = 0; releases++; }
                    }
                    public static void openVideoIntent(String value, boolean dismiss) {
                        if (dismiss) {
                            throw new AssertionError("Queue startup must not dismiss a replacement player");
                        }
                        if ((previewPlaying || value.contains("&t=")) && activeBlock == 0) {
                            throw new AssertionError("Block the active feed preview before dispatching a queue watch");
                        }
                        if (failOpen) {
                            throw new IllegalStateException("Watch dispatch failed");
                        }
                        opens++; url = value;
                    }
                }
            """,
            "StringRef" to """
                package app.morphe.extension.shared;
                public class StringRef { public static String str(String key) { return key; } }
            """,
            "Base64" to """
                package android.util;
                public class Base64 {
                    public static final int NO_WRAP = 2;
                    public static String encodeToString(byte[] bytes, int flags) {
                        return java.util.Base64.getEncoder().encodeToString(bytes);
                    }
                }
            """,
            "Uri" to """
                package android.net;
                public class Uri {
                    public static String encode(String value) {
                        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
                    }
                }
            """,
            "RestoreOriginalTitlesPatch" to """
                package app.morphe.extension.youtube.patches.originaltitles;
                public class RestoreOriginalTitlesPatch {
                    public static String getTitle(String id, String title) { return title; }
                }
            """,
            "ChannelIdRequest" to """
                package app.morphe.extension.youtube.patches.utils.requests;
                public class ChannelIdRequest {
                    public static String getVideoTitleIfAvailable(String id) { return ""; }
                }
            """,
            "ByteTrieSearch" to """
                package app.morphe.extension.shared;
                public class ByteTrieSearch {
                    public ByteTrieSearch(byte[]... values) {}
                    public static byte[][] convertStringsToBytes(String... values) { return new byte[0][]; }
                }
            """,
            "Utils" to """
                package app.morphe.extension.shared;
                public class Utils {
                    public static boolean mainThread = true;
                    public static boolean isCurrentlyOnMainThread() { return mainThread; }
                    private static final java.util.ArrayDeque<Runnable> pending = new java.util.ArrayDeque<>();
                    public static <K,V> java.util.Map<K,V> createSizeRestrictedMap(int size) {
                        return new java.util.LinkedHashMap<>();
                    }
                    private record Delayed(Runnable action, long when) {}
                    private static final java.util.List<Delayed> delayed = new java.util.ArrayList<>();
                    private static long now;
                    private static final android.content.Context context = new android.content.Context();
                    public static android.content.Context getContext() { return context; }
                    public static void runOnMainThreadDelayed(Runnable action, long delay) {
                        delayed.add(new Delayed(action, now + delay));
                    }
                    public static void advanceBy(long milliseconds) {
                        now += milliseconds;
                        for (var item : new java.util.ArrayList<>(delayed)) {
                            if (item.when() <= now) { delayed.remove(item); item.action().run(); }
                        }
                    }
                    public static void runOnMainThread(Runnable action) { pending.add(action); }
                    public static void runOnMainThreadNowOrLater(Runnable action) { action.run(); }
                    private static final java.util.ArrayDeque<Runnable> background = new java.util.ArrayDeque<>();
                    public static void runOnBackgroundThread(Runnable action) { background.add(action); }
                    public static <T> java.util.concurrent.Future<T> submitOnBackgroundThread(java.util.concurrent.Callable<T> action) {
                        var task = new java.util.concurrent.FutureTask<T>(action);
                        background.add(task);
                        return task;
                    }
                    public static void verifyOffMainThread() {
                        if (mainThread) throw new AssertionError("Network on UI thread");
                    }
                    public static void drainBackground() {
                        mainThread = false;
                        try { while (!background.isEmpty()) background.remove().run(); }
                        finally { mainThread = true; }
                    }
                    public static android.app.Activity getActivity() { return new android.app.Activity(); }
                    public static int toasts;
                    public static boolean failToast;
                    public static void showSnackbarShort(String text) {
                        if (failToast) throw new IllegalStateException("Unavailable feedback surface");
                        toasts++;
                    }
                    public static void drain() { while (!pending.isEmpty()) pending.remove().run(); }
                }
            """,
            "Logger" to """
                package app.morphe.extension.shared;
                public class Logger {
                    public interface LogMessage { String buildMessageString(); }
                    public static int errors;
                    public static void printDebug(LogMessage message) { message.buildMessageString(); }
                    public static void printException(LogMessage message) { message.buildMessageString(); errors++; }
                    public static void printException(LogMessage message, Throwable e) { printException(message); }
                }
            """,
            "EnumSetting" to """
                package app.morphe.extension.shared.settings;
                public class EnumSetting<T extends Enum<T>> {
                    public String key = "morphe_queue_type";
                    public T value;
                    public EnumSetting(T value) { this.value = value; }
                    public T get() { return value; }
                }
            """,
            "Settings" to """
                package app.morphe.extension.youtube.settings;
                import app.morphe.extension.youtube.patches.AddToQueuePatch.QueueType;
                import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer;
                import app.morphe.extension.youtube.innertube.IconOuterClass;
                public class Settings {
                    public static final app.morphe.extension.shared.settings.EnumSetting<QueueType> QUEUE_TYPE =
                        new app.morphe.extension.shared.settings.EnumSetting<>(QueueType.NATIVE);
                    public static final app.morphe.extension.shared.settings.BooleanSetting NATIVE_QUEUE_FETCH_ON_SCROLL =
                        new app.morphe.extension.shared.settings.BooleanSetting();
                }
            """,
            "BooleanSetting" to """
                package app.morphe.extension.shared.settings;
                public class BooleanSetting {
                    public final String key = "morphe_native_queue_fetch_on_scroll";
                    public boolean value;
                    public Boolean get() { return value; }
                }
            """,
            "LocalQueuePatch" to """
                package app.morphe.extension.youtube.patches;
                public class LocalQueuePatch {
                    public static final java.util.List<String> savedItems = java.util.List.of("saved-video");
                    public static int additions;
                    public static boolean next;
                    public static boolean isEnabled() { return app.morphe.extension.youtube.settings.Settings.QUEUE_TYPE.get() == AddToQueuePatch.QueueType.LOCAL; }
                    public static void add(String video, boolean playNext) { additions++; next = playNext; }
                    public static int size() { return 0; }
                }
            """,
            "OpenVideosFullscreenHookPatch" to """
                package app.morphe.extension.youtube.patches;
                public class OpenVideosFullscreenHookPatch {
                    public static String minimized = "pending";
                    public static void keepVideoMinimized(String id) { minimized = id; }
                }
            """,
            "QueueFullscreenActions" to """
                package app.morphe.extension.youtube.patches.queue;
                public class QueueFullscreenActions {
                    public static int refreshes;
                    static void refresh() { refreshes++; }
                }
            """
        ).map { (name, source) -> File(output, "$name.java").apply { writeText(source.trimIndent()) } }
        val fixture = File(output, "RuntimeFixture.java").apply {
            writeText("""
                package app.morphe.extension.youtube.patches.queue;
                import android.content.SharedPreferences;
                import app.morphe.extension.shared.*;
                import app.morphe.extension.youtube.patches.*;
                import app.morphe.extension.youtube.patches.AddToQueuePatch.QueueType;
                import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer;
                import app.morphe.extension.youtube.innertube.IconOuterClass;
                import app.morphe.extension.youtube.settings.Settings;
                import java.lang.ref.WeakReference;
                import java.lang.reflect.*;
                import java.util.*;
                public class RuntimeFixture {
                    static Class<?> owner = NativePlaylistQueuePatch.class;
                    static Field field(String name) throws Exception {
                        Field value = owner.getDeclaredField(name); value.setAccessible(true); return value;
                    }
                    static Object call(String name) throws Exception {
                        Method method = owner.getDeclaredMethod(name); method.setAccessible(true); return method.invoke(null);
                    }
                    static void require(boolean value, String message) {
                        if (!value) throw new AssertionError(message);
                    }
                    public static void setFetchOnScroll(boolean enabled) {
                        Settings.NATIVE_QUEUE_FETCH_ON_SCROLL.value = enabled;
                    }
                    static int clears;
                    static NativePlaylistQueuePatch.NativePlayback backend(boolean local, boolean clearSucceeds) {
                        return (NativePlaylistQueuePatch.NativePlayback) Proxy.newProxyInstance(owner.getClassLoader(),
                            new Class<?>[]{NativePlaylistQueuePatch.NativePlayback.class}, (proxy, method, args) -> {
                                switch (method.getName()) {
                                    case "patch_isLocalQueue": return local;
                                    case "patch_clearQueue": clears++; return clearSucceeds;
                                    default: throw new AssertionError("Unexpected playback operation: " + method.getName());
                                }
                            });
                    }
                    static PlaylistQueue install(NativePlaylistQueuePatch.NativePlayback playback) throws Exception {
                        PlaylistQueue queue = new PlaylistQueue("abcdefghijk", "Playing").playNext("ZYXWVUTSRQP");
                        field("queue").set(null, queue);
                        field("nativePlaybackRef").set(null, new WeakReference<>(playback));
                        field("publishedModel").set(null, new Object());
                        field("pendingQueueStart").set(null, new QueueStart(queue, ""));
                        Class<?> undo = Class.forName(owner.getName() + "${'$'}PendingUndo");
                        Constructor<?> create = undo.getDeclaredConstructors()[0]; create.setAccessible(true);
                        field("pendingUndo").set(null, create.newInstance("token", queue, new WeakReference<>(playback), null));
                        clears = 0; Logger.errors = 0; QueueFullscreenActions.refreshes = 0;
                        return queue;
                    }
                    public static void nativeRowPalettes() throws Exception {
                        var light = PlaylistQueueRenderer.ColorPalette.newBuilder().setSection2Color(0xffeeddcc).setSection4Color(0xffddccbb)
                            .setPrimaryTitleColor(0xff111111).setSecondaryTitleColor(0xff333333).build();
                        var dark = PlaylistQueueRenderer.ColorPalette.newBuilder().setSection2Color(0xff332211).setSection4Color(0xff221100)
                            .setPrimaryTitleColor(0xffffeeee).setSecondaryTitleColor(0xffccaaaa).build();
                        var metadata = new VideoMetadata("abcdefghijk", "abcdefghijk", "", "", "1:00",
                            false, false, light, dark);
                        var cache = (Map<String, VideoMetadata>) field("metadataCache").get(null);
                        cache.put(metadata.videoId(), metadata);
                        var remember = owner.getDeclaredMethod("rememberChannel", String.class, String.class, String.class);
                        remember.setAccessible(true);
                        remember.invoke(null, metadata.videoId(), "Title", "Channel");
                        var candidate = new PlaylistQueue(metadata.videoId(), "Title").playLast("ZZZZZZZZZZZ");
                        var renderer = owner.getDeclaredMethod("renderer", PlaylistQueue.class);
                        renderer.setAccessible(true);
                        var panel = PlaylistQueueRenderer.Panel.parseFrom((byte[]) renderer.invoke(null, candidate));
                        require(panel.getItems(0).getVideo().getSelected(), "Current row must remain selected");
                        require(panel.getItems(0).getVideo().getLightColorPalette().equals(light)
                            && panel.getItems(0).getVideo().getDarkColorPalette().equals(dark),
                            "Rebuilt row must carry the native background and text colors for both themes");
                        require(!panel.getItems(1).getVideo().hasLightColorPalette()
                            && !panel.getItems(1).getVideo().hasDarkColorPalette(),
                            "A row without native colors must retain the native theme fallback");
                    }
                    public static void hiddenVideosFooter() throws Exception {
                        var message = PlaylistQueueRenderer.Item.newBuilder().setMessageRenderer(
                            com.google.protobuf.ByteString.copyFrom(java.util.Base64.getDecoder().decode(
                                "MinqrfSCAyMKIQofCh1VbmF2YWlsYWJsZSB2aWRlb3MgYXJlIGhpZGRlbkICCAI="))).build();
                        var nativePanel = ordinaryWindow("WL").toBuilder().setVideoCount(4).addItems(message).build();
                        var page = PlaylistPage.readPanel(nativePanel, "WL");
                        var queue = PlaylistQueue.fromPage(page, null).select(2).playLast("ZZZZZZZZZZZ")
                            .addPlaylist(PlaylistQueue.fromPage(page, null), true)
                            .addPlaylist(new PlaylistQueue("YYYYYYYYYYY", "No hidden videos"), true);
                        var renderer = owner.getDeclaredMethod("renderer", PlaylistQueue.class);
                        renderer.setAccessible(true);
                        var panel = PlaylistQueueRenderer.Panel.parseFrom((byte[]) renderer.invoke(null, queue));
                        require(panel.getVideoCount() == 10 && queue.entries().size() == 8, "Hidden total must remain native");
                        require(panel.getSelectedIndex() == 2 && panel.getLocalSelectedIndex() == 2,
                            "Footer must not shift the playing position");
                        require(panel.getItemsCount() == 9 && panel.getItems(7).getVideo().getVideoId().equals("YYYYYYYYYYY"),
                            "Appended videos must precede the message");
                        require(panel.getItems(8).equals(message) && !panel.getItems(8).hasVideo()
                            && panel.getItemsList().stream().filter(item -> item.hasMessageRenderer()).count() == 1,
                            "Native footer payload must survive rendering without becoming playable");
                    }
                    public static void metadataLocale() throws Exception {
                        var previous = Locale.getDefault();
                        var locale = Locale.forLanguageTag("en-IN");
                        try {
                            Locale.setDefault(Locale.US);
                            app.morphe.extension.shared.requests.Requester.locale = locale;
                            field("queue").set(null, new PlaylistQueue("abcdefghijk", "Playing"));
                            PlaylistMetadataRequest.result = new VideoMetadata("abcdefghijk", "Playing", "Channel",
                                "108 crore", "1:00", true);
                            call("completeMetadata"); Utils.drainBackground();
                            require(locale.equals(PlaylistMetadataRequest.requestedLocale),
                                "Metadata request must use the app locale rather than the system locale");
                            app.morphe.extension.shared.requests.Requester.locale = Locale.US;
                            Utils.drain();
                            var cache = (Map<?, ?>) field("metadataCache").get(null);
                            require(!cache.containsKey("abcdefghijk"), "Reject results after an app-language change");
                            app.morphe.extension.shared.requests.Requester.locale = locale;
                            call("completeMetadata"); Utils.drainBackground(); Utils.drain();
                            require(cache.get("abcdefghijk") instanceof VideoMetadata metadata
                                    && metadata.videoInfo().equals("108 crore"),
                                "Keep YouTube's regional view text unchanged");
                        } finally {
                            Locale.setDefault(previous);
                        }
                    }
                    public static void stockQueueRouting() throws Exception {
                        int[] originals = {0};
                        Runnable original = () -> originals[0]++;
                        var action = AddToQueuePatch.replaceButtonRunnable(original);
                        require(action != original, "Native must replace the stock Next callback");
                        app.morphe.extension.youtube.patches.utils.FlyoutUtils.video = "LMNOPQRSTUV";
                        action.run();
                        require(LoadVideoPatch.opens == 1 && LoadVideoPatch.url.contains("ZYXWVUTSRQP"),
                            "Stock Next must start the selected video through the existing queue path");
                        require(field("pendingQueueStart").get(null) != null && field("queue").get(null) == null,
                            "First video must wait for native attachment rather than preassigning a queue");
                        require(originals[0] == 0 && app.morphe.extension.youtube.patches.utils.PlaylistPatch.dialogs == 0,
                            "Native action must not invoke Premium or the account queue dialog");
                        app.morphe.extension.youtube.patches.utils.FlyoutUtils.stock.add(
                            new android.util.Pair<>("QUEUE_PLAY_NEXT", 1));
                        require(AddToQueuePatch.replaceOnItemClick("QUEUE_PLAY_NEXT") && LoadVideoPatch.opens == 2,
                            "The existing contextual click hook must route to Native as well");
                        require(QueueFlyoutActions.onStockQueueItemClick(IconOuterClass.YTIconType.QUEUE_PLAY_NEXT)
                                && LoadVideoPatch.opens == 3,
                            "Direct native sheet dispatch must route to the same captured action");
                        require(!QueueFlyoutActions.onStockQueueItemClick(IconOuterClass.YTIconType.SHARE),
                            "Direct sheet dispatch leaves unrelated actions untouched");
                        app.morphe.extension.youtube.patches.utils.FlyoutUtils.button = "SAVE_TO_PLAYLIST";
                        require(AddToQueuePatch.replaceButtonRunnable(original) == original,
                            "Unrelated stock actions must retain their exact callback");
                        app.morphe.extension.youtube.patches.utils.FlyoutUtils.button = "QUEUE_PLAY_NEXT";
                        app.morphe.extension.youtube.patches.utils.FlyoutUtils.video = "";
                        int opens = LoadVideoPatch.opens;
                        var unavailable = AddToQueuePatch.replaceButtonRunnable(original);
                        require(unavailable != original, "Missing context cannot invoke Premium in Native mode");
                        unavailable.run();
                        require(AddToQueuePatch.replaceOnItemClick("QUEUE_PLAY_NEXT") && LoadVideoPatch.opens == opens
                                && originals[0] == 0, "Contextual clicks must also consume missing-context queue actions");
                        require(AddToQueuePatch.flyoutButtonClickLogic("QUEUE_PLAY_LAST", "ZYXWVUTSRQP")
                                && LoadVideoPatch.opens == opens, "Unavailable Last stays Native and never creates a queue");
                        Utils.failToast = true;
                        require(AddToQueuePatch.flyoutButtonClickLogic("QUEUE_PLAY_NEXT", ""),
                            "Even a Native feedback failure must not fall through to the Premium command");
                        Utils.failToast = false;
                        Settings.QUEUE_TYPE.value = QueueType.DEFAULT;
                        require(AddToQueuePatch.replaceButtonRunnable(original) == original,
                            "Default mode must retain the original callback");
                    }
                    public static void otherQueueRouting() {
                        var menu = PlaylistQueueRenderer.MenuRenderer.newBuilder();
                        for (var icon : List.of(IconOuterClass.YTIconType.QUEUE_PLAY_NEXT,
                                IconOuterClass.YTIconType.QUEUE_PLAY_LAST)) {
                            menu.addItems(PlaylistQueueRenderer.MenuItem.newBuilder().setMenuServiceItemRenderer(
                                PlaylistQueueRenderer.MenuServiceItem.newBuilder().setIcon(
                                    IconOuterClass.Icon.newBuilder().setYtIconType(icon))));
                        }
                        var originalMenu = menu.build();
                        Settings.QUEUE_TYPE.value = QueueType.MORPHE;
                        require(!QueueFlyoutActions.onStockQueueItemClick(IconOuterClass.YTIconType.QUEUE_PLAY_NEXT),
                            "Additional Native dispatch hooks must not intercept another queue provider");
                        require(QueueFlyoutActions.completeQueueMenu(originalMenu) == originalMenu,
                            "Morphe must retain its exact native menu object");
                        require(AddToQueuePatch.flyoutButtonClickLogic("QUEUE_PLAY_NEXT", "ZYXWVUTSRQP"), "Morphe action");
                        require(app.morphe.extension.youtube.patches.utils.PlaylistPatch.dialogs == 1
                                && LoadVideoPatch.opens == 0, "Morphe must retain its playlist dialog");
                        Settings.QUEUE_TYPE.value = QueueType.LOCAL;
                        require(QueueFlyoutActions.completeQueueMenu(originalMenu) == originalMenu,
                            "Local must retain its exact native menu object");
                        require(AddToQueuePatch.flyoutButtonClickLogic("QUEUE_PLAY_LAST", "ZYXWVUTSRQP"), "Local action");
                        require(LocalQueuePatch.additions == 1 && !LocalQueuePatch.next
                                && app.morphe.extension.youtube.patches.utils.PlaylistPatch.dialogs == 1,
                            "Local Last must retain its client-side queue and append direction");
                        require(!AddToQueuePatch.flyoutButtonClickLogic("SAVE_TO_PLAYLIST", "ZYXWVUTSRQP"),
                            "Other actions must not be consumed");
                    }
                    public static void staleLastAction() throws Exception {
                        require(!NativePlaylistQueuePatch.canShowFlyoutLast(),
                            "Last must not be shown without a Native queue");
                        var controller = new Controller(new Playback());
                        installOwned(controller);
                        require(NativePlaylistQueuePatch.canShowFlyoutLast(), "Last is available for an owned Native queue");
                        Runnable last = NativePlaylistQueuePatch.getFlyoutQueueAction("ZYXWVUTSRQP", true);
                        require(last != null, "Last available for an existing queue");
                        field("queue").set(null, null);
                        last.run();
                        require(LoadVideoPatch.opens == 0 && field("pendingQueueStart").get(null) == null,
                            "A stale Last callback must not start a new queue");
                    }
                    static Controller playlistController(boolean mix, boolean minimized) throws Exception {
                        var controller = new Controller(new Playback());
                        controller.playlist = mix ? "RD-mix" : "PL-account";
                        controller.playingPanel = ordinaryWindow(controller.playlist);
                        controller.pageSource = new PageSource();
                        PlaylistPages.attach(controller.pageSource);
                        controller.playback.local = false;
                        field("controllerRef").set(null, new WeakReference<>(controller));
                        field("observingPlayer").setBoolean(null, true);
                        VideoInformation.playlist = controller.playlist;
                        app.morphe.extension.youtube.shared.PlayerType.current = minimized
                            ? app.morphe.extension.youtube.shared.PlayerType.WATCH_WHILE_MINIMIZED
                            : app.morphe.extension.youtube.shared.PlayerType.WATCH_WHILE_MAXIMIZED;
                        return controller;
                    }
                    static Controller verifyPlaylistHandoff(boolean mix, boolean minimized) throws Exception {
                        var controller = playlistController(mix, minimized);
                        String sourcePlaylist = controller.playlist;
                        var type = app.morphe.extension.youtube.shared.PlayerType.current;
                        require(NativePlaylistQueuePatch.canShowFlyoutLast(),
                            "A playing playlist offers conversion through both queue positions");
                        require(AddToQueuePatch.replaceOnItemClick("QUEUE_PLAY_NEXT"), "Native owns stock Next");
                        require(field("queue").get(null) == null, "Read the source without blocking the UI or replacing it early");
                        Utils.drainBackground(); Utils.drain();
                        var queue = (PlaylistQueue) field("queue").get(null);
                        require(queue != null && queue.entries().size() == 4
                                && queue.currentEntry().videoId().equals("abcdefghijk")
                                && queue.entries().get(1).videoId().equals("ZYXWVUTSRQP")
                                && queue.entries().get(2).videoId().equals("0123456789_")
                                && queue.entries().get(3).videoId().equals("ZYXWVUTSRQP"),
                            "Preserve every original row and insert selected video next, including intentional duplicates");
                        require(controller.playback.attaches == 2 && controller.playback.clears == 0
                                && field("nativePlaybackRef").get(null) instanceof WeakReference<?> ref
                                && ref.get() == controller.playback,
                            "Rebind navigation through the SAME active native backend");
                        require(LoadVideoPatch.opens == 0 && LoadVideoPatch.dismissals == 0
                                && LoadVideoPatch.blocks == 0 && field("pendingQueueStart").get(null) == null,
                            "Never reopen, dismiss or schedule a replacement player for playlist conversion");
                        Utils.advanceBy(10000);
                        require(LoadVideoPatch.opens == 0 && app.morphe.extension.youtube.shared.PlayerType.current == type
                                && VideoInformation.time == 75432 && VideoInformation.playlist.equals(sourcePlaylist),
                            "No delayed restart or rewrite of player type, position or shared response identity");
                        require(controller.patch_getPlaylistId().isEmpty() && field("originalModel").get(null) == null,
                            "The local panel has no account ID and Clear cannot restore the old account panel");
                        require(NativePlaylistQueuePatch.canShowFlyoutLast(), "Last becomes available after conversion");
                        NativePlaylistQueuePatch.onControllerUpdated(controller);
                        require(field("queue").get(null) == queue, "Retained source response identity does not release ownership");
                        return controller;
                    }
                    static PlaylistQueueRenderer.NavigationEndpoint playlistWatch(String video, String playlist) {
                        return PlaylistQueueRenderer.NavigationEndpoint.newBuilder().setWatchEndpoint(
                            PlaylistQueueRenderer.WatchEndpoint.newBuilder().setVideoId(video).setPlaylistId(playlist)).build();
                    }
                    public static void playlistNextHandoff() throws Exception { verifyPlaylistHandoff(false, false); }
                    public static void playlistMixHandoff() throws Exception { verifyPlaylistHandoff(true, true); }
                    public static void firstQueueCurrentVideo() throws Exception {
                        for (String playlist : List.of("", "PL-account", "RD-mix")) {
                            var controller = new Controller(new Playback());
                            controller.playlist = playlist;
                            controller.playingPanel = playlist.isEmpty() ? null : ordinaryWindow(playlist);
                            PlaylistPages.attach(new PageSource());
                            controller.playback.local = false;
                            VideoInformation.playlist = playlist;
                            field("controllerRef").set(null, new WeakReference<>(controller));
                            field("observingPlayer").setBoolean(null, true);
                            app.morphe.extension.youtube.shared.PlayerType.current =
                                app.morphe.extension.youtube.shared.PlayerType.WATCH_WHILE_MINIMIZED;
                            require(AddToQueuePatch.flyoutButtonClickLogic("QUEUE_PLAY_NEXT", "abcdefghijk"), "Create Native queue");
                            Utils.drainBackground(); Utils.drain();
                            var queue = (PlaylistQueue) field("queue").get(null);
                            int originalSize = playlist.isEmpty() ? 1 : 3;
                            require(queue != null && queue.entries().size() == originalSize
                                    && queue.currentEntry().videoId().equals("abcdefghijk")
                                    && LoadVideoPatch.opens == 0 && LoadVideoPatch.dismissals == 0,
                                "Starting from the playing video must contain it once and preserve its player");
                            AddToQueuePatch.flyoutButtonClickLogic("QUEUE_PLAY_NEXT", "abcdefghijk");
                            queue = (PlaylistQueue) field("queue").get(null);
                            require(queue.entries().size() == originalSize + 1 && queue.selectedIndex() == 0,
                                "Adding the same video again to an existing queue remains an intentional duplicate");
                            call("releaseNativeQueue");
                        }
                    }
                    public static void playlistHandoffFailed() throws Exception {
                        var controller = playlistController(false, false);
                        Object original = controller.model;
                        controller.playback.attachSucceeds = false;
                        AddToQueuePatch.replaceOnItemClick("QUEUE_PLAY_NEXT"); Utils.drainBackground(); Utils.drain();
                        require(field("queue").get(null) == null && field("convertedPlaylist").get(null) == null
                                && controller.model == original && controller.patch_getPlaylistId().equals("PL-account")
                                && !controller.playback.local && LoadVideoPatch.opens == 0 && LoadVideoPatch.dismissals == 0,
                            "A rejected attachment restores the old model and never falls back to reloading playback");
                    }
                    public static void convertedPlaylistLifetime() throws Exception {
                        var controller = verifyPlaylistHandoff(false, false);
                        var source = playlistWatch("abcdefghijk", "PL-account");
                        NativePlaylistQueuePatch.PlaylistModel oldModel = () -> "PL-account";
                        require(NativePlaylistQueuePatch.shouldIgnorePlaylistModel(controller, oldModel)
                                && NativePlaylistQueuePatch.shouldIgnorePlaylistResponse(controller.playback, source),
                            "Late source models/responses must not reclaim converted navigation");
                        require(!NativePlaylistQueuePatch.shouldIgnorePlaylistResponse(new Playback(), source)
                                && !NativePlaylistQueuePatch.shouldIgnorePlaylistModel(new Controller(new Playback()), oldModel)
                                && !NativePlaylistQueuePatch.shouldIgnorePlaylistResponse(controller.playback,
                                    playlistWatch("abcdefghijk", "PL-other"))
                                && !NativePlaylistQueuePatch.shouldIgnorePlaylistResponse(controller.playback,
                                    playlistWatch("ZYXWVUTSRQP", "PL-account")),
                            "Only the exact old source on its original player can be ignored");
                        field("controllerRef").set(null, new WeakReference<>(null));
                        require((boolean) call("serviceOwnsQueue") && !(boolean) call("hasForeignPlaybackPlaylist"),
                            "The active service retains converted ownership after its watch UI is collected");
                        field("controllerRef").set(null, new WeakReference<>(controller));
                        NativePlaylistQueuePatch.prepareQueueStartContext(source, Map.of());
                        require(field("queue").get(null) == null
                                && !NativePlaylistQueuePatch.shouldIgnorePlaylistResponse(controller.playback, source)
                                && !NativePlaylistQueuePatch.shouldIgnorePlaylistModel(controller, oldModel),
                            "Explicit navigation to even the same source playlist releases the exemption");
                        controller = playlistController(true, true);
                        AddToQueuePatch.replaceOnItemClick("QUEUE_PLAY_NEXT");
                        Settings.QUEUE_TYPE.value = QueueType.DEFAULT;
                        call("releaseNativeQueue");
                        require(field("convertedPlaylist").get(null) == null
                                && !NativePlaylistQueuePatch.shouldIgnorePlaylistResponse(controller.playback,
                                    playlistWatch("abcdefghijk", "RD-mix")),
                            "Provider changes release converted ownership and preserve Default callbacks");
                    }
                    public static void convertedPlaylistUndo() throws Exception {
                        var controller = verifyPlaylistHandoff(false, true);
                        var before = field("queue").get(null);
                        require(controller.playback.patch_clearQueue(), "Native detaches local rows without stopping playback");
                        call("releasePanel");
                        require(controller.model == null && controller.patch_getPlaylistId().isEmpty(),
                            "Clear hides the queue rather than restoring the account playlist panel");
                        var source = playlistWatch("abcdefghijk", "PL-account");
                        require(NativePlaylistQueuePatch.shouldIgnorePlaylistResponse(controller.playback, source)
                                && NativePlaylistQueuePatch.shouldIgnorePlaylistModel(controller, () -> "PL-account"),
                            "Late source responses stay excluded after Clear on the same player");
                        Class<?> undo = Class.forName(owner.getName() + "${'$'}PendingUndo");
                        Constructor<?> create = undo.getDeclaredConstructors()[0]; create.setAccessible(true);
                        field("pendingUndo").set(null, create.newInstance("converted", before,
                            new WeakReference<>(controller.playback), null));
                        var method = owner.getDeclaredMethod("undoQueueEdit", String.class); method.setAccessible(true);
                        method.invoke(null, "converted");
                        require(field("queue").get(null) != null && controller.playback.attaches == 3
                                && LoadVideoPatch.opens == 0 && LoadVideoPatch.dismissals == 0,
                            "Clear Undo reattaches through the original player despite retained source response ID");
                        require(controller.playback.patch_clearQueue(), "Clear after Undo");
                        call("releasePanel");
                        AddToQueuePatch.replaceOnItemClick("QUEUE_PLAY_NEXT");
                        require(field("queue").get(null) != null && controller.playback.attaches == 4
                                && LoadVideoPatch.opens == 0,
                            "Starting another queue after Clear must not inherit the old playlist ID or reopen playback");
                        VideoInformation.playlist = "PL-other";
                        require((boolean) call("hasForeignPlaybackPlaylist"), "A different playlist must retain foreign ownership");
                    }
                    public static void queueTitleOwnership() throws Exception {
                        var controller = new Controller(new Playback()); installOwned(controller);
                        var title = new android.widget.TextView();
                        var model = new Object[] { controller.panel };
                        NativePlaylistQueuePatch.PlaylistTitle header = new NativePlaylistQueuePatch.PlaylistTitle() {
                            public Object patch_getPanel() { return model[0]; }
                            public android.widget.TextView patch_getTitle() { return title; }
                        };
                        // Binding can precede publication. It must recheck ownership after the native call finishes.
                        field("queue").set(null, null);
                        NativePlaylistQueuePatch.onPlaylistTitleBound(header);
                        installOwned(controller);
                        Utils.drain();
                        require(title.params.width == 89 && title.layoutRequests == 1,
                            "Use rounded native font measurement plus padding even when native width is already constrained WRAP_CONTENT");
                        NativePlaylistQueuePatch.onPlaylistTitleBound(header); Utils.drain();
                        require(title.layoutRequests == 1, "Repeated metadata/video binds must not request redundant layouts");
                        model[0] = new Object();
                        NativePlaylistQueuePatch.onPlaylistTitleBound(header); Utils.drain();
                        require(title.params.width == android.view.ViewGroup.LayoutParams.WRAP_CONTENT && title.layoutRequests == 2,
                            "A header reused for an ordinary playlist restores its native width");
                        model[0] = controller.panel;
                        NativePlaylistQueuePatch.onPlaylistTitleBound(header);
                        model[0] = new Object(); Utils.drain();
                        require(title.params.width == android.view.ViewGroup.LayoutParams.WRAP_CONTENT && title.layoutRequests == 2,
                            "A stale posted queue bind cannot alter the newer ordinary playlist");
                        model[0] = controller.panel;
                        NativePlaylistQueuePatch.onPlaylistTitleBound(header); Utils.drain();
                        title.paint.desiredWidth = 96.5f;
                        NativePlaylistQueuePatch.onPlaylistTitleBound(header); Utils.drain();
                        require(title.params.width == 102, "A native font/style change recomputes the required label width");
                        call("releasePanel");
                        NativePlaylistQueuePatch.onPlaylistTitleBound(header); Utils.drain();
                        require(title.params.width == android.view.ViewGroup.LayoutParams.WRAP_CONTENT, "Clear restores the title width even for a retained owned model");
                    }
                    public static void playlistRowContext() {
                        var row = (app.morphe.extension.youtube.patches.utils.FlyoutUtils.FlyoutMenuVideoIdInterface)
                            () -> "LMNOPQRSTUV";
                        var context = Map.of("com.google.android.libraries.youtube.innertube.endpoint.tag", row);
                        NativePlaylistQueuePatch.onRowMenuOpened(context);
                        require(app.morphe.extension.youtube.patches.utils.FlyoutUtils.contexts == 1
                                && app.morphe.extension.youtube.patches.utils.FlyoutUtils.video.equals("LMNOPQRSTUV"),
                            "An ordinary playlist row must replace the previous flyout video ID");
                        Settings.QUEUE_TYPE.value = QueueType.MORPHE;
                        NativePlaylistQueuePatch.onRowMenuOpened(context);
                        require(app.morphe.extension.youtube.patches.utils.FlyoutUtils.contexts == 1,
                            "Other providers keep their existing ordinary row context path");
                    }
                    static SharedPreferences.OnSharedPreferenceChangeListener listener() throws Exception {
                        return (SharedPreferences.OnSharedPreferenceChangeListener) field("QUEUE_SETTING_LISTENER").get(null);
                    }
                    static com.google.protobuf.MessageLite feedback(String token) {
                        return QueueUndoActions.feedback(token, "Queue cleared", "Undo")
                            .getAddToToastAction().getItem().getToastRenderer();
                    }
                    // These native boundaries record calls; they do not implement a
                    // second playback engine. The coordinator and serialized models are real.
                    static class Playback implements NativePlaylistQueuePatch.NativePlayback {
                        boolean local = true, attachSucceeds = true, clearSucceeds = true;
                        int clears, attaches, selection, repeat = PlaylistQueue.REPEAT_ONE;
                        byte[] panel;
                        public boolean patch_isLocalQueue() { return local; }
                        public boolean patch_attachQueue(byte[] value, byte[] command) {
                            attaches++;
                            try {
                                var watch = PlaylistQueueRenderer.NavigationEndpoint.parseFrom(command).getWatchEndpoint();
                                require(watch.getVideoId().equals(VideoInformation.id) && watch.getPlaylistId().isEmpty(),
                                    "Queue navigation command must describe current video without inheriting the source playlist");
                            } catch (com.google.protobuf.InvalidProtocolBufferException exception) { throw new AssertionError(exception); }
                            if (!attachSucceeds) return false;
                            panel = value; local = true; return true;
                        }
                        public void patch_updateSnapshot(byte[] value) { panel = value; }
                        public int patch_getSelectedIndex() { return selection; }
                        public Object patch_getQueueSequence() { return this; }
                        public int patch_getRepeatMode() { return repeat; }
                        public void patch_restoreQueueRepeat(int value) { repeat = value; }
                        public boolean patch_clearQueue() {
                            clears++;
                            if (!clearSucceeds) return false;
                            local = false; return true;
                        }
                    }
                    static class Controller implements NativePlaylistQueuePatch.PlaylistController {
                        final Playback playback;
                        PageSource pageSource;
                        final Object row = (NativePlaylistQueuePatch.QueueRow) () -> true;
                        final Object panel = (NativePlaylistQueuePatch.QueuePanel) () -> true;
                        boolean attached = true;
                        String playlist;
                        Object model = new Object(), previousModel;
                        String previousPlaylist;
                        com.google.protobuf.MessageLite playingPanel;
                        byte[] feedback;
                        int feedbacks;
                        Controller(Playback value) { playback = value; }
                        public String patch_getVideoId() { return VideoInformation.id; }
                        public String patch_getPlaylistId() { return model == null ? "" : playlist == null ? VideoInformation.playlist : playlist; }
                        public Object patch_getModel() { return model; }
                        public Object patch_getPanel() {
                            return playingPanel != null && !patch_getPlaylistId().isEmpty() ? playingPanel : panel;
                        }
                        public int patch_findQueueRow(Object target) { return target == row ? 1 : -1; }
                        public void patch_markQueueRows() {}
                        public boolean patch_isAttached() { return attached; }
                        public Object patch_getNavigator() {
                            return new NativePlaylistQueuePatch.ServiceNavigator() {
                                public String patch_getVideoId() { return VideoInformation.id; }
                                public NativePlaylistQueuePatch.NativePlayback patch_getQueuePlayback() { return playback; }
                                public void patch_navigateQueue(boolean next) { throw new AssertionError("Unexpected navigation"); }
                                public boolean patch_canNavigateQueue(boolean next) { return false; }
                            };
                        }
                        public Object patch_updatePanel(byte[] value) {
                            previousModel = model; previousPlaylist = patch_getPlaylistId();
                            model = new Object(); playlist = ""; return model;
                        }
                        public void patch_restorePanel(Object expected, Object original) {
                            require(model == expected, "Restore must only touch the accepted model");
                            model = original; playlist = original == previousModel ? previousPlaylist : "";
                        }
                        public void patch_showQueueFeedback(byte[] value) { feedbacks++; feedback = value; }
                    }
                    static PlaylistQueueRenderer.Panel ordinaryWindow(String playlist) {
                        var panel = PlaylistQueueRenderer.Panel.newBuilder().setPlaylistId(playlist).setVideoCount(3);
                        String[] ids = {"abcdefghijk", "0123456789_", "ZYXWVUTSRQP"};
                        for (int index = 0; index < ids.length; index++) {
                            panel.addItems(PlaylistQueueRenderer.Item.newBuilder().setVideo(
                                PlaylistQueueRenderer.Video.newBuilder().setVideoId(ids[index]).setSelected(index == 0)
                                    .setTitle(PlaylistQueueRenderer.Text.newBuilder().setSimpleText("Title " + index))
                                    .setNavigationEndpoint(PlaylistQueueRenderer.NavigationEndpoint.newBuilder().setWatchEndpoint(
                                        PlaylistQueueRenderer.WatchEndpoint.newBuilder().setPlaylistId(playlist)
                                            .setVideoId(ids[index]).setIndex(index).setPlaylistSetVideoId("entry" + index)))));
                        }
                        return panel.build();
                    }
                    static PlaylistQueueRenderer.Panel window(int first, int last, int selected) {
                        var panel = PlaylistQueueRenderer.Panel.newBuilder().setPlaylistId("WL").setVideoCount(5);
                        for (int index = first; index <= last; index++) {
                            String id = String.format(java.util.Locale.ROOT, "%011d", index);
                            panel.addItems(PlaylistQueueRenderer.Item.newBuilder().setVideo(
                                PlaylistQueueRenderer.Video.newBuilder().setVideoId(id).setSelected(index == selected)
                                    .setTitle(PlaylistQueueRenderer.Text.newBuilder().setSimpleText("Title " + index))
                                    .setNavigationEndpoint(PlaylistQueueRenderer.NavigationEndpoint.newBuilder()
                                        .setWatchEndpoint(PlaylistQueueRenderer.WatchEndpoint.newBuilder()
                                            .setPlaylistId("WL").setVideoId(id).setIndex(index)
                                            .setPlaylistSetVideoId("entry" + index)))));
                        }
                        return panel.build();
                    }
                    static class PageSource implements PlaylistPages.Source {
                        PlaylistPage.Account account = new PlaylistPage.Account("account", false);
                        int calls;
                        public PlaylistPage.Account patch_getPlaylistAccount() { return account; }
                        public java.util.concurrent.Future<?> patch_requestPlaylistPage(String playlist, String video, int index,
                                String params, String entry, byte[] tracking, PlaylistPage.Account expected) {
                            Utils.verifyOffMainThread(); calls++;
                            require(playlist.equals("WL") && account.equals(expected), "Use exact authenticated source");
                            var response = video == null ? window(0, 2, 0) : window(2, 4, index);
                            return java.util.concurrent.CompletableFuture.completedFuture(response);
                        }
                        public com.google.protobuf.MessageLite patch_getPlaylistPage(Object response) {
                            return (com.google.protobuf.MessageLite) response;
                        }
                        public com.google.protobuf.MessageLite patch_getPlaylistNavigation(Object response) {
                            return PlaylistQueueRenderer.PlaylistNavigation.getDefaultInstance();
                        }
                    }
                    enum Intent { AUTOPLAY, NEXT, PREVIOUS }
                    static class PagingController extends Controller implements NativePlaylistQueuePatch.PagingOwner {
                        final Object list = new Object();
                        final NativePlaylistQueuePatch.PlaylistNavigator navigator;
                        int resumes;
                        boolean loading;
                        final NativePlaylistQueuePatch.LoadingView loadingView = value -> loading = value;
                        Object resumed;
                        PagingController(Playback playback) {
                            super(playback);
                            navigator = new NativePlaylistQueuePatch.PlaylistNavigator() {
                                public String patch_getVideoId() { return VideoInformation.id; }
                                public NativePlaylistQueuePatch.NativePlayback patch_getQueuePlayback() { return playback; }
                                public void patch_navigateQueue(boolean next) { throw new AssertionError("Do not replace native intent"); }
                                public boolean patch_canNavigateQueue(boolean next) { return true; }
                                public Enum<?> patch_getPlaylistIntent(Object request) { return Intent.AUTOPLAY; }
                                public void patch_resumePlaylistNavigation(Object request) { resumes++; resumed = request; }
                            };
                        }
                        public Object patch_getNavigator() { return navigator; }
                        public Object patch_getPlaylistList() { return list; }
                        public NativePlaylistQueuePatch.LoadingView patch_getPlaylistLoadingView() { return loadingView; }
                    }
                    static PageSource startIdlePlaylist() throws Exception {
                        return startIdlePlaylist(new PageSource());
                    }
                    static PageSource startIdlePlaylist(PageSource source) throws Exception {
                        app.morphe.extension.youtube.shared.PlayerType.current = app.morphe.extension.youtube.shared.PlayerType.NONE;
                        PlaylistPages.attach(source);
                        Runnable action = NativePlaylistQueuePatch.getPlaylistQueueAction("WL", false);
                        require(action != null, "Offer a collection action without playback");
                        action.run();
                        require(source.calls == 0 && LoadVideoPatch.opens == 0, "Fetch off main before opening playback");
                        return source;
                    }
                    static PagingController acceptIdlePlaylist() throws Exception {
                        Utils.drainBackground(); Utils.drain();
                        require(LoadVideoPatch.opens == 1 && field("queue").get(null) == null,
                            "First response opens playback but cannot itself attach a queue");
                        VideoInformation.id = "00000000000";
                        app.morphe.extension.youtube.shared.PlayerType.current =
                            app.morphe.extension.youtube.shared.PlayerType.WATCH_WHILE_MINIMIZED;
                        var playback = new Playback(); playback.repeat = PlaylistQueue.REPEAT_OFF;
                        var controller = new PagingController(playback);
                        field("controllerRef").set(null, new WeakReference<>(controller));
                        call("tryStartQueue");
                        var queue = (PlaylistQueue) field("queue").get(null);
                        require(queue != null && queue.entries().size() == 3 && queue.totalCount() == 5,
                            "Attach the first native window and reported total");
                        return controller;
                    }
                    public static void idlePlaylistPaging() throws Exception {
                        var source = startIdlePlaylist(); var controller = acceptIdlePlaylist();
                        Utils.drainBackground(); Utils.drain();
                        require(source.calls == 1, "Do not eagerly fetch more pages or each video's metadata");
                        NativePlaylistQueuePatch.onNativePlaylistScroll(new Object()); Utils.drainBackground(); Utils.drain();
                        require(source.calls == 1, "Unrelated playlist scrolling cannot load this queue");
                        NativePlaylistQueuePatch.onNativePlaylistScroll(controller.list);
                        NativePlaylistQueuePatch.onNativePlaylistScroll(controller.list);
                        Utils.drainBackground(); Utils.drain();
                        var queue = (PlaylistQueue) field("queue").get(null);
                        require(source.calls == 2 && queue.entries().size() == 5, "Coalesce native scroll triggers into one page");
                        require(queue.entries().get(4).metadata() != null && Logger.errors == 0, "Preserve native row metadata");
                    }
                    static class WindowSource extends PageSource {
                        int requestedIndex = -1;
                        public java.util.concurrent.Future<?> patch_requestPlaylistPage(String playlist, String video, int index,
                                String params, String entry, byte[] tracking, PlaylistPage.Account expected) {
                            Utils.verifyOffMainThread(); calls++;
                            require(playlist.equals("WL") && account.equals(expected), "Keep native source identity");
                            if (video != null) {
                                require(video.equals(String.format(java.util.Locale.ROOT, "%011d", index))
                                        && entry.equals("entry" + index), "Use the original source occurrence");
                                requestedIndex = index;
                            }
                            return java.util.concurrent.CompletableFuture.completedFuture(window(0, Math.min(index + 2, 4), index));
                        }
                    }
                    public static void playbackWindowGrowth() throws Exception {
                        var source = new WindowSource(); startIdlePlaylist(source); var controller = acceptIdlePlaylist();
                        NativePlaylistQueuePatch.onNativeQueueSelection(); Utils.drain(); Utils.drainBackground(); Utils.drain();
                        require(source.calls == 2, "Default off follows the native playback window");
                        NativePlaylistQueuePatch.onNativeQueueSelection();
                        Utils.drain(); Utils.drainBackground(); Utils.drain();
                        var queue = (PlaylistQueue) field("queue").get(null);
                        require(source.calls == 2 && queue.entries().size() == 3 && queue.source() != null,
                            "An unchanged playback window neither duplicates rows nor proves EOF");
                        for (int index = 1; index <= 2; index++) {
                            controller.playback.selection = index;
                            VideoInformation.id = String.format(java.util.Locale.ROOT, "%011d", index);
                            NativePlaylistQueuePatch.onVideoChanged(VideoInformation.id);
                            NativePlaylistQueuePatch.onNativeQueueSelection(); Utils.drain();
                            NativePlaylistQueuePatch.onNativePlaylistScroll(controller.list);
                            Utils.drainBackground(); Utils.drain();
                            queue = (PlaylistQueue) field("queue").get(null);
                            require(source.calls == index + 2 && source.requestedIndex == index
                                    && queue.entries().size() == index + 3 && queue.selectedIndex() == index,
                                "Each transition merges only the newly supplied tail and ignores scroll fetching");
                        }
                        require(LoadVideoPatch.opens == 1 && Logger.errors == 0, "Do not reopen or block playback");
                    }
                    public static void playbackWindowSelection() throws Exception {
                        var source = new WindowSource(); startIdlePlaylist(source); var controller = acceptIdlePlaylist();
                        Settings.NATIVE_QUEUE_FETCH_ON_SCROLL.value = false;
                        controller.playback.selection = 1; VideoInformation.id = "00000000001";
                        NativePlaylistQueuePatch.onNativeQueueSelection(); Utils.drain(); Utils.drainBackground();
                        controller.playback.selection = 2; VideoInformation.id = "00000000002";
                        Utils.drain();
                        require(((PlaylistQueue) field("queue").get(null)).entries().size() == 3,
                            "An older playback response cannot publish before native selection is synchronized");
                        Utils.drainBackground(); Utils.drain();
                        var queue = (PlaylistQueue) field("queue").get(null);
                        require(source.requestedIndex == 2 && queue.selectedIndex() == 2 && queue.entries().size() == 5,
                            "Coalesce rapid transitions and refresh the latest confirmed occurrence");
                        require(Logger.errors == 0, "Stale playback completion is an expected transition");
                    }
                    public static void playbackWindowLifetime() throws Exception {
                        var source = new WindowSource(); startIdlePlaylist(source); var controller = acceptIdlePlaylist();
                        Settings.NATIVE_QUEUE_FETCH_ON_SCROLL.value = false;
                        controller.attached = false;
                        controller.playback.selection = 1; VideoInformation.id = "00000000001";
                        NativePlaylistQueuePatch.onNativeQueueSelection(); Utils.drain(); Utils.drainBackground(); Utils.drain();
                        require(source.calls == 1, "A detached view cannot publish an automatic window");
                        controller.attached = true;
                        field("observingPlayer").setBoolean(null, true);
                        NativePlaylistQueuePatch.onControllerUpdated(controller); Utils.drainBackground();
                        require(source.requestedIndex == 1, "Reattaching catches up without another video-ID change");
                        int toasts = Utils.toasts;
                        Settings.NATIVE_QUEUE_FETCH_ON_SCROLL.value = true;
                        listener().onSharedPreferenceChanged(null, Settings.NATIVE_QUEUE_FETCH_ON_SCROLL.key); Utils.drain();
                        require(((PlaylistQueue) field("queue").get(null)).entries().size() == 3 && Utils.toasts == toasts,
                            "Enabling scroll loading rejects an in-flight playback window silently");
                        NativePlaylistQueuePatch.onNativePlaylistScroll(controller.list);
                        Utils.drainBackground(); Utils.drain();
                        require(((PlaylistQueue) field("queue").get(null)).entries().size() == 5,
                            "Enabling the setting allows scroll loading");
                        Settings.NATIVE_QUEUE_FETCH_ON_SCROLL.value = false;
                        controller.playback.selection = 2; VideoInformation.id = "00000000002";
                        NativePlaylistQueuePatch.onNativeQueueSelection(); Utils.drain(); Utils.drainBackground();
                        call("releaseNativeQueue"); Utils.drain();
                        require(field("queue").get(null) == null && field("playlistRequestToken").get(null) == null
                                && field("playlistWindowEntry").get(null) == null,
                            "Clear releases source-window state and rejects late completion");
                    }
                    public static void playbackWindowBoundary() throws Exception {
                        var source = new WindowSource(); startIdlePlaylist(source); var controller = acceptIdlePlaylist();
                        Settings.NATIVE_QUEUE_FETCH_ON_SCROLL.value = false;
                        controller.playback.selection = 2; VideoInformation.id = "00000000002";
                        NativePlaylistQueuePatch.onNativeQueueSelection(); Utils.drain();
                        Object request = new Object();
                        require(NativePlaylistQueuePatch.deferPlaylistNavigation(controller.navigator, request), "Keep native intent");
                        Utils.drain(); Utils.drainBackground(); Utils.drain();
                        require(source.calls == 2 && controller.resumes == 1 && controller.resumed == request,
                            "Cancel speculative work and resume the exact navigation after loading the boundary");
                        require(Logger.errors == 0, "Boundary priority must not produce an automatic-loading error");
                    }
                    public static void playlistRequestLifetime() throws Exception {
                        app.morphe.extension.youtube.shared.PlayerType.current = app.morphe.extension.youtube.shared.PlayerType.NONE;
                        app.morphe.extension.youtube.patches.utils.FlyoutUtils.nativePlaylistMenu = true;
                        require(AddToQueuePatch.flyoutButtonClickLogic("QUEUE_PLAY_NEXT", "abcdefghijk")
                                && LoadVideoPatch.opens == 0,
                            "An unresolved collection must never silently queue only its thumbnail video");
                        app.morphe.extension.youtube.patches.utils.FlyoutUtils.nativePlaylistMenu = false;
                        var source = startIdlePlaylist();
                        NativePlaylistQueuePatch.onUserLeaveHint(); Utils.drainBackground(); Utils.drain();
                        require(source.calls == 0 && LoadVideoPatch.opens == 0, "Cancel idle work before a native request starts");
                        source = startIdlePlaylist(); Utils.drainBackground();
                        source.account = new PlaylistPage.Account("other", false); Utils.drain();
                        require(LoadVideoPatch.opens == 0 && field("pendingQueueStart").get(null) == null,
                            "A response from a previous account must never start playback");
                        var cancelled = new boolean[1];
                        var timeout = new PageSource() {
                            public java.util.concurrent.Future<?> patch_requestPlaylistPage(String playlist, String video, int index,
                                    String params, String entry, byte[] tracking, PlaylistPage.Account expected) {
                                return new java.util.concurrent.Future<Object>() {
                                    public boolean isDone() { return false; }
                                    public boolean isCancelled() { return cancelled[0]; }
                                    public boolean cancel(boolean interrupt) { cancelled[0] = interrupt; return true; }
                                    public Object get() { throw new AssertionError("Use a bounded wait"); }
                                    public Object get(long time, java.util.concurrent.TimeUnit unit) throws java.util.concurrent.TimeoutException {
                                        throw new java.util.concurrent.TimeoutException();
                                    }
                                };
                            }
                        };
                        PlaylistPages.attach(timeout); Utils.mainThread = false;
                        try {
                            PlaylistPages.first(timeout, new PlaylistQueue.Source("WL", timeout.account));
                            throw new AssertionError("Timeout must propagate");
                        } catch (java.util.concurrent.TimeoutException expected) {
                            require(cancelled[0], "Timeout must cancel the actual native transport Future");
                        } finally { Utils.mainThread = true; }
                    }
                    public static void playlistBoundaryReplay() throws Exception {
                        var source = startIdlePlaylist(); var controller = acceptIdlePlaylist();
                        controller.playback.selection = 2; VideoInformation.id = "00000000002";
                        Object request = new Object();
                        controller.playback.repeat = PlaylistQueue.REPEAT_ONE;
                        require(!NativePlaylistQueuePatch.deferPlaylistNavigation(controller.navigator, request),
                            "Repeat one must not advance or fetch another page");
                        controller.playback.repeat = PlaylistQueue.REPEAT_OFF;
                        require(NativePlaylistQueuePatch.isUnloadedPlaylistBoundary(controller.playback, Intent.AUTOPLAY),
                            "An unloaded source must prevent early wrap");
                        require(NativePlaylistQueuePatch.deferPlaylistNavigation(controller.navigator, request), "Defer the original request");
                        Utils.drain(); Utils.drainBackground(); Utils.drain();
                        require(source.calls == 2 && controller.resumes == 1 && controller.resumed == request,
                            "Replay the same autoplay request after native rows are available");
                        require(Logger.errors == 0, "Boundary replay must not fail silently");
                    }
                    public static void playlistWholeOperations() throws Exception { playlistWholeOperation(false); }
                    public static void playlistSaveOperation() throws Exception { playlistWholeOperation(true); }
                    public static void activePlaylistNext() throws Exception {
                        var source = startIdlePlaylist(); var controller = acceptIdlePlaylist();
                        require(NativePlaylistQueuePatch.canQueuePlaylist(), "An owned active queue accepts whole playlists");
                        NativePlaylistQueuePatch.onNativePlaylistScroll(controller.list);
                        var menu = QueueFlyoutActions.completeMenu(PlaylistQueueRenderer.MenuRenderer.getDefaultInstance(),
                            true, "Next", "Last", "WL");
                        var next = QueueFlyoutActions.getPlaylistQueueAction(menu.getItems(0));
                        require(next != null, "The bound item retains its collection and Next action");
                        next.run();
                        NativePlaylistQueuePatch.getPlaylistQueueAction("WL", true).run();
                        require(source.calls == 1 && !controller.loading, "Next fetches off main while keeping the queue browsable");
                        controller.playback.selection = 1; VideoInformation.id = "00000000001";
                        Utils.drainBackground(); Utils.drain();
                        var queue = (PlaylistQueue) field("queue").get(null);
                        require(source.calls == 5 && queue.entries().size() == 10 && queue.totalCount() == 10,
                            "Keep the primary page and fetch the entire incoming playlist; ignore a second busy action");
                        require(queue.selectedIndex() == 1 && queue.entries().get(2).videoId().equals("00000000000")
                                && queue.entries().get(7).videoId().equals("00000000002"),
                            "Insert after the current occurrence at completion without replacing the live primary tail");
                        require(queue.source() != null && LoadVideoPatch.opens == 1 && Logger.errors == 0,
                            "Retain primary paging and native playback without reopening a video");
                    }
                    public static void activePlaylistLast() throws Exception {
                        var source = startIdlePlaylist(); var controller = acceptIdlePlaylist();
                        controller.playback.selection = 2; VideoInformation.id = "00000000002";
                        Object request = new Object();
                        require(NativePlaylistQueuePatch.deferPlaylistNavigation(controller.navigator, request),
                            "Keep a native boundary request pending while Last resolves its source");
                        Utils.drain();
                        app.morphe.extension.youtube.patches.utils.FlyoutUtils.nativePlaylist = "WL";
                        require(AddToQueuePatch.flyoutButtonClickLogic("QUEUE_PLAY_LAST", "abcdefghijk"),
                            "The direct stock button must dispatch collection Last instead of its thumbnail video");
                        NativePlaylistQueuePatch.onNativePlaylistScroll(controller.list);
                        require(controller.loading, "Last shows the native loading frame while resolving the existing tail");
                        Utils.drainBackground(); Utils.drain();
                        var queue = (PlaylistQueue) field("queue").get(null);
                        require(queue.entries().size() == 8 && queue.totalCount() == 10 && source.calls == 4,
                            "Finish the old source, but fetch only the incoming first window");
                        require(queue.entries().get(4).videoId().equals("00000000004")
                                && queue.entries().get(5).videoId().equals("00000000000") && queue.source() != null,
                            "The incoming source starts after the entire current queue");
                        require(!controller.loading && controller.resumes == 1 && controller.resumed == request,
                            "Restore content and replay the exact native request across the cursor handoff");
                        NativePlaylistQueuePatch.onNativePlaylistScroll(controller.list);
                        Utils.drainBackground(); Utils.drain();
                        queue = (PlaylistQueue) field("queue").get(null);
                        require(queue.entries().size() == 10 && source.calls == 5 && Logger.errors == 0,
                            "Incoming pages resume only through the native scrolling trigger");
                    }
                    public static void activePlaylistLifetime() throws Exception {
                        var source = startIdlePlaylist(); var controller = acceptIdlePlaylist();
                        var before = (PlaylistQueue) field("queue").get(null);
                        NativePlaylistQueuePatch.getPlaylistQueueAction("WL", true).run(); Utils.drainBackground();
                        source.account = new PlaylistPage.Account("other", false); Utils.drain();
                        require(field("queue").get(null) == before && !controller.loading,
                            "An account switch discards the result and restores content");
                        source.account = new PlaylistPage.Account("account", false);
                        NativePlaylistQueuePatch.getPlaylistQueueAction("WL", false).run(); Utils.drainBackground();
                        call("releaseNativeQueue"); Utils.drain();
                        require(field("queue").get(null) == null && field("playlistAddition").get(null) == null,
                            "Clear invalidates already delivered results without resurrecting a queue");
                        require(LoadVideoPatch.opens == 1 && Logger.errors == 0, "No late result can start playback");
                    }
                    public static void activePlaylistFailure() throws Exception {
                        startIdlePlaylist(); var controller = acceptIdlePlaylist();
                        var before = (PlaylistQueue) field("queue").get(null);
                        var failed = new PageSource() {
                            public java.util.concurrent.Future<?> patch_requestPlaylistPage(String playlist, String video, int index,
                                    String params, String entry, byte[] tracking, PlaylistPage.Account expected) {
                                Utils.verifyOffMainThread();
                                return video == null ? java.util.concurrent.CompletableFuture.completedFuture(window(0, 2, 0)) : null;
                            }
                        };
                        PlaylistPages.attach(failed);
                        for (boolean playLast : new boolean[] {false, true}) {
                            NativePlaylistQueuePatch.getPlaylistQueueAction("WL", playLast).run();
                            Utils.drainBackground(); Utils.drain();
                            require(field("queue").get(null) == before && !controller.loading
                                    && field("playlistAddition").get(null) == null,
                                "A failed incoming or current tail must not publish a partial import or lose the old cursor");
                        }
                        require(Logger.errors == 2, "Report both fetch failures");
                    }
                    static void playlistWholeOperation(boolean save) throws Exception {
                        var source = startIdlePlaylist(); var controller = acceptIdlePlaylist();
                        NativePlaylistQueuePatch.RepeatHeader header = (NativePlaylistQueuePatch.RepeatHeader)
                            Proxy.newProxyInstance(owner.getClassLoader(), new Class<?>[]{NativePlaylistQueuePatch.RepeatHeader.class},
                                (proxy, method, args) -> switch (method.getName()) {
                                    case "patch_getNavigator" -> controller.navigator;
                                    case "patch_getPanel" -> controller.panel;
                                    default -> throw new AssertionError("Unexpected header operation");
                                });
                        boolean intercepted;
                        if (save) {
                            var command = QueueSaveActions.openPicker(List.of("00000000000", "00000000001", "00000000002"));
                            intercepted = NativePlaylistQueuePatch.onQueueMenuCommand(command, Map.of(
                                "com.google.android.libraries.youtube.innertube.endpoint.tag", controller.panel)) == null;
                        } else {
                            intercepted = NativePlaylistQueuePatch.onQueueShuffle(header);
                        }
                        require(intercepted && controller.loading, "Show native loading for an incomplete whole-list operation");
                        require(source.calls == 1, "Do not fetch on the UI thread");
                        Utils.drainBackground(); Utils.drain();
                        var queue = (PlaylistQueue) field("queue").get(null);
                        require(queue.entries().size() == 5 && queue.source() == null && source.calls == 3,
                            "Shuffle must resolve the final native boundary before operating on all rows");
                        require(!controller.loading && queue.currentEntry().videoId().equals("00000000000"),
                            "Restore native content and preserve current playback after shuffling");
                        if (save) {
                            var picker = PlaylistQueueRenderer.NavigationEndpoint.parseFrom(controller.feedback);
                            require(picker.hasAddToPlaylistEndpoint() && picker.getQueueSaveVideoIdsCount() == 5,
                                "The native picker must receive all entries after paging, not only the first window");
                        }
                        require(Logger.errors == 0, "Whole-list operation must complete without hidden errors");
                    }
                    static PagingController playingPagedPlaylist(PageSource source, int first, int last, int selected) throws Exception {
                        PlaylistPages.attach(source);
                        VideoInformation.id = String.format(java.util.Locale.ROOT, "%011d", selected);
                        VideoInformation.playlist = "WL";
                        app.morphe.extension.youtube.shared.PlayerType.current =
                            app.morphe.extension.youtube.shared.PlayerType.WATCH_WHILE_MINIMIZED;
                        var playback = new Playback(); playback.local = false; playback.selection = selected - first;
                        var controller = new PagingController(playback);
                        controller.playlist = "WL"; controller.playingPanel = window(first, last, selected);
                        field("controllerRef").set(null, new WeakReference<>(controller));
                        field("observingPlayer").setBoolean(null, true);
                        return controller;
                    }
                    public static void playingPlaylistNext() throws Exception { playingPlaylistInsertion(false); }
                    public static void playingPlaylistLast() throws Exception { playingPlaylistInsertion(true); }
                    static void playingPlaylistInsertion(boolean playLast) throws Exception {
                        var source = new PageSource(); var controller = playingPagedPlaylist(source, 0, 2, 1);
                        Object original = controller.model;
                        require(NativePlaylistQueuePatch.canQueuePlaylist(), "A playing account playlist accepts a collection action");
                        app.morphe.extension.youtube.patches.utils.FlyoutUtils.nativePlaylist = "WL";
                        AddToQueuePatch.flyoutButtonClickLogic(playLast ? "QUEUE_PLAY_LAST" : "QUEUE_PLAY_NEXT", "abcdefghijk");
                        require(field("queue").get(null) == null && controller.model == original && source.calls == 0,
                            "Read the playing panel in a worker before converting it");
                        Utils.drainBackground(); Utils.drain();
                        require(field("queue").get(null) != null && source.calls == 0,
                            "Convert its loaded first window in place, then schedule the existing insertion worker");
                        Utils.drainBackground(); Utils.drain();
                        var queue = (PlaylistQueue) field("queue").get(null);
                        require(queue.entries().size() == 8 && queue.totalCount() == 10 && queue.selectedIndex() == 1,
                            "Both lists retain their totals and the original playing position");
                        require(queue.currentEntry().videoId().equals("00000000001") && queue.source() != null,
                            "The existing queue insertion keeps one unfinished source and current playback");
                        require(playLast ? queue.entries().get(4).videoId().equals("00000000004")
                                && queue.entries().get(5).videoId().equals("00000000000")
                            : queue.entries().get(2).videoId().equals("00000000000")
                                && queue.entries().get(7).videoId().equals("00000000002"),
                            "Reuse the established Next/Last placement rather than wiping the source playlist");
                        require(LoadVideoPatch.opens == 0 && LoadVideoPatch.dismissals == 0 && VideoInformation.time == 75432
                                && VideoInformation.playlist.equals("WL") && !controller.loading && Logger.errors == 0,
                            "No restart, position rewrite, stale source reclaim or hidden error");
                    }
                    public static void playingPlaylistPrefix() throws Exception {
                        var source = new PageSource() {
                            public com.google.protobuf.MessageLite patch_getPlaylistPage(Object response) {
                                var panel = ((PlaylistQueueRenderer.Panel) response).toBuilder();
                                for (int index = 0; index < panel.getItemsCount(); index++) {
                                    var video = panel.getItems(index).getVideo();
                                    if (video.getNavigationEndpoint().getWatchEndpoint().getIndex() == 1) {
                                        panel.setItems(index, panel.getItems(index).toBuilder().setVideo(video.toBuilder()
                                            .setVideoId("00000000003").setNavigationEndpoint(video.getNavigationEndpoint().toBuilder()
                                                .setWatchEndpoint(video.getNavigationEndpoint().getWatchEndpoint().toBuilder()
                                                    .setVideoId("00000000003")))));
                                    }
                                }
                                return panel.build();
                            }
                        };
                        var controller = playingPagedPlaylist(source, 2, 4, 3);
                        AddToQueuePatch.flyoutButtonClickLogic("QUEUE_PLAY_NEXT", "ZYXWVUTSRQP");
                        Utils.drainBackground(); Utils.drain();
                        var queue = (PlaylistQueue) field("queue").get(null);
                        require(source.calls == 2 && queue.entries().size() == 6 && queue.selectedIndex() == 3,
                            "Recover prefix pages and select the exact entry, not an earlier video-ID duplicate");
                        require(queue.entries().get(0).videoId().equals("00000000000")
                                && queue.entries().get(1).videoId().equals("00000000003")
                                && queue.entries().get(4).videoId().equals("ZYXWVUTSRQP")
                                && queue.entries().get(5).videoId().equals("00000000004"),
                            "Keep earlier videos, the following tail and insert after the correct playing occurrence");
                        require(queue.source() != null && LoadVideoPatch.opens == 0 && Logger.errors == 0,
                            "Keep the original source cursor and player");
                    }
                    public static void playingPlaylistLifetime() throws Exception {
                        var source = new PageSource(); var controller = playingPagedPlaylist(source, 2, 4, 3);
                        AddToQueuePatch.flyoutButtonClickLogic("QUEUE_PLAY_NEXT", "ZYXWVUTSRQP");
                        var command = playlistWatch(VideoInformation.id, "WL");
                        var context = Map.of("original", new Object());
                        require(NativePlaylistQueuePatch.prepareQueueStartContext(command, context) == context,
                            "A deliberate watch keeps its original context");
                        Utils.drainBackground(); Utils.drain();
                        require(field("queue").get(null) == null && source.calls == 0,
                            "Even same-video/list explicit navigation cancels pending conversion");
                        AddToQueuePatch.flyoutButtonClickLogic("QUEUE_PLAY_NEXT", "ZYXWVUTSRQP"); Utils.drainBackground();
                        controller.model = new Object(); Utils.drain();
                        require(field("queue").get(null) == null && controller.playback.attaches == 0,
                            "A replaced native model cannot accept the old source result");
                        AddToQueuePatch.flyoutButtonClickLogic("QUEUE_PLAY_NEXT", "ZYXWVUTSRQP"); Utils.drainBackground();
                        source.account = new PlaylistPage.Account("other", false); Utils.drain();
                        require(field("queue").get(null) == null && controller.playback.attaches == 0
                                && Logger.errors == 0 && LoadVideoPatch.opens == 0,
                            "An account switch cannot attach an old playlist or reopen playback");
                        VideoInformation.id = "abcdefghijk";
                        var mixController = playlistController(true, false);
                        AddToQueuePatch.flyoutButtonClickLogic("QUEUE_PLAY_NEXT", "ZYXWVUTSRQP"); Utils.drainBackground();
                        app.morphe.extension.shared.innertube.utils.AuthUtils.account = "other"; Utils.drain();
                        require(field("queue").get(null) == null && mixController.playback.attaches == 0 && Logger.errors == 0,
                            "A finite Mix snapshot also belongs to the account that accepted it");
                    }
                    public static void playingPlaylistFailure() throws Exception {
                        var failed = new PageSource() {
                            public java.util.concurrent.Future<?> patch_requestPlaylistPage(String playlist, String video, int index,
                                    String params, String entry, byte[] tracking, PlaylistPage.Account expected) { return null; }
                        };
                        var controller = playingPagedPlaylist(failed, 2, 4, 3);
                        Object model = controller.model;
                        AddToQueuePatch.flyoutButtonClickLogic("QUEUE_PLAY_NEXT", "ZYXWVUTSRQP");
                        Utils.drainBackground(); Utils.drain();
                        require(field("queue").get(null) == null && controller.model == model
                                && controller.playlist.equals("WL") && !controller.playback.local
                                && field("convertedPlaylist").get(null) == null && Logger.errors == 1,
                            "A failed prefix request leaves the original model, backend and source untouched");
                        controller.model = null;
                        require(!NativePlaylistQueuePatch.canQueuePlaylist(),
                            "A missing playlist model must not turn its still-playing source into a standalone video");
                        NativePlaylistQueuePatch.getPlaylistQueueAction("WL", false).run();
                        AddToQueuePatch.flyoutButtonClickLogic("QUEUE_PLAY_NEXT", "ZYXWVUTSRQP");
                        require(field("queue").get(null) == null && controller.playback.attaches == 0,
                            "Wait for the authoritative source model instead of silently dropping its playlist");
                    }
                    public static void standalonePlaylist() throws Exception {
                        var source = new PageSource(); PlaylistPages.attach(source);
                        var controller = new PagingController(new Playback()); controller.playback.local = false;
                        app.morphe.extension.youtube.shared.PlayerType.current =
                            app.morphe.extension.youtube.shared.PlayerType.WATCH_WHILE_MINIMIZED;
                        field("controllerRef").set(null, new WeakReference<>(controller));
                        field("observingPlayer").setBoolean(null, true);
                        require(NativePlaylistQueuePatch.canQueuePlaylist(), "Standalone playback accepts collection actions");
                        NativePlaylistQueuePatch.getPlaylistQueueAction("WL", false).run();
                        Utils.drainBackground(); Utils.drain();
                        var queue = (PlaylistQueue) field("queue").get(null);
                        require(queue.entries().size() == 6 && queue.currentEntry().videoId().equals("abcdefghijk")
                                && queue.entries().get(1).videoId().equals("00000000000")
                                && LoadVideoPatch.opens == 0 && Logger.errors == 0,
                            "Create through existing in-place attachment, then reuse full incoming Next insertion");
                    }
                    public static void playingVideoLast() throws Exception {
                        playlistController(false, false);
                        AddToQueuePatch.flyoutButtonClickLogic("QUEUE_PLAY_LAST", "ZYXWVUTSRQP");
                        Utils.drainBackground(); Utils.drain();
                        var queue = (PlaylistQueue) field("queue").get(null);
                        require(queue.entries().size() == 4 && queue.entries().get(1).videoId().equals("0123456789_")
                                && queue.entries().get(3).videoId().equals("ZYXWVUTSRQP") && queue.selectedIndex() == 0,
                            "Single-video Last converts and preserves all original entries before appending");
                        require(LoadVideoPatch.opens == 0 && Logger.errors == 0, "Keep playback in place");
                    }
                    static PlaylistQueue installOwned(Controller controller) throws Exception {
                        var queue = new PlaylistQueue("abcdefghijk", "A").playLast("ZYXWVUTSRQP")
                            .playLast("abcdefghijk").select(2).withRepeatMode(PlaylistQueue.REPEAT_ONE);
                        field("queue").set(null, queue);
                        field("controllerRef").set(null, new WeakReference<>(controller));
                        field("nativePlaybackRef").set(null, new WeakReference<>(controller.playback));
                        field("publishedModel").set(null, controller.model);
                        field("originalModel").set(null, null);
                        field("pendingQueueStart").set(null, null);
                        field("pendingUndo").set(null, null);
                        field("pendingReload").set(null, null);
                        app.morphe.extension.youtube.shared.PlayerType.current =
                            app.morphe.extension.youtube.shared.PlayerType.WATCH_WHILE_MINIMIZED;
                        return queue;
                    }
                    static PlaylistQueue prepareReload(Controller controller) throws Exception {
                        var before = installOwned(controller);
                        field("observingPlayer").setBoolean(null, true);
                        Runnable dispatch = NativePlaylistQueuePatch.prepareVideoReload("abcdefghijk");
                        require(dispatch != null, "Capture an owned Reload");
                        require(field("queue").get(null) == null && controller.playback.clears == 1
                                && field("pendingUndo").get(null) == null && controller.feedbacks == 0,
                            "Reload clears local rows and waits for a usable replacement before showing Undo");
                        NativePlaylistQueuePatch.onPlaybackTransition(controller); Utils.drain();
                        require(controller.feedbacks == 0, "The old descriptor cannot present feedback before intent dispatch");
                        controller.attached = false;
                        app.morphe.extension.youtube.shared.PlayerType.current =
                            app.morphe.extension.youtube.shared.PlayerType.NONE;
                        call("onPlaybackContextChanged"); Utils.drain();
                        VideoInformation.id = "ZYXWVUTSRQP";
                        app.morphe.extension.youtube.shared.PlayerType.current =
                            app.morphe.extension.youtube.shared.PlayerType.INLINE_MINIMAL;
                        NativePlaylistQueuePatch.onVideoChanged("ZYXWVUTSRQP"); Utils.drain();
                        require(field("pendingReload").get(null) != null && controller.feedbacks == 0,
                            "Expected teardown and inline previews neither cancel nor receive Reload Undo");
                        VideoInformation.id = "abcdefghijk";
                        dispatch.run(); // The delayed Reload intent is now being opened.
                        return before;
                    }
                    static Controller finishReload() throws Exception {
                        var controller = new Controller(new Playback()); controller.playback.local = false;
                        controller.model = null; // Ordinary standalone playback has no playlist model.
                        app.morphe.extension.youtube.shared.PlayerType.current =
                            app.morphe.extension.youtube.shared.PlayerType.WATCH_WHILE_MAXIMIZED;
                        NativePlaylistQueuePatch.onControllerUpdated(controller); Utils.drain();
                        require(controller.feedbacks == 0, "A binding callback alone does not authorize Reload Undo");
                        NativePlaylistQueuePatch.onPlaybackTransition(controller); Utils.drain();
                        return controller;
                    }
                    static com.google.protobuf.MessageLite feedbackUndo(Controller controller) throws Exception {
                        var toast = PlaylistQueueRenderer.NavigationEndpoint.parseFrom(controller.feedback)
                            .getAddToToastAction().getItem().getToastRenderer();
                        require(toast.getResponseText().getSimpleText().equals("morphe_native_playlist_queue_cleared")
                                && QueueSaveFeedback.confirmationDuration(toast) == 5000,
                            "Reload shares the normal Queue cleared label and five-second Undo duration");
                        return toast.getActionButton().getButtonRenderer().getServiceEndpoint();
                    }
                    public static void reloadQueueUndo() throws Exception {
                        var old = new Controller(new Playback()); var before = prepareReload(old);
                        // An old controller notification cannot offer Undo before the reload watch dispatch.
                        old.attached = true;
                        app.morphe.extension.youtube.shared.PlayerType.current =
                            app.morphe.extension.youtube.shared.PlayerType.WATCH_WHILE_MINIMIZED;
                        NativePlaylistQueuePatch.onControllerUpdated(old); Utils.drain();
                        require(old.feedbacks == 0, "Retained old models must not present Reload feedback");
                        var watch = playlistWatch("abcdefghijk", ""); var context = Map.of("existing", true);
                        require(NativePlaylistQueuePatch.prepareQueueStartContext(watch, context) == context,
                            "Reload does not force minimized presentation or change native watch arguments");
                        NativePlaylistQueuePatch.onControllerUpdated(old); Utils.drain();
                        require(old.feedbacks == 0, "Dispatch alone cannot make the old cleared model usable");
                        var replacement = finishReload();
                        require(replacement.feedbacks == 1 && old.feedbacks == 0
                                && field("queue").get(null) == null && field("pendingReload").get(null) == null,
                            "Only accepted reloaded playback gets Queue cleared, with queue still cleared");
                        NativePlaylistQueuePatch.onControllerUpdated(replacement); Utils.drain();
                        require(replacement.feedbacks == 1, "Later binding must not present feedback twice");
                        var undo = feedbackUndo(replacement);
                        VideoInformation.time = 81000;
                        NativePlaylistQueuePatch.onQueueUndoCommand(undo);
                        var restored = (PlaylistQueue) field("queue").get(null);
                        require(restored != null && restored.entries().equals(before.entries())
                                && restored.selectedIndex() == 2 && replacement.playback.repeat == PlaylistQueue.REPEAT_ONE
                                && old.playback.attaches == 0 && replacement.playback.attaches == 1,
                            "Undo restores order, selected duplicate and repeat on the reloaded backend only");
                        require(LoadVideoPatch.opens == 0 && LoadVideoPatch.dismissals == 0
                                && VideoInformation.time == 81000 && field("pendingQueueStart").get(null) == null,
                            "Reload Undo never opens, dismisses or seeks the running replacement");
                        NativePlaylistQueuePatch.onQueueUndoCommand(undo);
                        require(replacement.playback.attaches == 1, "Reload Undo is single-use");

                        call("releaseNativeQueue"); VideoInformation.time = 75432;
                        old = verifyPlaylistHandoff(false, false);
                        Runnable convertedDispatch = NativePlaylistQueuePatch.prepareVideoReload("abcdefghijk");
                        require(convertedDispatch != null
                                && field("convertedPlaylist").get(null) == null
                                && VideoInformation.playlist.equals("PL-account"),
                            "Converted queues clear their source exemption without rewriting shared response data");
                        convertedDispatch.run();
                        NativePlaylistQueuePatch.prepareQueueStartContext(watch, Map.of());
                        replacement = finishReload();
                        require(replacement.feedbacks == 0, "Wait for the list-free reload response identity");
                        VideoInformation.playlist = "";
                        NativePlaylistQueuePatch.onNativeQueueResponse(replacement.playback); Utils.drain();
                        require(replacement.feedbacks == 1, "Service readiness can finish a converted-playlist Reload");
                        undo = feedbackUndo(replacement);
                        NativePlaylistQueuePatch.onQueueUndoCommand(undo);
                        require(field("queue").get(null) != null && LoadVideoPatch.opens == 0,
                            "Converted queue Reload Undo reattaches without reopening the old account playlist");

                        call("releaseNativeQueue");
                        old = new Controller(new Playback()); prepareReload(old);
                        // Watch intents need not pass through the UI command dispatcher.
                        replacement = finishReload();
                        require(replacement.feedbacks == 1,
                            "A standalone playback transition after our intent dispatch is sufficient without a menu watch command");
                    }
                    public static void reloadQueueLifetime() throws Exception {
                        for (String scenario : List.of("otherWatch", "secondWatch", "shorts", "provider",
                                "timeout", "playlist", "activeVideo")) {
                            VideoInformation.id = "abcdefghijk";
                            var old = new Controller(new Playback()); prepareReload(old);
                            var watch = playlistWatch("abcdefghijk", "");
                            if (scenario.equals("otherWatch")) NativePlaylistQueuePatch.prepareQueueStartContext(
                                playlistWatch("ZYXWVUTSRQP", ""), Map.of());
                            if (scenario.equals("secondWatch")) {
                                NativePlaylistQueuePatch.prepareQueueStartContext(watch, Map.of());
                                NativePlaylistQueuePatch.prepareQueueStartContext(watch, Map.of());
                            }
                            if (scenario.equals("shorts")) {
                                app.morphe.extension.youtube.shared.ShortsPlayerState.open = true;
                                call("onPlaybackContextChanged"); Utils.drain();
                                app.morphe.extension.youtube.shared.ShortsPlayerState.open = false;
                            }
                            if (scenario.equals("provider")) {
                                Settings.QUEUE_TYPE.value = QueueType.LOCAL;
                                call("releaseNativeQueue"); Settings.QUEUE_TYPE.value = QueueType.NATIVE;
                            }
                            if (scenario.equals("timeout")) Utils.advanceBy(10000);
                            if (scenario.equals("playlist")) NativePlaylistQueuePatch.prepareQueueStartContext(
                                playlistWatch("abcdefghijk", "PL-account"), Map.of());
                            if (scenario.equals("activeVideo")) {
                                app.morphe.extension.youtube.shared.PlayerType.current =
                                    app.morphe.extension.youtube.shared.PlayerType.WATCH_WHILE_MAXIMIZED;
                                VideoInformation.id = "ZYXWVUTSRQP";
                                NativePlaylistQueuePatch.onVideoChanged("ZYXWVUTSRQP"); Utils.drain();
                                VideoInformation.id = "abcdefghijk";
                            }
                            NativePlaylistQueuePatch.prepareQueueStartContext(watch, Map.of());
                            var replacement = finishReload();
                            require(replacement.feedbacks == 0 && field("pendingReload").get(null) == null
                                    && field("pendingUndo").get(null) == null,
                                "Cancelled Reload must never show late feedback or retain an Undo capability: " + scenario);
                        }
                        var old = new Controller(new Playback()); prepareReload(old);
                        var watch = playlistWatch("abcdefghijk", "");
                        NativePlaylistQueuePatch.prepareQueueStartContext(watch, Map.of());
                        var replacement = finishReload(); var undo = feedbackUndo(replacement);
                        Utils.advanceBy(5001); NativePlaylistQueuePatch.onQueueUndoCommand(undo);
                        require(field("queue").get(null) == null && replacement.playback.attaches == 0,
                            "Expired Reload Undo keeps the queue cleared");
                        old = new Controller(new Playback()); prepareReload(old);
                        NativePlaylistQueuePatch.prepareQueueStartContext(watch, Map.of());
                        replacement = finishReload(); undo = feedbackUndo(replacement);
                        NativePlaylistQueuePatch.prepareQueueStartContext(watch, Map.of());
                        NativePlaylistQueuePatch.onQueueUndoCommand(undo);
                        require(field("queue").get(null) == null && replacement.playback.attaches == 0,
                            "A deliberate same-video watch cancels Reload Undo even if a backend is reused");

                        old = new Controller(new Playback()); prepareReload(old);
                        NativePlaylistQueuePatch.prepareQueueStartContext(watch, Map.of());
                        replacement = finishReload(); undo = feedbackUndo(replacement);
                        AddToQueuePatch.flyoutButtonClickLogic("QUEUE_PLAY_NEXT", "ZYXWVUTSRQP");
                        var newer = (PlaylistQueue) field("queue").get(null);
                        NativePlaylistQueuePatch.onQueueUndoCommand(undo);
                        require(newer != null && newer.entries().size() == 2 && field("queue").get(null) == newer,
                            "Starting another queue invalidates Reload Undo and cannot resurrect the old sequence");

                        old = new Controller(new Playback()); prepareReload(old);
                        NativePlaylistQueuePatch.prepareQueueStartContext(watch, Map.of());
                        replacement = finishReload(); undo = feedbackUndo(replacement);
                        require(NativePlaylistQueuePatch.prepareVideoReload("abcdefghijk") == null,
                            "A second Reload has no active queue to capture");
                        NativePlaylistQueuePatch.onQueueUndoCommand(undo);
                        require(field("queue").get(null) == null && replacement.playback.attaches == 0,
                            "A second Reload invalidates earlier Undo before its delayed watch dispatch");
                        beginClosedRestore();
                        require(field("pendingQueueStart").get(null) != null, "Closed-player Undo is awaiting acceptance");
                        NativePlaylistQueuePatch.prepareVideoReload("abcdefghijk");
                        require(field("pendingQueueStart").get(null) == null && LoadVideoPatch.activeBlock == 0,
                            "A new Reload cancels an unfinished restore and releases only that restore's inline block");

                        VideoInformation.id = "abcdefghijk";
                        old = new Controller(new Playback()); installOwned(old);
                        Runnable obsoleteDispatch = NativePlaylistQueuePatch.prepareVideoReload("abcdefghijk");
                        var newerOwner = new Controller(new Playback()); installOwned(newerOwner);
                        Runnable currentDispatch = NativePlaylistQueuePatch.prepareVideoReload("abcdefghijk");
                        require(obsoleteDispatch != null && currentDispatch != null, "Prepare two distinct Reload operations");
                        obsoleteDispatch.run();
                        replacement = finishReload();
                        require(replacement.feedbacks == 0, "An old delayed intent cannot authorize the newer Reload");
                        currentDispatch.run();
                        NativePlaylistQueuePatch.onPlaybackTransition(replacement); Utils.drain();
                        require(replacement.feedbacks == 1, "Only the matching Reload dispatch authorizes its new player");
                    }
                    static com.google.protobuf.MessageLite nativeWatch(PlaylistQueueRenderer.WatchEndpoint watch) {
                        var command = PlaylistQueueRenderer.NavigationEndpoint.newBuilder().setWatchEndpoint(watch).build();
                        return (com.google.protobuf.MessageLite) Proxy.newProxyInstance(owner.getClassLoader(),
                            new Class<?>[]{com.google.protobuf.MessageLite.class, QueueUndoActions.Command.class},
                            (proxy, method, args) -> {
                                if (method.getName().equals("toByteArray")) return command.toByteArray();
                                if (method.getName().equals("patch_parseQueueCommand")) return
                                    PlaylistQueueRenderer.NavigationEndpoint.parseFrom((byte[]) args[0]);
                                throw new AssertionError("Unexpected native command operation: " + method.getName());
                            });
                    }
                    static final String SENDER_VIEW_KEY = "com.google.android.libraries.youtube.rendering.elements.sender_view";
                    static com.google.protobuf.MessageLite musicTap(com.google.protobuf.MessageLite command) {
                        return NativePlaylistQueuePatch.prepareQueueVideoCommand(command, Map.of(SENDER_VIEW_KEY, new android.view.View()));
                    }
                    static PlaylistQueueRenderer.WatchEndpoint.Builder musicWatch() {
                        return PlaylistQueueRenderer.WatchEndpoint.newBuilder().setVideoId("ZYXWVUTSRQP").setPlaylistId("RDZYXWVUTSRQP");
                    }
                    static PlaylistQueueRenderer.ConfirmationDialogRenderer mixPrompt(Controller controller) throws Exception {
                        app.morphe.extension.youtube.patches.utils.FeedVideoCardsPatch.individual = false;
                        require(NativePlaylistQueuePatch.onQueueMixTap(nativeWatch(musicWatch().build()),
                                Map.of(SENDER_VIEW_KEY, new android.view.View())), "Intentional Mix tap pauses navigation");
                        return PlaylistQueueRenderer.NavigationEndpoint.parseFrom(controller.feedback)
                            .getShowConfirmationDialogEndpoint().getDialog().getConfirmDialogRenderer();
                    }
                    public static void mixChoice() throws Exception {
                        var controller = new Controller(new Playback()); var before = installOwned(controller);
                        var prompt = mixPrompt(controller);
                        require(field("queue").get(null) == before && controller.playback.attaches == 0
                                && LoadVideoPatch.opens == 0, "Showing or cancelling a prompt leaves playback unchanged");
                        var watch = prompt.getCancelButton().getButtonRenderer().getServiceEndpoint();
                        require(!NativePlaylistQueuePatch.onQueueMixTap(watch, Map.of()),
                            "Watch dispatches the original Mix exactly once without a second prompt");
                        require(watch.getWatchEndpoint().equals(musicWatch().build()), "Watch retains original video and Mix ID");
                        require(NativePlaylistQueuePatch.onQueueMixTap(watch, Map.of()), "Replayed dialog choice cannot navigate again");
                        prompt = mixPrompt(controller);
                        require(NativePlaylistQueuePatch.onQueueMixTap(watch, Map.of()),
                            "An old choice must not consume the newer dialog capability");
                        var add = prompt.getConfirmButton().getButtonRenderer().getServiceEndpoint();
                        MixPlaylistRequest.result = List.of(new VideoMetadata("ZYXWVUTSRQP", "First", "Channel", "", "1:00", false),
                            new VideoMetadata("abcdefghijk", "Duplicate", "Channel", "", "2:00", false));
                        require(NativePlaylistQueuePatch.onQueueMixTap(add, Map.of()), "Add consumes the watch command");
                        require(field("queue").get(null) == before, "Do not mutate before fetch completes");
                        // An edit in the same playback session must survive the async append.
                        var edited = before.playLast("0123456789_");
                        field("queue").set(null, edited);
                        Utils.drainBackground(); Utils.drain();
                        var after = (PlaylistQueue) field("queue").get(null);
                        require(after.entries().size() == edited.entries().size() + 2
                                && after.entries().get(edited.entries().size()).title().equals("First")
                                && after.entries().get(before.entries().size()).videoId().equals("0123456789_")
                                && after.entries().get(after.entries().size()-1).videoId().equals("abcdefghijk")
                                && after.currentEntry() == before.currentEntry() && after.selectedIndex() == before.selectedIndex(),
                            "Append the whole ordered batch, including duplicates, without changing selection");
                        require(controller.playback.attaches == 1 && controller.playback.clears == 0
                                && LoadVideoPatch.opens == 0 && after.repeatMode() == controller.playback.repeat,
                            "Publish once to the same backend, retaining repeat and continuous playback");
                    }
                    public static void mixChoiceGuards() throws Exception {
                        var controller = new Controller(new Playback()); var before = installOwned(controller);
                        var original = nativeWatch(musicWatch().build());
                        require(!NativePlaylistQueuePatch.onQueueMixTap(original, Map.of(SENDER_VIEW_KEY, new android.view.View())),
                            "Individual music videos never get the Mix prompt");
                        app.morphe.extension.youtube.patches.utils.FeedVideoCardsPatch.individual = false;
                        for (var type : new QueueType[] { QueueType.DEFAULT, QueueType.MORPHE, QueueType.LOCAL }) {
                            Settings.QUEUE_TYPE.value = type;
                            require(!NativePlaylistQueuePatch.onQueueMixTap(original, Map.of(SENDER_VIEW_KEY, new android.view.View())),
                                "Do not intercept other queue modes");
                        }
                        Settings.QUEUE_TYPE.value = QueueType.NATIVE;
                        require(!NativePlaylistQueuePatch.onQueueMixTap(original, Map.of()), "No prompt for an unproven source");
                        var prompt = mixPrompt(controller);
                        var add = prompt.getConfirmButton().getButtonRenderer().getServiceEndpoint();
                        // A later ordinary watch invalidates the dialog capability.
                        NativePlaylistQueuePatch.onQueueMixTap(playlistWatch("abcdefghijk", ""), Map.of());
                        require(NativePlaylistQueuePatch.onQueueMixTap(add, Map.of()) && field("pendingMixAddition").get(null) == null,
                            "A stale dialog cannot append or start playback");
                        prompt = mixPrompt(controller);
                        add = prompt.getConfirmButton().getButtonRenderer().getServiceEndpoint();
                        MixPlaylistRequest.result = null;
                        NativePlaylistQueuePatch.onQueueMixTap(add, Map.of()); Utils.drainBackground(); Utils.drain();
                        require(field("queue").get(null) == before && controller.playback.attaches == 0,
                            "Fetch failure leaves the entire existing queue intact");
                        prompt = mixPrompt(controller);
                        add = prompt.getConfirmButton().getButtonRenderer().getServiceEndpoint();
                        MixPlaylistRequest.result = List.of(new VideoMetadata("ZYXWVUTSRQP", "New", "Channel", "", "1:00", false));
                        NativePlaylistQueuePatch.onQueueMixTap(add, Map.of()); Utils.drainBackground();
                        app.morphe.extension.shared.innertube.utils.AuthUtils.account = "different";
                        Utils.drain();
                        require(field("queue").get(null) == before && controller.playback.attaches == 0,
                            "Account changes invalidate a completed fetch before publication");
                        app.morphe.extension.shared.innertube.utils.AuthUtils.account = "test";
                        prompt = mixPrompt(controller); add = prompt.getConfirmButton().getButtonRenderer().getServiceEndpoint();
                        NativePlaylistQueuePatch.onQueueMixTap(add, Map.of()); Utils.drainBackground();
                        call("releasePanel"); Utils.drain();
                        require(field("queue").get(null) == null && controller.playback.attaches == 0,
                            "Clear cancels a pending fetch without resurrecting a queue");
                    }
                    public static void musicVideoTap() throws Exception {
                        var controller = new Controller(new Playback()); var before = installOwned(controller);
                        var bytes = new java.io.ByteArrayOutputStream();
                        var fields = com.google.protobuf.CodedOutputStream.newInstance(bytes);
                        // Player parameters and opaque fields must survive the command rewrite.
                        fields.writeString(6, "player-params"); fields.writeUInt32(8, 42);
                        fields.writeBytes(100, com.google.protobuf.ByteString.copyFromUtf8("opaque")); fields.flush();
                        var watch = musicWatch().mergeFrom(bytes.toByteArray()).build();
                        var original = nativeWatch(watch);
                        var prepared = musicTap(original);
                        require(prepared != original, "Confirmed single-video tap must not start an automatic Mix");
                        var expected = PlaylistQueueRenderer.NavigationEndpoint.newBuilder()
                            .setWatchEndpoint(watch.toBuilder().clearPlaylistId()).build();
                        require(java.util.Arrays.equals(expected.toByteArray(), prepared.toByteArray()),
                            "Only the playlist ID changes; video, player parameters and opaque fields survive");
                        require(field("queue").get(null) == before && controller.playback.clears == 0
                                && controller.playback.attaches == 0 && LoadVideoPatch.opens == 0,
                            "Normalization must leave playback and queue insertion to existing native watch handling");
                    }
                    public static void musicVideoTapGuards() throws Exception {
                        var controller = new Controller(new Playback()); installOwned(controller);
                        var original = nativeWatch(musicWatch().build());
                        app.morphe.extension.youtube.patches.utils.FeedVideoCardsPatch.individual = false;
                        require(musicTap(original) == original, "Explicit Mix/unknown card must retain its exact command");
                        app.morphe.extension.youtube.patches.utils.FeedVideoCardsPatch.individual = true;
                        for (var watch : new PlaylistQueueRenderer.WatchEndpoint[] {
                                musicWatch().setPlaylistId("PLsaved").build(), musicWatch().clearPlaylistId().build(),
                                musicWatch().setIndex(0).build(), musicWatch().setPlaylistSetVideoId("entry").build() }) {
                            var command = nativeWatch(watch);
                            require(musicTap(command) == command, "Deliberate indexed/playlist and ordinary video commands must be untouched");
                        }
                        require(NativePlaylistQueuePatch.prepareQueueVideoCommand(original, Map.of()) == original,
                            "Missing tapped view cannot establish a single-video intent");
                        for (var type : new QueueType[] { QueueType.DEFAULT, QueueType.MORPHE, QueueType.LOCAL }) {
                            Settings.QUEUE_TYPE.value = type;
                            require(musicTap(original) == original, "Other queue providers retain their navigation");
                        }
                        Settings.QUEUE_TYPE.value = QueueType.NATIVE;
                        Utils.mainThread = false;
                        require(musicTap(original) == original, "Background dispatch must not inspect mounted views");
                        Utils.mainThread = true;
                        controller.playback.local = false;
                        require(musicTap(original) == original, "A retained panel does not authorize rewriting foreign playback");
                        controller.playback.local = true; controller.attached = false;
                        require(musicTap(original) == original, "Detached playback is not an owned queue");
                        controller.attached = true;
                        field("publishedModel").set(null, new Object());
                        require(musicTap(original) == original, "A stale queue model cannot authorize normalization");
                        field("publishedModel").set(null, controller.model);
                        field("queue").set(null, null);
                        require(musicTap(original) == original, "Without a queue the music video must retain stock navigation");
                        require(controller.playback.clears == 0 && controller.playback.attaches == 0,
                            "Rejected taps must not modify any backend");
                    }
                    public static void reloadQueueGuards() throws Exception {
                        for (var type : new QueueType[] { QueueType.DEFAULT, QueueType.MORPHE, QueueType.LOCAL }) {
                            var controller = new Controller(new Playback()); var before = installOwned(controller);
                            Settings.QUEUE_TYPE.value = type;
                            require(NativePlaylistQueuePatch.prepareVideoReload("abcdefghijk") == null
                                    && controller.playback.clears == 0 && field("queue").get(null) == before,
                                "Other queue types must retain their own Reload behavior");
                        }
                        Settings.QUEUE_TYPE.value = QueueType.NATIVE;
                        var controller = new Controller(new Playback()); var before = installOwned(controller);
                        require(NativePlaylistQueuePatch.prepareVideoReload("ZYXWVUTSRQP") == null
                                && controller.playback.clears == 0 && field("queue").get(null) == before,
                            "A different starting video does not authorize queue disposal");
                        controller.playback.local = false;
                        require(NativePlaylistQueuePatch.prepareVideoReload("abcdefghijk") == null
                                && controller.playback.clears == 0, "Foreign playback must not be cleared");
                        controller.playback.local = true; controller.playback.clearSucceeds = false;
                        require(NativePlaylistQueuePatch.prepareVideoReload("abcdefghijk") == null
                                && field("queue").get(null) == before && field("pendingReload").get(null) == null,
                            "A rejected clear cannot create misleading Reload Undo");
                    }
                    static com.google.protobuf.MessageLite closePlayer(Controller controller) throws Exception {
                        NativePlaylistQueuePatch.onQueuePlayerClosing();
                        require(controller.feedbacks == 0, "Confirmation must wait until native close runs");
                        controller.attached = false;
                        // Pending native notifications can still describe the mini player,
                        // followed by its dismissal animation, before the final NONE event.
                        for (var type : new app.morphe.extension.youtube.shared.PlayerType[] {
                                app.morphe.extension.youtube.shared.PlayerType.WATCH_WHILE_MINIMIZED,
                                app.morphe.extension.youtube.shared.PlayerType.WATCH_WHILE_SLIDING_MINIMIZED_DISMISSED,
                                app.morphe.extension.youtube.shared.PlayerType.WATCH_WHILE_SLIDING_FULLSCREEN_DISMISSED }) {
                            app.morphe.extension.youtube.shared.PlayerType.current = type;
                            call("onPlaybackContextChanged"); Utils.drain();
                            require(field("pendingUndo").get(null) != null && controller.feedbacks == 0,
                                "Dismissal must retain Undo and wait for the completed native close");
                        }
                        app.morphe.extension.youtube.shared.PlayerType.current =
                            app.morphe.extension.youtube.shared.PlayerType.NONE;
                        // Exercise actual lifecycle invalidation as the old watch screen disappears.
                        call("onPlaybackContextChanged"); Utils.drain();
                        call("onPlaybackContextChanged"); Utils.drain();
                        require(controller.feedbacks == 1, "Close must show exactly one native confirmation");
                        var action = app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.NavigationEndpoint
                            .parseFrom(controller.feedback).getAddToToastAction().getItem().getToastRenderer();
                        require(action.getResponseText().getSimpleText().equals("morphe_native_playlist_queue_cleared"),
                            "Close must use the localized Queue cleared label");
                        require(QueueSaveFeedback.confirmationDuration(action) == 5000,
                            "Player-close Undo uses the same five-second presentation window");
                        return action.getActionButton().getButtonRenderer().getServiceEndpoint();
                    }
                    public static void closedPlayerRestore() throws Exception {
                        var old = new Controller(new Playback()); var before = installOwned(old);
                        var undo = closePlayer(old);
                        var accessible = Utils.getContext().accessibility;
                        accessible.enabled = true; accessible.touchExploration = true;
                        var toast = app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.NavigationEndpoint
                            .parseFrom(old.feedback).getAddToToastAction().getItem().getToastRenderer();
                        require(QueueSaveFeedback.confirmationDuration(toast) == 0,
                            "Accessible close Undo must retain the native twenty-second timeout");
                        Utils.advanceBy(10001);
                        require(old.playback.clears == 1 && field("queue").get(null) == null,
                            "Close must release the live local sequence");
                        require(((WeakReference<?>) field("nativePlaybackRef").get(null)).get() == null,
                            "Disposed playback must not be retained as the restore target");
                        VideoInformation.id = "ZYXWVUTSRQP";
                        app.morphe.extension.youtube.shared.PlayerType.current =
                            app.morphe.extension.youtube.shared.PlayerType.INLINE_MINIMAL;
                        require(NativePlaylistQueuePatch.onQueueUndoCommand(undo), "Native button must consume local Undo");
                        require(LoadVideoPatch.opens == 1 && LoadVideoPatch.url.equals(
                            "https://www.youtube.com/watch?v=abcdefghijk&t=75s"), "Reopen at the saved position");
                        var start = (QueueStart) field("pendingQueueStart").get(null);
                        require(start.candidate() == before && start.restoresClosedPlayer(),
                            "Restore must preserve the original selected occurrence and order");
                        call("tryStartQueue");
                        require(old.playback.attaches == 0 && field("queue").get(null) == null,
                            "Do not attach through the detached old controller");
                        require(LoadVideoPatch.activeBlock != 0 && LoadVideoPatch.releases == 0,
                            "The feed preview must stay blocked while the requested player is not accepted");
                        var watch = app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.NavigationEndpoint
                            .newBuilder().setWatchEndpoint(app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer
                            .WatchEndpoint.newBuilder().setVideoId("abcdefghijk")).build();
                        require(Boolean.TRUE.equals(NativePlaylistQueuePatch.prepareQueueStartContext(watch, Map.of())
                            .get("start_watch_minimized")), "Undo must reopen in the mini player");
                        var replacement = new Controller(new Playback()); replacement.playback.repeat = 0;
                        field("controllerRef").set(null, new WeakReference<>(replacement));
                        VideoInformation.id = "abcdefghijk";
                        app.morphe.extension.youtube.shared.PlayerType.current =
                            app.morphe.extension.youtube.shared.PlayerType.WATCH_WHILE_MINIMIZED;
                        call("tryStartQueue");
                        require(replacement.playback.attaches == 1 && old.playback.attaches == 0,
                            "Attach the full restored queue only to the newly accepted backend");
                        var panel = app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.Panel
                            .parseFrom(replacement.playback.panel);
                        require(panel.getItemsCount() == 3 && panel.getSelectedIndex() == 2,
                            "Native panel must retain the selected duplicate, not just the first video");
                        require(replacement.playback.repeat == PlaylistQueue.REPEAT_ONE,
                            "Restore repeat after the new backend attaches");
                        require(field("pendingQueueStart").get(null) == null && Utils.toasts == 0,
                            "Successful Undo must finish without an unrelated Added toast");
                        require(LoadVideoPatch.activeBlock == 0 && LoadVideoPatch.releases == 1,
                            "Release inline protection once the restored mini player owns its new backend");
                        NativePlaylistQueuePatch.onQueueUndoCommand(undo);
                        require(LoadVideoPatch.opens == 1, "Undo can activate only once");
                    }
                    static void beginClosedRestore() throws Exception {
                        VideoInformation.id = "abcdefghijk";
                        var controller = new Controller(new Playback()); installOwned(controller);
                        var undo = closePlayer(controller);
                        VideoInformation.id = "ZYXWVUTSRQP";
                        app.morphe.extension.youtube.shared.PlayerType.current =
                            app.morphe.extension.youtube.shared.PlayerType.INLINE_MINIMAL;
                        NativePlaylistQueuePatch.onQueueUndoCommand(undo);
                    }
                    public static void closedRestoreInlineLifetime() throws Exception {
                        beginClosedRestore();
                        app.morphe.extension.youtube.shared.ShortsPlayerState.open = true;
                        call("tryStartQueue");
                        require(field("pendingQueueStart").get(null) == null && LoadVideoPatch.activeBlock == 0,
                            "Shorts must cancel the restore and release its feed block");
                        app.morphe.extension.youtube.shared.ShortsPlayerState.open = false;
                        beginClosedRestore();
                        Utils.advanceBy(9999);
                        require(LoadVideoPatch.activeBlock != 0, "Keep protection while native restore can still complete");
                        Utils.advanceBy(1);
                        require(field("pendingQueueStart").get(null) == null && LoadVideoPatch.activeBlock == 0,
                            "A failed restore must release protection at the navigation deadline");
                        beginClosedRestore();
                        int replacementBlock = LoadVideoPatch.blockInlinePlaybackUntilPlayerOpens();
                        call("releaseNativeQueue");
                        require(LoadVideoPatch.activeBlock == replacementBlock,
                            "Cancelling queue restore must not release a newer Reload operation's block");
                    }
                    static void beginPreviewStart() throws Exception {
                        VideoInformation.id = "abcdefghijk";
                        app.morphe.extension.youtube.shared.PlayerType.current =
                            app.morphe.extension.youtube.shared.PlayerType.INLINE_MINIMAL;
                        LoadVideoPatch.previewPlaying = true;
                        NativePlaylistQueuePatch.getFlyoutQueueAction("ZYXWVUTSRQP", false).run();
                    }
                    static void acceptPreviewStart() throws Exception {
                        var start = (QueueStart) field("pendingQueueStart").get(null);
                        String requested = start.candidate().currentEntry().videoId();
                        call("tryStartQueue");
                        require(field("queue").get(null) == null && LoadVideoPatch.activeBlock != 0,
                            "A feed preview cannot attach the queue or release its protection");
                        var watch = playlistWatch(requested, "");
                        require(Boolean.TRUE.equals(NativePlaylistQueuePatch.prepareQueueStartContext(watch, Map.of())
                            .get("start_watch_minimized")), "The requested watch still opens minimized");
                        VideoInformation.id = requested;
                        app.morphe.extension.youtube.shared.PlayerType.current =
                            app.morphe.extension.youtube.shared.PlayerType.WATCH_WHILE_MINIMIZED;
                        var controller = new Controller(new Playback());
                        field("controllerRef").set(null, new WeakReference<>(controller));
                        call("tryStartQueue");
                        var queue = (PlaylistQueue) field("queue").get(null);
                        require(queue != null && queue.currentEntry().videoId().equals(requested)
                                && controller.playback.attaches == 1 && LoadVideoPatch.activeBlock == 0,
                            "Only accepted playback publishes the queue and releases the feed block");
                    }
                    public static void videoPreviewStart() throws Exception {
                        beginPreviewStart();
                        require(LoadVideoPatch.url.equals("https://www.youtube.com/watch?v=ZYXWVUTSRQP")
                                && LoadVideoPatch.activeBlock != 0 && LoadVideoPatch.releases == 0,
                            "Protect the selected video before native dispatch without dismissing a player");
                        acceptPreviewStart();
                        int blocks = LoadVideoPatch.blocks;
                        int opens = LoadVideoPatch.opens;
                        NativePlaylistQueuePatch.getFlyoutQueueAction("0123456789_", false).run();
                        require(LoadVideoPatch.blocks == blocks && LoadVideoPatch.opens == opens,
                            "Adding to attached playback must not acquire another block or reopen the player");
                    }
                    public static void playlistPreviewStart() throws Exception {
                        for (String playlist : List.of("WL", "RDZYXWVUTSRQP")) {
                            for (boolean last : List.of(false, true)) {
                                call("releaseNativeQueue");
                                VideoInformation.id = "abcdefghijk";
                                app.morphe.extension.youtube.shared.PlayerType.current =
                                    app.morphe.extension.youtube.shared.PlayerType.INLINE_MINIMAL;
                                LoadVideoPatch.previewPlaying = true;
                                var source = new PageSource(); PlaylistPages.attach(source);
                                MixPlaylistRequest.result = List.of(
                                    new VideoMetadata("ZYXWVUTSRQP", "Seed", "Channel", "", "1:00", false));
                                int opens = LoadVideoPatch.opens;
                                NativePlaylistQueuePatch.getPlaylistQueueAction(playlist, last).run();
                                require(LoadVideoPatch.opens == opens, "Fetch the initial batch before opening playback");
                                Utils.drainBackground(); Utils.drain();
                                require(LoadVideoPatch.opens == opens + 1 && LoadVideoPatch.activeBlock != 0,
                                    "Both collection types and insertion actions protect their initial watch");
                                acceptPreviewStart();
                            }
                        }
                    }
                    public static void previewStartLifetime() throws Exception {
                        beginPreviewStart();
                        Utils.advanceBy(9999);
                        require(LoadVideoPatch.activeBlock != 0, "Keep protection while startup is still pending");
                        Utils.advanceBy(1);
                        require(field("pendingQueueStart").get(null) == null && LoadVideoPatch.activeBlock == 0,
                            "Timeout must release an unaccepted start's feed block");
                        beginPreviewStart();
                        int previous = LoadVideoPatch.activeBlock;
                        Utils.advanceBy(5000);
                        NativePlaylistQueuePatch.getFlyoutQueueAction("0123456789_", false).run();
                        int replacement = LoadVideoPatch.activeBlock;
                        require(replacement != 0 && replacement != previous, "A replacement start owns a fresh block");
                        Utils.advanceBy(5000);
                        require(field("pendingQueueStart").get(null) != null && LoadVideoPatch.activeBlock == replacement,
                            "The old startup deadline cannot cancel or unblock its replacement");
                        int newerReload = LoadVideoPatch.blockInlinePlaybackUntilPlayerOpens();
                        call("releaseNativeQueue");
                        require(field("pendingQueueStart").get(null) == null && LoadVideoPatch.activeBlock == newerReload,
                            "Queue cancellation cannot release a newer Reload operation's protection");
                        LoadVideoPatch.releaseInlinePlaybackBlock(newerReload);
                        LoadVideoPatch.failOpen = true;
                        beginPreviewStart();
                        require(field("pendingQueueStart").get(null) == null && field("queue").get(null) == null
                                && LoadVideoPatch.activeBlock == 0,
                            "A failed watch dispatch must release its block without publishing a queue");
                    }
                    public static void closedPlayerSuperseded() throws Exception {
                        var controller = new Controller(new Playback()); installOwned(controller);
                        var undo = closePlayer(controller);
                        var watch = app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.NavigationEndpoint
                            .newBuilder().setWatchEndpoint(app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer
                            .WatchEndpoint.newBuilder().setVideoId("abcdefghijk")).build();
                        NativePlaylistQueuePatch.prepareQueueStartContext(watch, Map.of());
                        NativePlaylistQueuePatch.onQueueUndoCommand(undo);
                        require(LoadVideoPatch.opens == 0, "A new watch, even the same video, cancels old close Undo");
                        controller = new Controller(new Playback()); installOwned(controller);
                        undo = closePlayer(controller);
                        app.morphe.extension.youtube.shared.PlayerType.current =
                            app.morphe.extension.youtube.shared.PlayerType.WATCH_WHILE_MINIMIZED;
                        call("onPlaybackContextChanged"); Utils.drain();
                        NativePlaylistQueuePatch.onQueueUndoCommand(undo);
                        require(LoadVideoPatch.opens == 0, "An active player after close must cancel the old Undo");
                        controller = new Controller(new Playback()); installOwned(controller);
                        undo = closePlayer(controller);
                        Settings.QUEUE_TYPE.value = QueueType.LOCAL;
                        call("releaseNativeQueue"); NativePlaylistQueuePatch.onQueueUndoCommand(undo);
                        require(LoadVideoPatch.opens == 0, "Other queue type cancels closed queue Undo");
                    }
                    public static void closedPlayerGuards() throws Exception {
                        var controller = new Controller(new Playback()); var before = installOwned(controller);
                        app.morphe.extension.youtube.shared.PlayerType.current =
                            app.morphe.extension.youtube.shared.PlayerType.NONE;
                        call("onPlaybackContextChanged"); Utils.drain();
                        require(field("queue").get(null) == before && field("pendingUndo").get(null) == null
                            && controller.feedbacks == 0, "Backgrounding alone must not clear a live queue or show Undo");
                        app.morphe.extension.youtube.shared.PlayerType.current =
                            app.morphe.extension.youtube.shared.PlayerType.WATCH_WHILE_MINIMIZED;
                        VideoInformation.playlist = "PL-account";
                        NativePlaylistQueuePatch.onQueuePlayerClosing();
                        require(controller.playback.clears == 0 && field("queue").get(null) == before,
                            "Account playlist playback must fall through unchanged");
                        VideoInformation.playlist = ""; VideoInformation.time = -1;
                        NativePlaylistQueuePatch.onQueuePlayerClosing();
                        require(controller.playback.clears == 0 && field("pendingUndo").get(null) == null,
                            "Unknown playback position must not create misleading restoration feedback");
                        VideoInformation.time = 75432; controller.playback.local = false;
                        NativePlaylistQueuePatch.onQueuePlayerClosing();
                        require(controller.playback.clears == 0, "Never clear a foreign playback backend");
                    }
                    public static void singleRemovalSilent() throws Exception {
                        var controller = new Controller(new Playback()); installOwned(controller);
                        var hide = app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.NavigationEndpoint
                            .newBuilder().setHideEnclosingAction(app.morphe.extension.youtube.innertube.queue
                            .PlaylistQueueRenderer.HideEnclosingAction.newBuilder().setHack(true)).build();
                        require(NativePlaylistQueuePatch.onQueueMenuCommand(hide, Map.of(
                            "com.google.android.libraries.youtube.innertube.endpoint.tag", controller.row)) == null,
                            "Successful owned removal must consume native Hide without feedback");
                        var current = (PlaylistQueue) field("queue").get(null);
                        require(current.entries().size() == 2 && current.selectedIndex() == 1,
                            "Remove the requested row and keep the selected duplicate");
                        require(field("pendingUndo").get(null) == null && controller.feedbacks == 0 && Utils.toasts == 0,
                            "Single removal must be silent");
                        NativePlaylistQueuePatch.onQueueMenuCommand(hide, Map.of(
                            "com.google.android.libraries.youtube.innertube.endpoint.tag", controller.row));
                        require(field("queue").get(null) == current, "The playing row must still be protected");
                        require(NativePlaylistQueuePatch.onQueueMenuCommand(hide, Map.of()) == hide,
                            "Ordinary native removal must retain its original command");
                    }
                    public static void menuClearUndo() throws Exception {
                        var controller = new Controller(new Playback()); var before = installOwned(controller);
                        var hide = app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.NavigationEndpoint
                            .newBuilder().setHideEnclosingAction(app.morphe.extension.youtube.innertube.queue
                            .PlaylistQueueRenderer.HideEnclosingAction.newBuilder().setHack(true)).build();
                        var nativeCommand = (com.google.protobuf.MessageLite) Proxy.newProxyInstance(owner.getClassLoader(),
                            new Class<?>[]{com.google.protobuf.MessageLite.class, QueueUndoActions.Command.class},
                            (proxy, method, args) -> {
                                if (method.getName().equals("toByteArray")) return hide.toByteArray();
                                if (method.getName().equals("patch_parseQueueCommand")) return
                                    app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.NavigationEndpoint
                                        .parseFrom((byte[]) args[0]);
                                throw new AssertionError("Unexpected native command operation: " + method.getName());
                            });
                        var feedback = NativePlaylistQueuePatch.onQueueMenuCommand(nativeCommand, Map.of(
                            "com.google.android.libraries.youtube.innertube.endpoint.tag", controller.panel));
                        require(field("queue").get(null) == null && controller.playback.clears == 1,
                            "Menu Clear must detach only the local sequence");
                        var undo = app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.NavigationEndpoint
                            .parseFrom(feedback.toByteArray()).getAddToToastAction().getItem().getToastRenderer()
                            .getActionButton().getButtonRenderer().getServiceEndpoint();
                        NativePlaylistQueuePatch.onQueueUndoCommand(undo);
                        var restored = (PlaylistQueue) field("queue").get(null);
                        require(restored.entries().equals(before.entries()) && restored.selectedIndex() == 2,
                            "Menu Clear Undo must retain the selected duplicate and complete order");
                        require(controller.playback.attaches == 1 && LoadVideoPatch.opens == 0,
                            "Menu Clear Undo must reuse live playback without reopening or seeking");
                    }
                    public static void normalUndoTiming() throws Exception {
                        install(backend(true, true));
                        var accessible = Utils.getContext().accessibility;
                        // Enabled accessibility without touch exploration retains native normal timing.
                        accessible.enabled = true;
                        require(QueueSaveFeedback.confirmationDuration(feedback("token")) == 5000,
                            "Normal Clear snackbar must last five seconds");
                        var first = field("pendingUndo").get(null);
                        require(QueueSaveFeedback.confirmationDuration(feedback("foreign")) == 0,
                            "A stale/foreign token must not override a snackbar");
                        require(field("pendingUndo").get(null) == first,
                            "Foreign feedback must not replace the active capability");
                        Utils.advanceBy(1000);
                        QueueSaveFeedback.confirmationDuration(feedback("token"));
                        var renewed = field("pendingUndo").get(null);
                        require(renewed != first, "Re-created feedback must replace the timer identity");
                        Utils.advanceBy(4000);
                        require(field("pendingUndo").get(null) == renewed,
                            "An old timer must not expire re-created feedback");
                        Utils.advanceBy(999);
                        require(field("pendingUndo").get(null) == renewed, "Undo must remain through the visible window");
                        Utils.advanceBy(1);
                        require(field("pendingUndo").get(null) == null, "Normal Undo must expire with the five-second confirmation");
                    }
                    public static void accessibleUndoTiming() throws Exception {
                        install(backend(true, true));
                        var accessible = Utils.getContext().accessibility;
                        accessible.enabled = true; accessible.touchExploration = true;
                        require(QueueSaveFeedback.confirmationDuration(feedback("token")) == 0,
                            "Accessible feedback must retain the verified native twenty-second action timeout");
                        var current = field("pendingUndo").get(null);
                        Utils.advanceBy(10000);
                        require(field("pendingUndo").get(null) == current,
                            "Accessible Undo must not expire at the old ten-second deadline");
                        Utils.advanceBy(9999);
                        require(field("pendingUndo").get(null) == current, "Accessible Undo must remain through the visible window");
                        Utils.advanceBy(1);
                        require(field("pendingUndo").get(null) == null, "Accessible Undo must expire at twenty seconds");
                    }
                    public static void queueTypeSelection() throws Exception {
                        Settings.QUEUE_TYPE.value = QueueType.NATIVE;
                        require(AddToQueuePatch.isNativeQueueEnabled(), "Native queue type must enable native queue");
                        Settings.QUEUE_TYPE.value = QueueType.MORPHE;
                        require(!AddToQueuePatch.isNativeQueueEnabled(), "Morphe queue type must disable native queue");
                        Settings.QUEUE_TYPE.value = QueueType.LOCAL;
                        require(!AddToQueuePatch.isNativeQueueEnabled(), "Premium-style queue type must disable native queue");
                        Settings.QUEUE_TYPE.value = QueueType.DEFAULT;
                        require(!AddToQueuePatch.isNativeQueueEnabled(), "Default queue type must disable native queue");
                    }
                    public static void acceptedHandoff() throws Exception {
                        Settings.QUEUE_TYPE.value = QueueType.NATIVE;
                        var live = backend(true, true);
                        PlaylistQueue before = install(live);
                        // Settings notification can precede the setting cache write.
                        listener().onSharedPreferenceChanged(null, Settings.QUEUE_TYPE.key);
                        require(field("queue").get(null) == before && clears == 0, "Do not detach inside preference notification");
                        Settings.QUEUE_TYPE.value = QueueType.LOCAL;
                        Utils.drain();
                        require(clears == 1 && field("queue").get(null) == null, "Detach local backend once before releasing ownership");
                        require(((WeakReference<?>) field("nativePlaybackRef").get(null)).get() == null, "Release backend reference");
                        require(field("publishedModel").get(null) == null, "Release accepted panel ownership");
                        require(field("pendingQueueStart").get(null) == null && field("pendingUndo").get(null) == null,
                            "Cancel both late starts and stale Undo");
                        require(OpenVideosFullscreenHookPatch.minimized == null, "Cancel pending minimized start");
                        require(QueueFullscreenActions.refreshes == 1 && Logger.errors == 0, "Refresh after successful handoff");
                        require(LocalQueuePatch.savedItems.equals(List.of("saved-video")), "Preserve Morphe items");
                        NativePlaylistQueuePatch.onNativeQueueResponse(live); Utils.drain();
                        require(clears == 1 && field("queue").get(null) == null, "Late response must not attach native queue");
                        Settings.QUEUE_TYPE.value = QueueType.NATIVE;
                        listener().onSharedPreferenceChanged(null, Settings.QUEUE_TYPE.key); Utils.drain();
                        require(field("queue").get(null) == null, "Switching back to native queue must not resurrect old queue");
                    }
                    public static void failedDetach() throws Exception {
                        Settings.QUEUE_TYPE.value = QueueType.LOCAL;
                        var rejected = backend(true, false); var before = install(rejected);
                        call("releaseNativeQueue");
                        require(clears == 1 && field("queue").get(null) == before, "Failed detach must retain ownership for retry");
                        require(((WeakReference<?>) field("nativePlaybackRef").get(null)).get() == rejected,
                            "Failed detach must retain backend reference");
                        require(field("pendingQueueStart").get(null) == null && field("pendingUndo").get(null) == null,
                            "Failed detach must still invalidate stale work");
                        require(Logger.errors == 1 && QueueFullscreenActions.refreshes == 0, "Report failure without false UI release");
                    }
                    public static void foreignBackend() throws Exception {
                        Settings.QUEUE_TYPE.value = QueueType.LOCAL;
                        install(backend(false, true)); call("releaseNativeQueue");
                        require(clears == 0 && field("queue").get(null) == null, "Never clear an unrelated backend");
                    }
                    public static void pendingStart() throws Exception {
                        Settings.QUEUE_TYPE.value = QueueType.LOCAL;
                        field("pendingQueueStart").set(null, new QueueStart(new PlaylistQueue("abcdefghijk", "Pending"), ""));
                        call("tryStartQueue");
                        require(field("pendingQueueStart").get(null) == null, "Cancel first-video start while another queue type is selected");
                    }
                }
            """.trimIndent())
        }
        try {
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "17", "-classpath", "${classes.path}${File.pathSeparator}${protobuf.path}",
                "-d", output.path, *stubs.map { it.path }.toTypedArray(), fixture.path))
            URLClassLoader(arrayOf(output.toURI().toURL(), classes.toURI().toURL()),
                GeneratedMessageLite::class.java.classLoader).use { loader ->
                val runtime = loader.loadClass("app.morphe.extension.youtube.patches.queue.RuntimeFixture")
                // Existing paging scenarios exercise scroll loading; window scenarios use the default mode.
                runtime.getMethod("setFetchOnScroll", Boolean::class.javaPrimitiveType).invoke(null, fetchOnScroll)
                runtime.getMethod(scenario).invoke(null)
            }
        } finally {
            output.deleteRecursively()
        }
    }
}
