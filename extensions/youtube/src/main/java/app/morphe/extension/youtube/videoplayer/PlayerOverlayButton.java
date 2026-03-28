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

    private static WeakReference<ViewTreeObserver> buttonObserver = new WeakReference<>(null);
    private static int newButtonCount;

    private static WeakReference<View> chapterTitleContainerRef = new WeakReference<>(null);
    private static int lastChapterMarginEnd = -1;

    /**
     * Resolves the chapter title container from the source button's parent hierarchy,
     * so its end margin can be adjusted dynamically to avoid overlap with overlay buttons.
     */
    private static void updateChapterTitleContainer(ViewGroup sourceButtonViewGroup) {
        if (chapterTitleContainerRef.get() != null) return;

        final int chapterId = ResourceUtils.getIdentifier(
                ResourceType.ID, "time_bar_chapter_title_container");
        if (chapterId == 0) return;

        // Walk up the hierarchy until we find the view or reach the root.
        ViewGroup parent = sourceButtonViewGroup;
        while (parent != null) {
            View found = parent.findViewById(chapterId);
            if (found != null) {
                chapterTitleContainerRef = new WeakReference<>(found);
                return;
            }
            if (parent.getParent() instanceof ViewGroup vg) {
                parent = vg;
            } else {
                break;
            }
        }
    }

    /**
     * Adjusts the end margin of the chapter title container so it doesn't overlap
     * the overlay buttons. Called every pre-draw; skips the layout pass if unchanged.
     */
    private static void updateChapterContainerMargin(View sourceButton, int totalButtons) {
        View chapterContainer = chapterTitleContainerRef.get();
        if (chapterContainer == null) return;

        final int buttonWidth = sourceButton.getWidth();
        if (buttonWidth == 0) return;

        final int reservedWidth = (int) (totalButtons
                * getButtonWidthPercentage(totalButtons)
                * buttonWidth);

        if (lastChapterMarginEnd == reservedWidth) return;
        lastChapterMarginEnd = reservedWidth;

        if (chapterContainer.getLayoutParams() instanceof ViewGroup.MarginLayoutParams lp) {
            lp.setMarginEnd(reservedWidth);
            chapterContainer.setLayoutParams(lp);
        }
    }

    public static void addButton(View sourceButton,
                                 String drawableName,
                                 View.OnClickListener onClickListener,
                                 View.OnLongClickListener onLongClickListener) {
        Utils.verifyOnMainThread();

        if (sourceButton.getParent() instanceof ViewGroup sourceButtonViewGroup) {
            updateChapterTitleContainer(sourceButtonViewGroup);

            ViewTreeObserver observer = sourceButton.getViewTreeObserver();
            if (observer != buttonObserver.get()) {
                newButtonCount = 0;
                buttonObserver = new WeakReference<>(observer);
            }
            final int buttonCount = ++newButtonCount;

            ImageView button = new ImageView(sourceButton.getContext());
            button.setId(View.generateViewId());
            button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            button.setImageResource(ResourceUtils.getIdentifierOrThrow(
                    ResourceType.DRAWABLE, drawableName)
            );
            button.setOnClickListener(onClickListener);
            button.setOnLongClickListener(onLongClickListener);

            observer.addOnPreDrawListener(
                    getOnPreDrawListener(sourceButton, button, buttonCount, button::setBackground)
            );

            sourceButtonViewGroup.addView(button);
        }
    }

    /**
     * Creates an overlay button that displays only a text label.
     *
     * @return The created {@link TextView}, or null if the button could not be added.
     */
    @Nullable
    public static TextView addButtonWithTextOverlay(View sourceButton,
                                                    View.OnClickListener onClickListener,
                                                    View.OnLongClickListener onLongClickListener) {
        Utils.verifyOnMainThread();

        if (!(sourceButton.getParent() instanceof ViewGroup sourceButtonViewGroup)) {
            return null;
        }

        updateChapterTitleContainer(sourceButtonViewGroup);

        ViewTreeObserver observer = sourceButton.getViewTreeObserver();
        if (observer != buttonObserver.get()) {
            newButtonCount = 0;
            buttonObserver = new WeakReference<>(observer);
        }
        final int buttonCount = ++newButtonCount;

        // TextView itself is the tappable surface.
        TextView textOverlay = new TextView(sourceButton.getContext());
        textOverlay.setId(View.generateViewId());
        textOverlay.setGravity(Gravity.CENTER);
        textOverlay.setTextSize(14);
        textOverlay.setTextColor(0xFFFFFFFF);
        textOverlay.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        textOverlay.setOnClickListener(onClickListener);
        textOverlay.setOnLongClickListener(onLongClickListener);

        observer.addOnPreDrawListener(
                getOnPreDrawListener(sourceButton, textOverlay, buttonCount, textOverlay::setBackground)
        );

        sourceButtonViewGroup.addView(textOverlay);

        return textOverlay;
    }

    private interface SetViewBackgroundInterface {
        void setBackground(Drawable drawable);
    }

    private static ViewTreeObserver.OnPreDrawListener getOnPreDrawListener(
            View source, View button, int buttonCount, SetViewBackgroundInterface setBackground) {
        return new ViewTreeObserver.OnPreDrawListener() {
            // Track the ConstantState of the source background to detect real drawable changes.
            Drawable.ConstantState sourceBackgroundSnapshot;

            @Override
            public boolean onPreDraw() {
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
                        - (buttonCount * (getButtonWidthPercentage(newButtonCount) * source.getWidth())));
                if (button.getX() != xOffset) {
                    button.setX(xOffset);
                }

                final float positionY = source.getY();
                if (button.getY() != positionY) {
                    button.setY(positionY);
                }

                updateChapterContainerMargin(source, newButtonCount);

                return true;
            }
        };
    }
}