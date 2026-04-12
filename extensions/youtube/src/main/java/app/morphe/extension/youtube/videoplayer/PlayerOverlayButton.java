/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.youtube.videoplayer;

import static app.morphe.extension.youtube.videoplayer.LegacyPlayerControlButton.getTotalUpperButtonCount;

import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.HidePlayerOverlayButtonsPatch;
import app.morphe.extension.youtube.patches.VersionCheckPatch;
import app.morphe.extension.youtube.settings.Settings;

public class PlayerOverlayButton {

    /**
     * Tracks a single container view whose end margin must be kept clear of overlay buttons.
     * <p>
     * Call {@link #updateContainerRef} once when a button is first added to locate the view, then call
     * {@link #updateMargin} on every pre-draw pass to keep the margin in sync with the
     * current button count and width.
     */
    private static class MarginAdjustableContainer {
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
            if (id != 0) {
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

            Logger.printException(() -> "Could not find button overlay: " + resourceName);
        }

        /**
         * Adjusts the container's end margin to reserve space for {@code totalButtons}
         * overlay buttons of the same width as {@code sourceButton}.
         * Skips the layout pass when the computed value hasn't changed.
         */
        void updateMargin(int buttonWidth, int totalButtons) {
            View container = containerRef.get();
            if (container == null) return;

            final int reservedWidth = (int) (totalButtons
                    * getButtonWidthPercentage(totalButtons)
                    * buttonWidth);

            if (lastMarginEnd == reservedWidth) return;
            lastMarginEnd = reservedWidth;

            if (container.getLayoutParams() instanceof ViewGroup.MarginLayoutParams params) {
                if (params.getMarginEnd() == reservedWidth) return;
                params.setMarginEnd(reservedWidth);
                container.setLayoutParams(params);
            }
        }
    }

    private interface SetViewBackgroundInterface {
        void setBackground(Drawable drawable);
    }

    private static class PlayerOverlayButtonController {
        private final WeakReference<View> buttonRef;
        private final SetViewBackgroundInterface setBackground;
        // Track the ConstantState of the source background to detect real drawable changes.
        @Nullable
        private Drawable.ConstantState sourceBackgroundSnapshot;

        private PlayerOverlayButtonController(View newButton, SetViewBackgroundInterface backgroundInterface) {
            buttonRef = new WeakReference<>(newButton);
            setBackground = backgroundInterface;

            newButton.getViewTreeObserver().addOnPreDrawListener(() -> {
                updateLayoutFromSourceButton();
                return true;
            });
        }

        private void updateLayoutFromSourceButton() {
            View source = ytSourceButtonRef.get();
            View button = buttonRef.get();
            if (source == null || button == null) {
                Logger.printException(() -> "Player buttons is null, source: " + source
                        + " button: " + button);
                return;
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
                //noinspection ExtractMethodRecommender
                ViewGroup.LayoutParams layoutParams = source.getLayoutParams();
                if (VersionCheckPatch.IS_21_15_OR_GREATER) {
                    // Fullscreen button has a custom margin layout parameters class
                    // and if used directly causes a broken layout with 21.15+
                    // if quality and speed button are shown. Older app targets
                    // must use the original layut otherwise app crashes with a cast exception.
                    layoutParams = new ViewGroup.MarginLayoutParams(layoutParams);
                }
                button.setLayoutParams(layoutParams);
                button.setPadding(
                        sourcePaddingLeft,
                        sourcePaddingTop,
                        sourcePaddingRight,
                        sourcePaddingBottom
                );
            }

            // Convert from 0 indexing to 1 indexing.
            final int buttonNumber = buttonControllers.indexOf(this) + (HIDE_FULLSCREEN_BUTTON_ENABLED ? 0 : 1);
            final float xOffset = (int) (source.getX()
                    - (buttonNumber * (getButtonWidthPercentage(buttonControllers.size()) * source.getWidth())));
            if (button.getX() != xOffset) {
                button.setX(xOffset);
            }

            float positionY = source.getY();
            if (HIDE_FULLSCREEN_BUTTON_ENABLED) {
                positionY += HidePlayerOverlayButtonsPatch.FULLSCREEN_HIDDEN_Y_OFFSET;
            }
            if (button.getY() != positionY) {
                button.setY(positionY);
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

            updateContainerMargins(source);
        }
    }

    private static final Boolean HIDE_FULLSCREEN_BUTTON_ENABLED = Settings.HIDE_FULLSCREEN_BUTTON.get();

    /** Top bar: video title container */
    private static final MarginAdjustableContainer videoHeadingContainer =
            new MarginAdjustableContainer("player_video_heading");

    /** Bottom bar: chapter chip container */
    private static final MarginAdjustableContainer chapterTitleContainer =
            new MarginAdjustableContainer("time_bar_chapter_title_container");

    private static WeakReference<View> ytSourceButtonRef = new WeakReference<>(null);
    private static final List<PlayerOverlayButtonController> buttonControllers = new ArrayList<>();

    /**
     * Returns the button width percentage based on the total number of buttons,
     * so buttons don't overlap the video time bar.
     */
    private static float getButtonWidthPercentage(int totalButtons) {
        return switch (totalButtons) {
            case 2 -> 0.90f;
            case 3 -> 0.80f;
            case 4 -> 0.70f;
            default -> 1.0f;
        };
    }

    private static void updateContainerMargins(View lowerButtonSource) {
        // Keep both containers' end margins in sync with the current button count.
        final int totalLowerButtons = buttonControllers.size() - (HIDE_FULLSCREEN_BUTTON_ENABLED
                ? 1
                : 0);
        chapterTitleContainer.updateMargin(lowerButtonSource.getWidth(), totalLowerButtons);
        videoHeadingContainer.updateMargin(LegacyPlayerControlButton.buttonWidth, getTotalUpperButtonCount());
    }


    @Nullable
    private static ViewGroup updateRefsFromSourceButton(View sourceButton) {
        Utils.verifyOnMainThread();

        if (!(sourceButton.getParent() instanceof ViewGroup sourceButtonViewGroup)) {
            Logger.printException(() -> "Unknown button parent: " + sourceButton.getParent());
            return null;
        }

        if (ytSourceButtonRef.get() != sourceButton) {
            buttonControllers.clear();
            ytSourceButtonRef = new WeakReference<>(sourceButton);
        }

        // Locate each container once; subsequent calls are no-ops.
        chapterTitleContainer.updateContainerRef(sourceButtonViewGroup);
        videoHeadingContainer.updateContainerRef(sourceButtonViewGroup);

        return sourceButtonViewGroup;
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
        ViewGroup sourceButtonViewGroup = updateRefsFromSourceButton(sourceButton);
        if (sourceButtonViewGroup == null) return;

        ImageView button = new ImageView(sourceButton.getContext());
        button.setId(View.generateViewId());
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setImageResource(ResourceUtils.getIdentifierOrThrow(
                ResourceType.DRAWABLE, drawableName)
        );
        button.setOnClickListener(onClickListener);
        button.setOnLongClickListener(onLongClickListener);
        sourceButtonViewGroup.addView(button);

        buttonControllers.add(new PlayerOverlayButtonController(button, button::setBackground));
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
        ViewGroup sourceButtonViewGroup = updateRefsFromSourceButton(sourceButton);
        if (sourceButtonViewGroup == null) return null;

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

        buttonControllers.add(new PlayerOverlayButtonController(textOverlay, textOverlay::setBackground));

        return textOverlay;
    }
}
