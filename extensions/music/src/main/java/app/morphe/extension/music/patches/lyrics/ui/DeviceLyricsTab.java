/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4722
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.content.Context;
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
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.music.patches.ProtoMessage;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

/**
 * Lyrics tab for device files.
 *
 * <p>The app builds the watch next response of a device file itself, with only a Songs tab.
 * This adds a lyrics tab to it, with no endpoint since the app has no lyrics for device files.
 * Its page only holds the third-party lyrics panel, which {@link LyricsPanelInstaller} installs
 * the same way as in the lyrics tab of other tracks.
 */
@SuppressWarnings("unused")
public final class DeviceLyricsTab {

    /** Field of the tab renderer in a tab of the watch next response. */
    private static final int TAB_RENDERER_FIELD = 58174010;

    private static final int TAB_TITLE_FIELD = 2;
    private static final int TAB_IDENTIFIER_FIELD = 11;

    /** Identifier of the added tab, which no tab of the app has. */
    private static final String TAB_IDENTIFIER = "morphe_device_lyrics";

    /**
     * Tab controllers showing the added tab, with the tab model of it.
     */
    private static final Map<Object, Object> addedTabs = new WeakHashMap<>();

    private DeviceLyricsTab() {
    }

    static byte[] createTabRenderer(String title) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream output = CodedOutputStream.newInstance(bytes);
        output.writeString(TAB_TITLE_FIELD, title);
        output.writeString(TAB_IDENTIFIER_FIELD, TAB_IDENTIFIER);
        output.flush();
        return bytes.toByteArray();
    }

    static byte[] createTab(String title) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream output = CodedOutputStream.newInstance(bytes);
        output.writeByteArray(TAB_RENDERER_FIELD, createTabRenderer(title));
        output.flush();
        return bytes.toByteArray();
    }

    static boolean isAddedTabRenderer(byte[] tabRenderer) {
        return TAB_IDENTIFIER.equals(new ProtoMessage(tabRenderer).stringField(TAB_IDENTIFIER_FIELD));
    }

    /**
     * Injection point. Called after the Songs tab is added to the watch next response
     * of a device file.
     */
    @SuppressWarnings("unused")
    public static void addTab(Object tabsBuilder) {
        if (!Settings.LYRICS_ENABLED.get()) {
            return;
        }
        try {
            String title = LyricsPanelInstaller.lyricsTitle(Utils.getContext());
            if (title != null) {
                appendTab(tabsBuilder, parseTab(defaultTab(), createTab(title)));
            }
        } catch (Exception ex) {
            Logger.printException(() -> "addTab failure", ex);
        }
    }

    /**
     * Injection point. Called for each tab added to a tabbed view.
     *
     * @param page Page of the tab.
     * @return Page to show for the tab.
     */
    @Nullable
    @SuppressWarnings("unused")
    public static View onAddTab(ViewGroup tabbedView, Object tabController, Object tab, @Nullable View page) {
        if (!Settings.LYRICS_ENABLED.get()) {
            return page;
        }
        try {
            byte[] tabRenderer = serializeTabRenderer(tab);
            if (tabRenderer == null || !isAddedTabRenderer(tabRenderer)) {
                return page;
            }
            addedTabs.put(tabController, tab);
            return createPage(tabbedView.getContext());
        } catch (Exception ex) {
            Logger.printException(() -> "onAddTab failure", ex);
            return page;
        }
    }

    /**
     * The page shows why there are no lyrics while the lyrics panel finds none.
     * The panel is installed over it like over the built-in lyrics page.
     */
    private static View createPage(Context context) {
        FrameLayout page = new FrameLayout(context);
        page.setId(LyricsPanelInstaller.TAB_CONTENT_ID);

        TextView unavailable = new TextView(context);
        unavailable.setText(ResourceUtils.getIdentifier(ResourceType.STRING,
                "morphe_music_lyrics_device_unavailable"));
        unavailable.setTextColor(Color.WHITE);
        unavailable.setGravity(Gravity.CENTER);
        page.addView(unavailable, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return page;
    }

    /**
     * Removes the added tabs after third-party lyrics are turned off.
     */
    static void removeTabs() {
        for (Map.Entry<Object, Object> entry : addedTabs.entrySet()) {
            removeTab(entry.getKey(), entry.getValue());
        }
        //noinspection RedundantOperationOnEmptyContainer
        addedTabs.clear();
    }

    // The methods below are replaced during patching with calls to the app.

    private static Object defaultTab() {
        throw new UnsupportedOperationException();
    }

    private static Object parseTab(Object defaultTab, byte[] tab) {
        throw new UnsupportedOperationException();
    }

    private static void appendTab(Object tabsBuilder, Object tab) {
        throw new UnsupportedOperationException();
    }

    /**
     * @return The tab renderer of the tab model, or null when it was not built from one.
     */
    @Nullable
    private static byte[] serializeTabRenderer(Object tab) {
        throw new UnsupportedOperationException();
    }

    private static void removeTab(Object tabController, Object tab) {
        throw new UnsupportedOperationException();
    }
}
