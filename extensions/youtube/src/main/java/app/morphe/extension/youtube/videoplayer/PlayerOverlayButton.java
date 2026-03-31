/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.youtube.videoplayer;

import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;

public class PlayerOverlayButton {

    public static final boolean RESTORE_OLD_PLAYER_BUTTONS = Settings.RESTORE_OLD_PLAYER_BUTTONS.get();

    /**
     * Returns the button width percentage based on the total number of buttons,
     * so buttons don't overlap the video time bar.
     */
    private static float getButtonWidthPercentage(int totalButtons) {
        return switch (totalButtons) {
            case 2 -> 0.95f;
            case 3 -> 0.90f;
            case 4 -> 0.85f;
            default -> 1.0f;
        };
    }

    /**
     * Tracks a single container view whose end margin must be kept clear of overlay buttons.
     * <p>
     * Call {@link #updateContainerRef} once when a button is first added to locate the view, then call
     * {@link #updateMargin} on every pre-draw pass to keep the margin in sync with the
     * current button count and width.
     */
    private static final class MarginAdjustableContainer {
        private final String resourceName;
        private WeakReference<View> containerRef = new WeakReference<>(null);
        private int lastMarginEnd = -1;

        MarginAdjustableContainer(String resourceName) {
            this.resourceName = resourceName;
        }

        /**
         * Walks up the view hierarchy from {@code sourceButtonViewGroup} to find the
         * target view by resource name. No-op if already resolved or the ID is missing.
         */
        void updateContainerRef(ViewGroup sourceButtonViewGroup) {
            if (containerRef.get() != null) return;

            final int id = ResourceUtils.getIdentifier(ResourceType.ID, resourceName);
            if (id == 0) return;

            ViewGroup parent = sourceButtonViewGroup;
            while (parent.getParent() instanceof ViewGroup vg) {
                parent = vg;
                View found = parent.findViewById(id);
                if (found != null) {
                    containerRef = new WeakReference<>(found);
                    return;
                }
            }
        }

        /**
         * Adjusts the container's end margin to reserve space for {@code totalButtons}
         * overlay buttons of the same width as {@code sourceButton}.
         * Skips the layout pass when the computed value hasn't changed.
         */
        void updateMargin(View sourceButton, int totalButtons) {
            View container = containerRef.get();
            if (container == null) return;

            final int buttonWidth = sourceButton.getWidth();
            if (buttonWidth == 0) return;

            final int reservedWidth = (int) (totalButtons
                    * getButtonWidthPercentage(totalButtons)
                    * buttonWidth);

            if (lastMarginEnd == reservedWidth) return;
            lastMarginEnd = reservedWidth;

            if (container.getLayoutParams() instanceof ViewGroup.MarginLayoutParams lp) {
                lp.setMarginEnd(reservedWidth);
                container.setLayoutParams(lp);
            }
        }
    }

    private static WeakReference<ViewTreeObserver> buttonObserver = new WeakReference<>(null);
    private static int totalButtonCount;

    /** Bottom bar: chapter chip container ({@code time_bar_chapter_title_container}). */
    private static final MarginAdjustableContainer chapterTitleContainer =
            new MarginAdjustableContainer("time_bar_chapter_title_container");

    /** Top bar: video title container ({@code player_video_heading}). */
    private static final MarginAdjustableContainer videoHeadingContainer =
            new MarginAdjustableContainer("player_video_heading");

    private static void resolveContainers(ViewGroup sourceButtonViewGroup) {
        // Locate each container once; subsequent calls are no-ops.
        chapterTitleContainer.updateContainerRef(sourceButtonViewGroup);
        videoHeadingContainer.updateContainerRef(sourceButtonViewGroup);
    }

    private static void updateContainerMargins(View sourceButton, int totalButtons) {
        // Keep both containers' end margins in sync with the current button count.
        chapterTitleContainer.updateMargin(sourceButton, totalButtons);
        videoHeadingContainer.updateMargin(sourceButton, totalButtons);
    }

    private static ViewTreeObserver updateViewObserver(View button) {
        Utils.verifyOnMainThread();

        ViewTreeObserver observer = button.getViewTreeObserver();
        if (observer != buttonObserver.get()) {
            totalButtonCount = 0;
            buttonObserver = new WeakReference<>(observer);
        }
        return observer;
    }

    /**
     * Adds an icon button to the player overlay, positioned to the left of {@code sourceButton}.
     * <p>
     * On first call, resolves the chapter title and video heading containers so their end margins
     * can be kept clear of overlay buttons on every subsequent pre-draw pass.
     *
     * @param sourceButton        the existing player button used as a position and style anchor.
     * @param drawableName        resource name of the drawable to display inside the button.
     * @param onClickListener     invoked when the button is tapped.
     * @param onLongClickListener invoked when the button is long-pressed.
     */
    public static void addButton(View sourceButton,
                                 String drawableName,
                                 View.OnClickListener onClickListener,
                                 View.OnLongClickListener onLongClickListener) {
        if (!(sourceButton.getParent() instanceof ViewGroup sourceButtonViewGroup)) {
            Logger.printException(() -> "Unknown button parent: " + sourceButton.getParent());
            return;
        }

        resolveContainers(sourceButtonViewGroup);

        ImageView button = new ImageView(sourceButton.getContext());
        button.setId(View.generateViewId());
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setImageResource(ResourceUtils.getIdentifierOrThrow(
                ResourceType.DRAWABLE, drawableName)
        );
        button.setOnClickListener(onClickListener);
        button.setOnLongClickListener(onLongClickListener);

        updateViewObserver(sourceButton).addOnPreDrawListener(
                getOnPreDrawListener(sourceButton, button, button::setBackground)
        );

        sourceButtonViewGroup.addView(button);
    }

    /**
     * Adds a text-only button to the player overlay, positioned to the left of {@code sourceButton}.
     * <p>
     * On first call, resolves the chapter title and video heading containers so their end margins
     * can be kept clear of overlay buttons on every subsequent pre-draw pass.
     *
     * @param sourceButton        the existing player button used as a position and style anchor.
     * @param onClickListener     invoked when the button is tapped.
     * @param onLongClickListener invoked when the button is long-pressed.
     * @return the created {@link TextView}, or {@code null} if the button could not be added.
     */
    @Nullable
    public static TextView addButtonWithTextOverlay(View sourceButton,
                                                    View.OnClickListener onClickListener,
                                                    View.OnLongClickListener onLongClickListener) {
        if (!(sourceButton.getParent() instanceof ViewGroup sourceButtonViewGroup)) {
            Logger.printException(() -> "Unknown button parent: " + sourceButton.getParent());
            return null;
        }

        resolveContainers(sourceButtonViewGroup);

        // TextView itself is the tappable surface.
        TextView textOverlay = new TextView(sourceButton.getContext());
        textOverlay.setId(View.generateViewId());
        textOverlay.setGravity(Gravity.CENTER);
        textOverlay.setTextSize(14);
        textOverlay.setTextColor(0xFFFFFFFF);
        textOverlay.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        textOverlay.setOnClickListener(onClickListener);
        textOverlay.setOnLongClickListener(onLongClickListener);
        sourceButtonViewGroup.addView(textOverlay);

        updateViewObserver(sourceButton).addOnPreDrawListener(
                getOnPreDrawListener(sourceButton, textOverlay, textOverlay::setBackground)
        );

        return textOverlay;
    }

    private interface SetViewBackgroundInterface {
        void setBackground(Drawable drawable);
    }

    private static ViewTreeObserver.OnPreDrawListener getOnPreDrawListener(
            View sourceView, View newButton, SetViewBackgroundInterface setBackground) {
        WeakReference<View> sourceRef = new WeakReference<>(sourceView);
        WeakReference<View> newButtonRef = new WeakReference<>(newButton);

        final int buttonCount = ++totalButtonCount;

        return new ViewTreeObserver.OnPreDrawListener() {
            // Track the ConstantState of the source background to detect real drawable changes.
            Drawable.ConstantState sourceBackgroundSnapshot;

            @Override
            public boolean onPreDraw() {
                View source = sourceRef.get();
                View button = newButtonRef.get();
                if (source == null || button == null) {
                    Logger.printException(() -> "Player buttons is null, source: " + source
                            + " button: " + button);
                    return true;
                }

                final int sourcePaddingLeft = source.getPaddingLeft();
                final int sourcePaddingTop = source.getPaddingTop();
                final int sourcePaddingRight = source.getPaddingRight();
                final int sourcePaddingBottom = source.getPaddingBottom();

                if (!(sourcePaddingLeft == button.getPaddingLeft()
                        && sourcePaddingTop == button.getPaddingTop()
                        && sourcePaddingRight == button.getPaddingRight()
                        && sourcePaddingBottom == button.getPaddingBottom())
                ) {
                    button.setLayoutParams(source.getLayoutParams());
                    button.setPadding(
                            sourcePaddingLeft,
                            sourcePaddingTop,
                            sourcePaddingRight,
                            sourcePaddingBottom
                    );
                }

                Drawable sourceButtonBackground = source.getBackground();
                Drawable.ConstantState newConstantState = sourceButtonBackground != null
                        ? sourceButtonBackground.getConstantState()
                        : null;
                if (sourceBackgroundSnapshot != newConstantState) {
                    // Use newDrawable() instead of mutate() so each button gets a
                    // fully independent Drawable instance with its own hotspot/ripple
                    // state. mutate() only isolates color/alpha state but still shares
                    // the ConstantState hotspot, causing the ripple to fire on every
                    // button that references the same source drawable simultaneously.
                    Drawable newBackground = newConstantState != null
                            ? newConstantState.newDrawable().mutate()
                            : sourceButtonBackground;
                    setBackground.setBackground(newBackground);
                    sourceBackgroundSnapshot = newConstantState;
                }

                final float sourceButtonAlpha = source.getAlpha();
                if (button.getAlpha() != sourceButtonAlpha) {
                    button.setAlpha(sourceButtonAlpha);
                }

                final int sourceButtonVisibility = source.getVisibility();
                if (button.getVisibility() != sourceButtonVisibility) {
                    button.setVisibility(sourceButtonVisibility);
                }

                final float xOffset = (int) (source.getX()
                        - (buttonCount * (getButtonWidthPercentage(totalButtonCount) * source.getWidth())));
                if (button.getX() != xOffset) {
                    button.setX(xOffset);
                }

                final float positionY = source.getY();
                if (button.getY() != positionY) {
                    button.setY(positionY);
                }

                updateContainerMargins(source, totalButtonCount);

                return true;
            }
        };
    }
}
