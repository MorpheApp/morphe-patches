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
import android.widget.FrameLayout;
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
            case 1 -> 1.0f;
            case 2 -> 0.95f;
            case 3 -> 0.90f;
            case 4 -> 0.85f;
            default -> 1.0f / totalButtons;
        };
    }

    private static WeakReference<ViewTreeObserver> buttonObserver = new WeakReference<>(null);
    private static int newButtonCount;

    @Nullable
    public static ImageView addButton(View sourceButton,
                                      String drawableName,
                                      View.OnClickListener onClickListener,
                                      View.OnLongClickListener onLongClickListener) {
        Utils.verifyOnMainThread();

        if (sourceButton.getParent() instanceof ViewGroup sourceButtonViewGroup) {
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
                    new ViewTreeObserver.OnPreDrawListener() {
                        // Track the ConstantState of the source background to detect real drawable changes.
                        Drawable.ConstantState sourceBackgroundSnapshot;

                        @Override
                        public boolean onPreDraw() {
                            final int sourcePaddingLeft = sourceButton.getPaddingLeft();
                            final int sourcePaddingTop = sourceButton.getPaddingTop();
                            final int sourcePaddingRight = sourceButton.getPaddingRight();
                            final int sourcePaddingBottom = sourceButton.getPaddingBottom();

                            if (!(sourcePaddingLeft == button.getPaddingLeft()
                                    && sourcePaddingTop == button.getPaddingTop()
                                    && sourcePaddingRight == button.getPaddingRight()
                                    && sourcePaddingBottom == button.getPaddingBottom())
                            ) {
                                button.setLayoutParams(sourceButton.getLayoutParams());
                                button.setPadding(
                                        sourcePaddingLeft,
                                        sourcePaddingTop,
                                        sourcePaddingRight,
                                        sourcePaddingBottom
                                );
                            }

                            Drawable sourceButtonBackground = sourceButton.getBackground();
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
                                button.setBackground(newBackground);
                                sourceBackgroundSnapshot = newConstantState;
                            }

                            final float sourceButtonAlpha = sourceButton.getAlpha();
                            if (button.getAlpha() != sourceButtonAlpha) {
                                button.setAlpha(sourceButtonAlpha);
                            }

                            final int sourceButtonVisibility = sourceButton.getVisibility();
                            if (button.getVisibility() != sourceButtonVisibility) {
                                button.setVisibility(sourceButtonVisibility);
                            }

                            final float xOffset = (int) (sourceButton.getX()
                                    - (buttonCount * (getButtonWidthPercentage(newButtonCount) * sourceButton.getWidth())));
                            if (button.getX() != xOffset) {
                                button.setX(xOffset);
                            }

                            final float positionY = sourceButton.getY();
                            if (button.getY() != positionY) {
                                button.setY(positionY);
                            }

                            return true;
                        }
                    }
            );

            sourceButtonViewGroup.addView(button);

            return button;
        }

        return null;
    }

    /**
     * Same as {@link #addButton} but wraps the button in a {@link FrameLayout}
     * containing both the icon and a centered {@link TextView} overlay.
     * Visibility, alpha, and fade animations are automatically inherited
     * by both children since they share the same container.
     *
     * @return The created {@link TextView}, or null if the button could not be added.
     */
    @Nullable
    public static TextView addButtonWithTextOverlay(View sourceButton,
                                                    String drawableName,
                                                    View.OnClickListener onClickListener,
                                                    View.OnLongClickListener onLongClickListener) {
        Utils.verifyOnMainThread();

        if (!(sourceButton.getParent() instanceof ViewGroup sourceButtonViewGroup)) {
            return null;
        }

        ViewTreeObserver observer = sourceButton.getViewTreeObserver();
        if (observer != buttonObserver.get()) {
            newButtonCount = 0;
            buttonObserver = new WeakReference<>(observer);
        }
        final int buttonCount = ++newButtonCount;

        FrameLayout container = new FrameLayout(sourceButton.getContext());
        container.setId(View.generateViewId());

        ImageView icon = new ImageView(sourceButton.getContext());
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        icon.setImageResource(ResourceUtils.getIdentifierOrThrow(ResourceType.DRAWABLE, drawableName));
        icon.setOnClickListener(onClickListener);
        icon.setOnLongClickListener(onLongClickListener);
        container.addView(icon, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        TextView textOverlay = new TextView(sourceButton.getContext());
        textOverlay.setId(View.generateViewId());
        textOverlay.setGravity(Gravity.CENTER);
        textOverlay.setTextSize(10);
        textOverlay.setTextColor(0xFFFFFFFF);
        textOverlay.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        textOverlay.setClickable(false);
        textOverlay.setFocusable(false);
        textOverlay.setPadding(0, 0, 0, 0);
        container.addView(textOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
        ));

        observer.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            // Track the ConstantState of the source background to detect real drawable changes.
            Drawable.ConstantState sourceBackgroundSnapshot;

            @Override
            public boolean onPreDraw() {
                final int sourcePaddingLeft = sourceButton.getPaddingLeft();
                final int sourcePaddingTop = sourceButton.getPaddingTop();
                final int sourcePaddingRight = sourceButton.getPaddingRight();
                final int sourcePaddingBottom = sourceButton.getPaddingBottom();

                if (!(sourcePaddingLeft == container.getPaddingLeft()
                        && sourcePaddingTop == container.getPaddingTop()
                        && sourcePaddingRight == container.getPaddingRight()
                        && sourcePaddingBottom == container.getPaddingBottom())
                ) {
                    container.setLayoutParams(sourceButton.getLayoutParams());
                    container.setPadding(
                            sourcePaddingLeft,
                            sourcePaddingTop,
                            sourcePaddingRight,
                            sourcePaddingBottom
                    );
                }

                Drawable sourceButtonBackground = sourceButton.getBackground();
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
                    container.setBackground(newBackground);
                    sourceBackgroundSnapshot = newConstantState;
                }

                final float sourceButtonAlpha = sourceButton.getAlpha();
                if (container.getAlpha() != sourceButtonAlpha) {
                    container.setAlpha(sourceButtonAlpha);
                }

                final int sourceButtonVisibility = sourceButton.getVisibility();
                if (container.getVisibility() != sourceButtonVisibility) {
                    container.setVisibility(sourceButtonVisibility);
                }

                final float xOffset = (int) (sourceButton.getX()
                        - (buttonCount * (getButtonWidthPercentage(newButtonCount) * sourceButton.getWidth())));
                if (container.getX() != xOffset) {
                    container.setX(xOffset);
                }

                final float positionY = sourceButton.getY();
                if (container.getY() != positionY) {
                    container.setY(positionY);
                }

                return true;
            }
        });

        sourceButtonViewGroup.addView(container);

        return textOverlay;
    }
}
