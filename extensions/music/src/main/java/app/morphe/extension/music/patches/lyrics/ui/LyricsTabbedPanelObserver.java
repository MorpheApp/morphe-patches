/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4267
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;

/** Watches the legacy lyrics tabs in their own bottom-sheet window, not the activity window. */
public final class LyricsTabbedPanelObserver extends View implements ViewTreeObserver.OnPreDrawListener {
    @Nullable
    private ViewGroup tabs;
    @Nullable
    private ViewGroup lyricsContent;
    @Nullable
    private ViewTreeObserver observer;
    private boolean lyricsSelected;
    private int contentId;
    private final Rect visibleRect = new Rect();

    public LyricsTabbedPanelObserver(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!(getParent() instanceof ViewGroup parent)) {
            return;
        }
        int tabsId = ResourceUtils.getIdentifier(ResourceType.ID, "bottom_sheet_tabbed_view");
        contentId = ResourceUtils.getIdentifier(ResourceType.ID, "elements_component_holder");
        if (tabsId == 0 || contentId == 0 || !(parent.findViewById(tabsId) instanceof ViewGroup group)) {
            return;
        }
        tabs = group;
        observer = getViewTreeObserver();
        observer.addOnPreDrawListener(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (observer != null && observer.isAlive()) {
            observer.removeOnPreDrawListener(this);
        }
        if (lyricsContent != null) {
            LyricsPanelInstaller.onTabbedLyricsPanelChanged(lyricsContent, false);
        }
        observer = null;
        tabs = null;
        lyricsContent = null;
        lyricsSelected = false;
        super.onDetachedFromWindow();
    }

    @Override
    public boolean onPreDraw() {
        try {
            if (tabs == null || tabs.getChildCount() == 0) {
                return true;
            }
            String title = LyricsPanelInstaller.lyricsTitle(tabs.getContext());
            // Only inspect the tab strip, never lyric text or the overlay's own controls.
            boolean selected = tabs.isShown() && title != null
                    && hasSelectedLyricsTab(tabs.getChildAt(0), title);
            ViewGroup content = lyricsContent;
            if (selected && (content == null || !content.isAttachedToWindow() || !fillsPage(content))) {
                content = findContent(tabs);
            }
            if (selected != lyricsSelected || content != lyricsContent) {
                lyricsSelected = selected;
                lyricsContent = content;
                LyricsPanelInstaller.onTabbedLyricsPanelChanged(content, selected);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not observe the tabbed lyrics panel", ex);
        }
        return true;
    }

    private static boolean hasSelectedLyricsTab(View view, String title) {
        if (view.getVisibility() != VISIBLE) {
            return false;
        }
        if (view.isSelected() && (title.equalsIgnoreCase(String.valueOf(view.getContentDescription()))
                || view instanceof TextView text && title.equalsIgnoreCase(String.valueOf(text.getText())))) {
            return true;
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasSelectedLyricsTab(group.getChildAt(i), title)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Nullable
    private ViewGroup findContent(View view) {
        if (view.getVisibility() != VISIBLE) {
            return null;
        }
        if (view.getId() == contentId && view instanceof ViewGroup group && fillsPage(view)) {
            return group;
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                ViewGroup found = findContent(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private boolean fillsPage(View view) {
        // Adjacent ViewPager pages can both be visible during a swipe. Wait until one
        // fills the viewport rather than attaching to the outgoing page's container.
        return tabs != null && tabs.getWidth() > 0 && view.getGlobalVisibleRect(visibleRect)
                && visibleRect.width() == tabs.getWidth();
    }
}
