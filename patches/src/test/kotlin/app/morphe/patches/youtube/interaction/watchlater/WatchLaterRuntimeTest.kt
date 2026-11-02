/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.interaction.watchlater

import com.google.protobuf.GeneratedMessageLite
import java.io.File
import java.net.URLClassLoader
import javax.tools.ToolProvider
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/** Run the compiled extension with only Android and settings boundaries replaced. */
class WatchLaterRuntimeTest {
    @Test
    fun `removal feedback is isolated by callback playlist action status and setting`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "extensions/youtube/src/main/proto").isDirectory }
        val classes = File(root,
            "extensions/youtube/build/intermediates/javac/release/compileReleaseJavaWithJavac/classes")
        val protobuf = File(GeneratedMessageLite::class.java.protectionDomain.codeSource.location.toURI())
        val output = createTempDirectory("watch-later-runtime").toFile()
        val sources = mapOf(
            "DisplayMetrics" to "package android.util; public class DisplayMetrics { public float density = 2; }",
            "Resources" to """
                package android.content.res;
                public class Resources {
                    public android.util.DisplayMetrics getDisplayMetrics() { return new android.util.DisplayMetrics(); }
                    public String getString(int id) { return "Drag to reorder"; }
                }
            """,
            "View" to """
                package android.view;
                public class View implements ViewParent {
                    public static final int LAYOUT_DIRECTION_RTL = 1;
                    public int direction;
                    public Object tag;
                    public CharSequence description;
                    private static final java.util.List<Runnable> posted = new java.util.ArrayList<>();
                    private TouchDelegate delegate;
                    private ViewGroup.LayoutParams params = new ViewGroup.LayoutParams();
                    public ViewParent getParent() { return null; }
                    public Object getTag(int id) { return tag; }
                    public int getWidth() { return 1080; }
                    public int getHeight() { return 271; }
                    public int getId() { return 0; }
                    public boolean post(Runnable runnable) { posted.add(runnable); return true; }
                    public static void drainPosted() {
                        var pending = new java.util.ArrayList<>(posted);
                        posted.clear();
                        pending.forEach(Runnable::run);
                    }
                    public void setTouchDelegate(TouchDelegate delegate) { this.delegate = delegate; }
                    public TouchDelegate getTouchDelegate() { return delegate; }
                    public CharSequence getContentDescription() { return description; }
                    public int getLayoutDirection() { return direction; }
                    public ViewGroup.LayoutParams getLayoutParams() { return params; }
                    public void setLayoutParams(ViewGroup.LayoutParams value) { params = value; }
                    public android.content.res.Resources getResources() { return new android.content.res.Resources(); }
                }
            """,
            "ViewParent" to "package android.view; public interface ViewParent {}",
            "Rect" to """
                package android.graphics;
                public class Rect {
                    public int left, top, right, bottom;
                    public Rect(int l,int t,int r,int b) { left=l;top=t;right=r;bottom=b; }
                    public boolean contains(int x,int y) { return x>=left && x<right && y>=top && y<bottom; }
                }
            """,
            "TouchDelegate" to "package android.view; public class TouchDelegate { public TouchDelegate(android.graphics.Rect rect, View view) {} }",
            "MotionEvent" to """
                package android.view;
                public class MotionEvent {
                    public static final int ACTION_DOWN=0, ACTION_UP=1, ACTION_MOVE=2, ACTION_CANCEL=3;
                    public int action;
                    public float x, y = 100;
                    public int getActionMasked() { return action; }
                    public float getX() { return x; }
                    public float getY() { return y; }
                }
            """,
            "ComponentHost" to """
                package com.facebook.litho;
                public class ComponentHost extends android.view.ViewGroup {}
            """,
            "ResourceType" to "package app.morphe.extension.shared; public enum ResourceType { ID, STRING }",
            "PlayerType" to """
                package app.morphe.extension.youtube.shared;
                public class PlayerType {
                    public static boolean maximized;
                    public static PlayerType getCurrent() { return new PlayerType(); }
                    public boolean isMaximizedOrFullscreen() { return maximized; }
                }
            """,
            "ResourceUtils" to """
                package app.morphe.extension.shared;
                public class ResourceUtils {
                    public static int getIdentifier(ResourceType type, String name) { return 1; }
                }
            """,
            "ViewGroup" to """
                package android.view;
                public class ViewGroup extends View {
                    public static class LayoutParams { public int width = 32; }
                    public java.util.List<View> children = new java.util.ArrayList<>();
                    public int getChildCount() { return children.size(); }
                    public View getChildAt(int index) { return children.get(index); }
                }
            """,
            "Logger" to """
                package app.morphe.extension.shared;
                public class Logger {
                    public static void printException(java.util.function.Supplier<String> text, Exception ex) {
                        throw new AssertionError(text.get(), ex);
                    }
                }
            """,
            "Settings" to """
                package app.morphe.extension.youtube.settings;
                public class Settings {
                    public static final app.morphe.extension.shared.settings.BooleanSetting WATCH_LATER_HIDE_REMOVAL_SNACKBAR =
                        new app.morphe.extension.shared.settings.BooleanSetting();
                }
            """,
            "BooleanSetting" to """
                package app.morphe.extension.shared.settings;
                public class BooleanSetting { public boolean value; public Boolean get() { return value; } }
            """,
            "Fixture" to """
                import java.util.List;
                import com.google.protobuf.MessageLite;
                import app.morphe.extension.youtube.patches.WatchLaterPatch;
                import app.morphe.extension.youtube.settings.Settings;
                import app.morphe.extension.youtube.innertube.PlaylistEdit.PlaylistEditAction;
                import app.morphe.extension.youtube.innertube.PlaylistEdit.PlaylistEditActionType;
                import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.*;

                public class Fixture implements WatchLaterPatch.Callback {
                    public MessageLite patch_parseWatchLaterResult(byte[] bytes) {
                        try { return QueueSaveEditResponse.parseFrom(bytes); }
                        catch (Exception ex) { throw new AssertionError(ex); }
                    }
                    static void check(boolean value) { if (!value) throw new AssertionError(); }
                    static Fixture callback(String playlist, PlaylistEditActionType action) {
                        var callback = new Fixture();
                        WatchLaterPatch.onEditCreated(callback, playlist,
                            List.of(PlaylistEditAction.newBuilder().setAction(action).build()));
                        return callback;
                    }
                    public static void run() {
                        var toast = NavigationEndpoint.newBuilder().setAddToToastAction(
                            AddToToastAction.newBuilder().setItem(QueueToastItem.newBuilder().setToastRenderer(
                                QueueToastRenderer.newBuilder().setResponseText(Text.newBuilder().setSimpleText("removed")))));
                        var other = NavigationEndpoint.newBuilder().setHideEnclosingAction(HideEnclosingAction.newBuilder());
                        var response = QueueSaveEditResponse.newBuilder().setStatus(1).addActions(toast).addActions(other).build();
                        WatchLaterPatch.setBrowseId("VLWL");
                        check(WatchLaterPatch.enableLongPressDrag());
                        var removal = callback("WL", PlaylistEditActionType.ACTION_REMOVE_VIDEO);
                        check(!Settings.WATCH_LATER_HIDE_REMOVAL_SNACKBAR.get());
                        check(WatchLaterPatch.onEditResult(removal, response) == response);
                        Settings.WATCH_LATER_HIDE_REMOVAL_SNACKBAR.value = true;
                        var filtered = (QueueSaveEditResponse) WatchLaterPatch.onEditResult(removal, response);
                        check(!filtered.getActions(0).hasAddToToastAction());
                        check(filtered.getActions(1).equals(response.getActions(1)));
                        check(WatchLaterPatch.onEditResult(new Fixture(), response) == response);
                        check(WatchLaterPatch.onEditResult(callback("PLother", PlaylistEditActionType.ACTION_REMOVE_VIDEO), response) == response);
                        check(WatchLaterPatch.onEditResult(callback("WL", PlaylistEditActionType.ACTION_ADD_VIDEO), response) == response);
                        var failure = response.toBuilder().setStatus(0).build();
                        check(WatchLaterPatch.onEditResult(removal, failure) == failure);
                        Settings.WATCH_LATER_HIDE_REMOVAL_SNACKBAR.value = false;
                        check(WatchLaterPatch.onEditResult(removal, response) == response);
                        var view = new android.view.View();
                        WatchLaterPatch.widenDragHandle(view);
                        check(view.getLayoutParams().width == 64);
                        var host = new com.facebook.litho.ComponentHost();
                        check(!WatchLaterPatch.allowElementDrag(host));
                        host.tag = new Object();
                        check(WatchLaterPatch.allowElementDrag(host));
                        host.tag = null;
                        var handle = new android.view.View();
                        handle.description = "Drag to reorder";
                        host.children.add(handle);
                        check(WatchLaterPatch.allowElementDrag(host));
                        WatchLaterPatch.onHostLayout(host);
                        android.view.View.drainPosted();
                        var touch = new android.view.MotionEvent();
                        touch.x = 48;
                        check(WatchLaterPatch.interceptHandleTouch(host, touch));
                        check(touch.x == 48);
                        touch.x = 65;
                        check(!WatchLaterPatch.interceptHandleTouch(host, touch));
                        touch.x = 48;
                        touch.action = android.view.MotionEvent.ACTION_CANCEL;
                        check(!WatchLaterPatch.interceptHandleTouch(host, touch));
                        host.direction = android.view.View.LAYOUT_DIRECTION_RTL;
                        WatchLaterPatch.onHostLayout(host);
                        android.view.View.drainPosted();
                        touch.action = android.view.MotionEvent.ACTION_DOWN;
                        touch.x = 1032;
                        check(WatchLaterPatch.interceptHandleTouch(host, touch));
                        check(touch.x == 1032);
                        app.morphe.extension.youtube.shared.PlayerType.maximized = true;
                        check(!WatchLaterPatch.enableLongPressDrag());
                        check(!WatchLaterPatch.interceptHandleTouch(host, touch));
                        app.morphe.extension.youtube.shared.PlayerType.maximized = false;
                        Settings.WATCH_LATER_HIDE_REMOVAL_SNACKBAR.value = true;
                        WatchLaterPatch.clearBrowseId();
                        check(!WatchLaterPatch.enableLongPressDrag());
                        check(WatchLaterPatch.onEditResult(callback("WL", PlaylistEditActionType.ACTION_REMOVE_VIDEO), response) == response);
                        var otherView = new android.view.View();
                        WatchLaterPatch.widenDragHandle(otherView);
                        check(otherView.getLayoutParams().width == 32);
                        WatchLaterPatch.onHostLayout(host);
                        android.view.View.drainPosted();
                        check(host.getTouchDelegate() == null);
                    }
                }
            """,
        ).map { (name, text) -> File(output, "$name.java").apply { writeText(text.trimIndent()) } }
        val compiler = ToolProvider.getSystemJavaCompiler()
        val classpath = listOf(classes, protobuf).joinToString(File.pathSeparator)
        assertEquals(0, compiler.run(null, null, null, "-classpath", classpath, "-d", output.path,
            *sources.map { it.path }.toTypedArray()))
        URLClassLoader(arrayOf(output.toURI().toURL(), classes.toURI().toURL(), protobuf.toURI().toURL()),
            ClassLoader.getPlatformClassLoader()).use {
            it.loadClass("Fixture").getMethod("run").invoke(null)
        }
    }
}
