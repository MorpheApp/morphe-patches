/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.youtube.videoplayer;

import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;

public class PlayerOverlayButton {

    public static final boolean RESTORE_OLD_PLAYER_BUTTONS = Settings.RESTORE_OLD_PLAYER_BUTTONS.get();

    /**
     * How much to compress/expand the existing width. Used to fit 4 buttons
     * without overlapping the video time.
     */
    private static final float BUTTON_WIDTH_PERCENTAGE = 1.0f;

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
                        Drawable placeholderButtonBackground;

                        @Override
                        public boolean onPreDraw() {
                            final int sourcePaddingLeft = sourceButton.getPaddingLeft();
                            final int sourcePaddingTop = sourceButton.getPaddingTop();
                            final int sourcePaddingRight = sourceButton.getPaddingRight();
                            final int sourcePaddingBottom = sourceButton.getPaddingBottom();

                            if (!(sourcePaddingLeft == button.getPaddingLeft()
                                    && sourcePaddingTop == button.getPaddingLeft()
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
                            if (placeholderButtonBackground != sourceButtonBackground) {
                                Drawable mutated = sourceButtonBackground.mutate();
                                button.setBackground(mutated);
                                placeholderButtonBackground = mutated;
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
                                    - (buttonCount * (BUTTON_WIDTH_PERCENTAGE * sourceButton.getWidth())));
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
}
