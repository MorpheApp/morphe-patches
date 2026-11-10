/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4338
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.ui.glass;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import app.morphe.extension.shared.ui.Dim;

/**
 * The layout of the glass navigation bar, shared by the apps: which buttons fit in the capsule,
 * how wide they are and how tall the capsule is. Also used by the preview in the settings.
 */
public final class GlassNavigationBarLayout {

    /**
     * The least width of a button, the size of a touch target.
     */
    private static final int MIN_BUTTON_WIDTH = Dim.dp48;

    /**
     * The least room between the content of two buttons. With less room, the buttons that do not fit
     * in the width of the bar are hidden.
     */
    private static final int MIN_BUTTON_SPACING = Dim.dp8;

    /**
     * The least room above and below the content of the buttons in the capsule.
     */
    private static final int MIN_VERTICAL_PADDING = Dim.dp4;

    /**
     * The usual content width (the median) and the content height of the buttons of the last bar, and the height
     * the app gives the bar, so the preview in the settings shows the buttons as they are.
     */
    public static volatile int lastContentWidth;
    public static volatile int lastContentHeight;
    public static volatile int lastBaseHeight;
    /**
     * The width the app gives each button at least, such as on tablets, where the app gives the buttons
     * a minimum width and centers them, instead of sharing the width of the bar between them.
     */
    public static volatile int lastMinButtonWidth;

    /**
     * The buttons of the last bar, also the buttons hidden because they do not fit, so the preview in the settings
     * shows their icons and labels.
     */
    public static volatile List<WeakReference<View>> lastButtons = Collections.emptyList();

    /**
     * Keeps the buttons of the last bar, if they changed.
     */
    public static void setLastButtons(List<View> buttons) {
        List<WeakReference<View>> seenButtons = lastButtons;
        boolean buttonsChanged = seenButtons.size() != buttons.size();
        for (int i = 0, count = buttons.size(); i < count && !buttonsChanged; i++) {
            buttonsChanged = seenButtons.get(i).get() != buttons.get(i);
        }
        if (buttonsChanged) {
            List<WeakReference<View>> references = new ArrayList<>(buttons.size());
            for (View button : buttons) {
                references.add(new WeakReference<>(button));
            }
            lastButtons = references;
        }
    }

    private GlassNavigationBarLayout() {
    }

    /**
     * The buttons that fit in the capsule and their width.
     *
     * @param barWidth     The width of the navigation bar.
     * @param buttons      The number of buttons.
     * @param contentWidth The usual width of the content of the buttons (the icon or the label), the median,
     *                     so a long label that the app cuts does not make every button wider.
     * @param appMinWidth  The width the app gives each button at least, see {@link #lastMinButtonWidth}.
     * @param widthPercent The most width of the capsule, in percent of the width of the bar.
     * @param spacing      The room between the content of two buttons.
     * @param margin       The least room between the ends of the capsule and the edges of the screen.
     * @return The number of buttons shown, and the width of each button.
     */
    public static int[] getButtonsLayout(int barWidth, int buttons, int contentWidth, int appMinWidth,
                                         int widthPercent, int spacing, int margin) {
        final int maxWidth = Math.max(0, (barWidth - 2 * margin) * widthPercent / 100);
        final int minWidth = Math.max(MIN_BUTTON_WIDTH, appMinWidth);
        final int minButtonWidth = Math.max(minWidth, contentWidth + MIN_BUTTON_SPACING);
        final int shown = Math.max(1, Math.min(buttons, maxWidth / minButtonWidth));
        // With less room, the buttons share the most width of the capsule, but never less than the app allows.
        final int buttonWidth = Math.min(Math.max(minWidth, contentWidth + spacing), maxWidth / shown);
        return new int[]{shown, buttonWidth};
    }

    /**
     * The height of the capsule.
     *
     * @param baseHeight    The height the app gives the bar, without the room below the buttons.
     * @param contentHeight The height of the content of the buttons, the icon and the label.
     * @param heightPercent The height of the capsule, in percent of the height the app gives the bar,
     *                      or of the height the buttons need if they need more, such as with a larger font.
     */
    public static int getCapsuleHeight(int baseHeight, int contentHeight, int heightPercent) {
        final int minHeight = contentHeight + 2 * MIN_VERTICAL_PADDING;
        return Math.max(minHeight, Math.round(Math.max(baseHeight, minHeight) * heightPercent / 100f));
    }

    /**
     * @return If the navigation bar is the bar along the bottom of the screen,
     *         not a navigation rail along the side.
     */
    public static boolean isHorizontalBottomBar(View navigationBar) {
        final int width = navigationBar.getWidth();
        final int height = navigationBar.getHeight();
        return width > 0 && height > 0 && width > 2 * height;
    }

    /**
     * The content of the buttons of a navigation bar.
     */
    public static final class Buttons {
        /**
         * Every button, also the buttons hidden because they do not fit,
         * so hiding them does not change how many buttons fit.
         */
        public final List<View> views;
        /**
         * The usual content width of the buttons, the median.
         */
        public final int contentWidth;
        /**
         * The width the app gives each button at least. The buttons are never narrower than the app allows,
         * or they are wider than the capsule, which then cuts the first and the last button.
         */
        public final int minWidth;
        /**
         * The top and the bottom of the content of the buttons shown, in the coordinates of the buttons.
         * The bottom is not more than the top if no button is laid out.
         */
        public final int contentTop;
        public final int contentBottom;

        Buttons(List<View> views, int contentWidth, int minWidth, int contentTop, int contentBottom) {
            this.views = views;
            this.contentWidth = contentWidth;
            this.minWidth = minWidth;
            this.contentTop = contentTop;
            this.contentBottom = contentBottom;
        }

        public int contentHeight() {
            return contentBottom - contentTop;
        }

        public boolean isValid() {
            return contentWidth > 0 && contentBottom > contentTop;
        }
    }

    /**
     * Measures the content (the icon and the label) of the buttons. The size of the buttons comes from their
     * content, so it follows the density, the font size and the language.
     *
     * @param tabs        The parent of the buttons.
     * @param iconId      The id of the view that holds the icon of a button, or 0 if not known.
     * @param labelId     The id of the label of a button, or 0 if not known.
     * @param hidden      The buttons hidden because they do not fit.
     */
    public static Buttons measureButtons(ViewGroup tabs, int iconId, int labelId, Set<View> hidden) {
        final int count = tabs.getChildCount();
        List<View> buttons = new ArrayList<>(count);
        int[] contentWidths = new int[count];
        int contentTop = Integer.MAX_VALUE;
        int contentBottom = 0;
        final boolean idsKnown = iconId != 0 && labelId != 0;

        for (int i = 0; i < count; i++) {
            View button = tabs.getChildAt(i);
            if (button.getVisibility() == View.GONE && !hidden.contains(button)) continue;
            if (!(button instanceof ViewGroup group)) continue;

            final boolean laidOut = button.getVisibility() == View.VISIBLE;
            int buttonContentWidth = 0;
            // The icon and the label are children of the button, or are inside a layout the button holds.
            List<View> contents = new ArrayList<>(2);
            for (int j = 0, contentCount = group.getChildCount(); j < contentCount; j++) {
                View child = group.getChildAt(j);
                final int id = child.getId();
                if (!idsKnown || id == iconId || id == labelId) {
                    contents.add(child);
                }
            }
            if (idsKnown && contents.isEmpty()) {
                View icon = group.findViewById(iconId);
                View label = group.findViewById(labelId);
                if (icon != null) contents.add(icon);
                if (label != null) contents.add(label);
            }

            for (View child : contents) {
                if (!isShownIn(child, button)) continue;

                // The whole label, also if the button is narrower and the label is cut.
                buttonContentWidth = Math.max(buttonContentWidth, child instanceof TextView label
                        ? (int) Math.ceil(label.getPaint().measureText(label.getText().toString()))
                                + label.getPaddingLeft() + label.getPaddingRight()
                        : child.getWidth());
                if (laidOut && child.getHeight() > 0) {
                    final int top = getTopIn(child, button);
                    contentTop = Math.min(contentTop, top);
                    contentBottom = Math.max(contentBottom, top + child.getHeight());
                }
            }
            contentWidths[buttons.size()] = buttonContentWidth;
            buttons.add(button);
        }

        // A long label, which the app cuts, does not make every button wider.
        Arrays.sort(contentWidths, 0, buttons.size());
        final int contentWidth = buttons.isEmpty() ? 0 : contentWidths[buttons.size() / 2];
        int minWidth = 0;
        for (View button : buttons) {
            minWidth = Math.max(minWidth, button.getMinimumWidth());
        }
        return new Buttons(buttons, contentWidth, minWidth, contentTop, contentBottom);
    }

    /**
     * Hides the buttons that do not fit from the end, but never the selected one,
     * and shows the buttons again that fit, such as after a rotation.
     *
     * @return If a button was hidden or shown.
     */
    public static boolean fitButtons(List<View> buttons, int shown, @Nullable View selected, Set<View> hidden) {
        boolean changed = false;
        final int selectedIndex = buttons.indexOf(selected);
        final int lastShown = selectedIndex >= shown ? shown - 2 : shown - 1;
        for (int i = 0, count = buttons.size(); i < count; i++) {
            View button = buttons.get(i);
            if (i <= lastShown || i == selectedIndex) {
                if (hidden.remove(button)) {
                    button.setVisibility(View.VISIBLE);
                    changed = true;
                }
            } else if (hidden.add(button)) {
                button.setVisibility(View.GONE);
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Shows the buttons again that were hidden because they did not fit.
     */
    public static void showHiddenButtons(Set<View> hidden) {
        for (View button : hidden) {
            button.setVisibility(View.VISIBLE);
        }
        hidden.clear();
    }

    /**
     * @return If the view and its parents up to the ancestor are visible.
     */
    private static boolean isShownIn(View view, View ancestor) {
        while (view != ancestor) {
            if (view.getVisibility() != View.VISIBLE) return false;
            ViewParent parent = view.getParent();
            if (!(parent instanceof View parentView)) return false;
            view = parentView;
        }
        return true;
    }

    /**
     * @return The top of the view in the coordinates of the ancestor.
     */
    private static int getTopIn(View view, View ancestor) {
        int top = 0;
        while (view != ancestor) {
            top += view.getTop();
            ViewParent parent = view.getParent();
            if (!(parent instanceof View parentView)) break;
            view = parentView;
        }
        return top;
    }

    /**
     * @return The left of the view in the coordinates of the ancestor, with the translation,
     *         or NaN if the view or a parent is not visible.
     */
    public static float getLeftIn(View view, View ancestor) {
        float left = 0;
        while (view != ancestor) {
            left += view.getLeft() + view.getTranslationX();
            ViewParent parent = view.getParent();
            if (!(parent instanceof View parentView) || view.getVisibility() != View.VISIBLE) {
                return Float.NaN;
            }
            view = parentView;
        }
        return left;
    }
}
