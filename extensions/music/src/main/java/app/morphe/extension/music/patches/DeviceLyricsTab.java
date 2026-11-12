/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4722
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.extension.music.patches;

import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.protobuf.CodedOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

/** Supplies the tab missing from native sideloaded watch-next responses. */
public final class DeviceLyricsTab {
    private static final int TAB_RENDERER_FIELD = 58174010;
    private static final int TITLE_FIELD = 2;
    private static final int IDENTIFIER_FIELD = 11;
    private static final String IDENTIFIER = "morphe_device_lyrics";
    private static final java.util.WeakHashMap<ViewGroup, Object> localTabs = new java.util.WeakHashMap<>();

    private DeviceLyricsTab() { }

    // TabRenderer: title (2), identifier (11). Deliberately omit endpoint (1),
    // selected (3), content (4), and disabled (20). Device files have no native
    // lyrics provider. The page belongs to the extension, not DownloadsLyricsPageProvider.
    @Nullable
    static byte[] serializeTab(boolean enabled, String title) throws IOException {
        if (!enabled) return null;
        ByteArrayOutputStream renderer = new ByteArrayOutputStream();
        CodedOutputStream output = CodedOutputStream.newInstance(renderer);
        output.writeString(TITLE_FIELD, title);
        output.writeString(IDENTIFIER_FIELD, IDENTIFIER);
        output.flush();
        ByteArrayOutputStream tab = new ByteArrayOutputStream();
        output = CodedOutputStream.newInstance(tab);
        output.writeByteArray(TAB_RENDERER_FIELD, renderer.toByteArray());
        output.flush();
        return tab.toByteArray();
    }

    /** Injection point, after Songs is added in either sideloaded response path. */
    public static void onDeviceTabs(Object builder, Object prototype) {
        if (!Settings.LYRICS_ENABLED.get()) return;
        try {
            final int titleId = ResourceUtils.getIdentifier(ResourceType.STRING, "lyrics_tab_title");
            byte[] bytes = serializeTab(true, Utils.getContext().getString(titleId));
            appendTab(builder, parseTab(prototype, bytes));
        } catch (Exception ex) {
            Logger.printException(() -> "onDeviceTabs failure", ex);
        }
    }

    /** Injection point in native TabbedView.addTab, before the pager owns the page. */
    @Nullable
    public static View onTabContent(ViewGroup tabs, @Nullable View content, Object tab) {
        if (!Settings.LYRICS_ENABLED.get()) return content;
        try {
            if (!IDENTIFIER.equals(new ProtoMessage(serializeRenderer(tab)).stringField(IDENTIFIER_FIELD))) {
                return content;
            }
            localTabs.put(tabs, tab);
            FrameLayout page = new FrameLayout(tabs.getContext());
            TextView empty = new TextView(tabs.getContext());
            empty.setText(ResourceUtils.getIdentifier(ResourceType.STRING, "morphe_music_lyrics_device_unavailable"));
            empty.setTextColor(Color.WHITE);
            empty.setGravity(Gravity.CENTER);
            page.addView(empty, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            // Reuse the existing installer's selected/settled-page detection and lifecycle.
            page.setId(ResourceUtils.getIdentifier(ResourceType.ID, "elements_component_holder"));
            return page;
        } catch (Exception ex) {
            Logger.printException(() -> "onTabContent failure", ex);
            return content;
        }
    }

    public static void onDisabled() {
        try {
            for (var entry : localTabs.entrySet()) removeTab(entry.getKey(), entry.getValue());
            localTabs.clear();
        } catch (Exception ex) {
            Logger.printException(() -> "removeDeviceLyricsTab failure", ex);
        }
    }

    // Filled with direct app calls by the patch. No reflection or obfuscated names.
    private static Object parseTab(Object prototype, byte[] bytes) { throw new IllegalStateException("Unpatched lyrics parser"); }
    private static void appendTab(Object builder, Object tab) { throw new IllegalStateException("Unpatched lyrics builder"); }
    private static byte[] serializeRenderer(Object tab) { throw new IllegalStateException("Unpatched lyrics renderer"); }
    private static void removeTab(ViewGroup tabs, Object tab) { throw new IllegalStateException("Unpatched lyrics removal"); }
}
