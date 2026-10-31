/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4267
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.layout.lyrics

import java.io.File
import java.net.URLClassLoader
import javax.tools.ToolProvider
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

/** Exercises the actual observer against a small view tree without an emulator dependency. */
class LyricsTabbedPanelObserverTest {
    @Test
    fun `localized tab selection content replacement and detachment follow the sheet lifecycle`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "extensions/music/src/main/java").isDirectory }
        val output = createTempDirectory("lyrics-tabs").toFile()
        val sources = mapOf(
            "Nullable" to "package androidx.annotation; public @interface Nullable {}",
            "Context" to "package android.content; public class Context {}",
            "AttributeSet" to "package android.util; public interface AttributeSet {}",
            "Rect" to "package android.graphics; public class Rect { public int size; public int width() { return size; } }",
            "ViewTreeObserver" to """
                package android.view;
                public class ViewTreeObserver {
                    public interface OnPreDrawListener { boolean onPreDraw(); }
                    public OnPreDrawListener listener;
                    public boolean isAlive() { return true; }
                    public void addOnPreDrawListener(OnPreDrawListener value) { listener = value; }
                    public void removeOnPreDrawListener(OnPreDrawListener value) { if (listener == value) listener = null; }
                }
            """,
            "View" to """
                package android.view;
                import android.content.Context;
                import android.util.AttributeSet;
                import android.graphics.Rect;
                public class View {
                    public static final int VISIBLE = 0;
                    public int id, visibility, visibleWidth = 100;
                    public boolean selected, attached = true, onScreen = true;
                    public CharSequence description;
                    public ViewGroup parent;
                    private final ViewTreeObserver observer = new ViewTreeObserver();
                    public View(Context context, AttributeSet attrs) {}
                    protected void onAttachedToWindow() {}
                    protected void onDetachedFromWindow() {}
                    public Object getParent() { return parent; }
                    public Context getContext() { return new Context(); }
                    public ViewTreeObserver getViewTreeObserver() { return observer; }
                    public int getId() { return id; }
                    public int getWidth() { return 100; }
                    public int getVisibility() { return visibility; }
                    public boolean isSelected() { return selected; }
                    public boolean isAttachedToWindow() { return attached; }
                    public boolean isShown() { return visibility == VISIBLE && (parent == null || parent.isShown()); }
                    public boolean getGlobalVisibleRect(Rect rect) { rect.size = visibleWidth; return onScreen && isShown(); }
                    public CharSequence getContentDescription() { return description; }
                    public View findViewById(int target) { return id == target ? this : null; }
                }
            """,
            "ViewGroup" to """
                package android.view;
                public class ViewGroup extends View {
                    public final java.util.List<View> children = new java.util.ArrayList<>();
                    public ViewGroup() { super(null, null); }
                    public void add(View view) { children.add(view); view.parent = this; }
                    public int getChildCount() { return children.size(); }
                    public View getChildAt(int index) { return children.get(index); }
                    public View findViewById(int target) {
                        if (id == target) return this;
                        for (View child : children) { View found = child.findViewById(target); if (found != null) return found; }
                        return null;
                    }
                }
            """,
            "TextView" to """
                package android.widget;
                public class TextView extends android.view.View {
                    public CharSequence text;
                    public TextView() { super(null, null); }
                    public CharSequence getText() { return text; }
                }
            """,
            "ResourceType" to "package app.morphe.extension.shared; public enum ResourceType { ID }",
            "ResourceUtils" to """
                package app.morphe.extension.shared;
                public class ResourceUtils {
                    public static int getIdentifier(ResourceType type, String name) {
                        return name.equals("bottom_sheet_tabbed_view") ? 1 : 2;
                    }
                }
            """,
            "Logger" to """
                package app.morphe.extension.shared;
                public class Logger {
                    public static void printException(java.util.function.Supplier<String> message, Exception error) {
                        throw new AssertionError(message.get(), error);
                    }
                }
            """,
            "LyricsPanelInstaller" to """
                package app.morphe.extension.music.patches.lyrics.ui;
                public class LyricsPanelInstaller {
                    static String title;
                    static int calls;
                    static boolean selected;
                    static android.view.ViewGroup content;
                    static String lyricsTitle(android.content.Context context) { return title; }
                    static void onTabbedLyricsPanelChanged(android.view.ViewGroup value, boolean active) {
                        calls++; content = value; selected = active;
                    }
                }
            """,
            "TabbedFixture" to """
                package app.morphe.extension.music.patches.lyrics.ui;
                import android.view.*;
                import android.widget.TextView;
                public class TabbedFixture {
                    static void check(boolean value) { if (!value) throw new AssertionError(); }
                    public static void verify() {
                        for (String title : new String[]{"Paroles", "Lyrics", "歌詞"}) {
                            LyricsPanelInstaller.title = title;
                            LyricsPanelInstaller.calls = 0;
                            ViewGroup sheet = new ViewGroup(), tabs = new ViewGroup(), strip = new ViewGroup();
                            tabs.id = 1; sheet.add(tabs); tabs.add(strip);
                            TextView label = new TextView(); label.text = title.toUpperCase(java.util.Locale.ROOT);
                            strip.add(label);
                            // A selected label outside the tab strip must not identify the page as lyrics.
                            TextView decoy = new TextView(); decoy.text = title; decoy.selected = true; tabs.add(decoy);
                            LyricsTabbedPanelObserver observer = new LyricsTabbedPanelObserver(null, null);
                            sheet.add(observer); observer.onAttachedToWindow();
                            check(observer.getViewTreeObserver().listener == observer);
                            observer.onPreDraw(); check(LyricsPanelInstaller.calls == 0);
                            label.selected = true;
                            observer.onPreDraw(); check(LyricsPanelInstaller.selected && LyricsPanelInstaller.content == null);
                            ViewGroup hidden = new ViewGroup(); hidden.id = 2; hidden.onScreen = false; tabs.add(hidden);
                            ViewGroup outgoing = new ViewGroup(); outgoing.id = 2; outgoing.visibleWidth = 50; tabs.add(outgoing);
                            ViewGroup content = new ViewGroup(); content.id = 2; tabs.add(content);
                            observer.onPreDraw(); check(LyricsPanelInstaller.content == content);
                            int calls = LyricsPanelInstaller.calls;
                            observer.onPreDraw(); check(LyricsPanelInstaller.calls == calls); // No per-frame reinstall.
                            label.selected = false;
                            observer.onPreDraw(); check(!LyricsPanelInstaller.selected);
                            label.selected = true; observer.onPreDraw(); check(LyricsPanelInstaller.selected);
                            content.attached = false; content.onScreen = false;
                            ViewGroup replacement = new ViewGroup(); replacement.id = 2; tabs.add(replacement);
                            observer.onPreDraw(); check(LyricsPanelInstaller.content == replacement);
                            tabs.visibility = 8; observer.onPreDraw(); check(!LyricsPanelInstaller.selected);
                            tabs.visibility = 0; observer.onPreDraw(); check(LyricsPanelInstaller.selected);
                            observer.onDetachedFromWindow(); check(!LyricsPanelInstaller.selected);
                            check(observer.getViewTreeObserver().listener == null);
                        }
                    }
                }
            """
        ).map { (name, source) -> File(output, "$name.java").apply { writeText(source.trimIndent()) } }
        try {
            val observer = File(root,
                "extensions/music/src/main/java/app/morphe/extension/music/patches/lyrics/ui/LyricsTabbedPanelObserver.java")
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "17", "-encoding", "UTF-8", "-d", output.path,
                observer.path, *sources.map { it.path }.toTypedArray()))
            URLClassLoader(arrayOf(output.toURI().toURL()), null).use { loader ->
                loader.loadClass("app.morphe.extension.music.patches.lyrics.ui.TabbedFixture")
                    .getMethod("verify").invoke(null)
            }
        } finally {
            output.deleteRecursively()
        }
    }
}
