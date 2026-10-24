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
    fun `Native Last requires a queue and stale callbacks cannot create one`() = withFixture("staleLastAction")

    @Test
    fun `stock Next replaces playlist navigation without reopening current playback`() =
        withFixture("playlistNextHandoff")

    @Test
    fun `stock Next replaces Mix navigation and preserves minimized presentation`() = withFixture("playlistMixHandoff")

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

    // NativePlaylistQueuePatch depends on Android settings, scheduling and UI classes.
    // Isolate those boundaries while executing its actual compiled coordinator.
    private fun withFixture(scenario: String) {
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
                    public static PlayerInterface getPlayerInterface() { return () -> dismissals++; }
                    public static int blockInlinePlaybackUntilPlayerOpens() {
                        return activeBlock = ++blocks;
                    }
                    public static void releaseInlinePlaybackBlock(int token) {
                        if (token != 0 && token == activeBlock) { activeBlock = 0; releases++; }
                    }
                    public static void openVideoIntent(String value, boolean dismiss) {
                        if (dismiss) throw new AssertionError("Undo must not dismiss a replacement player");
                        if (value.contains("&t=") && activeBlock == 0)
                            throw new AssertionError("Block inline playback before dispatching a closed queue restore");
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
                    public static void runOnBackgroundThread(Runnable action) { /* Network boundary. */ }
                    public static android.app.Activity getActivity() { return new android.app.Activity(); }
                    public static int toasts;
                    public static boolean failToast;
                    public static void showToastShort(String text) {
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
                        require(!NativePlaylistQueuePatch.canShowFlyoutLast(),
                            "An ordinary playlist or Mix must not make Last visible");
                        require(AddToQueuePatch.replaceOnItemClick("QUEUE_PLAY_LAST")
                                && QueueFlyoutActions.onStockQueueItemClick(IconOuterClass.YTIconType.QUEUE_PLAY_LAST),
                            "Supplied/stale Last remains consumed by Native");
                        require(field("queue").get(null) == null && controller.playback.attaches == 0,
                            "Last cannot create a queue from an ordinary playlist");
                        require(AddToQueuePatch.replaceOnItemClick("QUEUE_PLAY_NEXT"), "Native owns stock Next");
                        var queue = (PlaylistQueue) field("queue").get(null);
                        require(queue != null && queue.entries().size() == 2
                                && queue.currentEntry().videoId().equals("abcdefghijk")
                                && queue.entries().get(1).videoId().equals("ZYXWVUTSRQP"),
                            "Retain current video first and insert selected video next");
                        require(controller.playback.attaches == 1 && controller.playback.clears == 0
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
                            controller.playback.local = false;
                            VideoInformation.playlist = playlist;
                            field("controllerRef").set(null, new WeakReference<>(controller));
                            field("observingPlayer").setBoolean(null, true);
                            app.morphe.extension.youtube.shared.PlayerType.current =
                                app.morphe.extension.youtube.shared.PlayerType.WATCH_WHILE_MINIMIZED;
                            require(AddToQueuePatch.flyoutButtonClickLogic("QUEUE_PLAY_NEXT", "abcdefghijk"), "Create Native queue");
                            var queue = (PlaylistQueue) field("queue").get(null);
                            require(queue != null && queue.entries().size() == 1
                                    && queue.currentEntry().videoId().equals("abcdefghijk")
                                    && LoadVideoPatch.opens == 0 && LoadVideoPatch.dismissals == 0,
                                "Starting from the playing video must contain it once and preserve its player");
                            AddToQueuePatch.flyoutButtonClickLogic("QUEUE_PLAY_NEXT", "abcdefghijk");
                            queue = (PlaylistQueue) field("queue").get(null);
                            require(queue.entries().size() == 2 && queue.selectedIndex() == 0,
                                "Adding the same video again to an existing queue remains an intentional duplicate");
                            call("releaseNativeQueue");
                        }
                    }
                    public static void playlistHandoffFailed() throws Exception {
                        var controller = playlistController(false, false);
                        Object original = controller.model;
                        controller.playback.attachSucceeds = false;
                        AddToQueuePatch.replaceOnItemClick("QUEUE_PLAY_NEXT");
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
                        require(field("queue").get(null) != null && controller.playback.attaches == 2
                                && LoadVideoPatch.opens == 0 && LoadVideoPatch.dismissals == 0,
                            "Clear Undo reattaches through the original player despite retained source response ID");
                        require(controller.playback.patch_clearQueue(), "Clear after Undo");
                        call("releasePanel");
                        AddToQueuePatch.replaceOnItemClick("QUEUE_PLAY_NEXT");
                        require(field("queue").get(null) != null && controller.playback.attaches == 3
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
                        int clears, attaches, repeat = PlaylistQueue.REPEAT_ONE;
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
                        public int patch_getSelectedIndex() { return 0; }
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
                        final Object row = (NativePlaylistQueuePatch.QueueRow) () -> true;
                        final Object panel = (NativePlaylistQueuePatch.QueuePanel) () -> true;
                        boolean attached = true;
                        String playlist;
                        Object model = new Object(), previousModel;
                        String previousPlaylist;
                        byte[] feedback;
                        int feedbacks;
                        Controller(Playback value) { playback = value; }
                        public String patch_getVideoId() { return VideoInformation.id; }
                        public String patch_getPlaylistId() { return model == null ? "" : playlist == null ? VideoInformation.playlist : playlist; }
                        public Object patch_getModel() { return model; }
                        public Object patch_getPanel() { return panel; }
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
                        var start = owner.getDeclaredMethod("startQueue", QueueStart.class); start.setAccessible(true);
                        int blocks = LoadVideoPatch.blocks;
                        start.invoke(null, new QueueStart(new PlaylistQueue("abcdefghijk", "New queue"), "ZYXWVUTSRQP"));
                        call("releasePanel");
                        require(LoadVideoPatch.blocks == blocks && LoadVideoPatch.activeBlock == replacementBlock,
                            "Ordinary first-queue navigation must not acquire or release a restore block");
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
                        require(!(boolean) call("isNativeQueueDisabled"), "Native queue type must enable native queue");
                        Settings.QUEUE_TYPE.value = QueueType.MORPHE;
                        require((boolean) call("isNativeQueueDisabled"), "Morphe queue type must disable native queue");
                        Settings.QUEUE_TYPE.value = QueueType.LOCAL;
                        require((boolean) call("isNativeQueueDisabled"), "Premium-style queue type must disable native queue");
                        Settings.QUEUE_TYPE.value = QueueType.DEFAULT;
                        require((boolean) call("isNativeQueueDisabled"), "Default queue type must disable native queue");
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
                loader.loadClass("app.morphe.extension.youtube.patches.queue.RuntimeFixture")
                    .getMethod(scenario).invoke(null)
            }
        } finally {
            output.deleteRecursively()
        }
    }
}
