/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.youtube.videoplayer;

import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

public class PlayerOverlayButton {

    private static WeakReference<ViewTreeObserver> buttonObserver = new WeakReference<>(null);
    private static int newButtonCount;

    public static ImageView addButton(View sourceButton, String drawableName,
                                      View.OnClickListener onClickListener,
                                      View.OnLongClickListener onLongClickListener) {
        if (sourceButton != null
                && sourceButton.getParent() instanceof ViewGroup sourceButtonViewGroup
        ) {
            ImageView button = new ImageView(sourceButton.getContext());

            button.setId(View.generateViewId());
            button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            button.setImageResource(ResourceUtils.getIdentifierOrThrow(
                    ResourceType.DRAWABLE, drawableName));

            ViewTreeObserver observer = sourceButton.getViewTreeObserver();
            if (observer != buttonObserver.get()) {
                Utils.showToastShort("New observer");
                newButtonCount = 0;
                buttonObserver = new WeakReference<>(observer);
            }

            final int buttonCount = ++newButtonCount;

            observer.addOnPreDrawListener(
                    new ViewTreeObserver.OnPreDrawListener() {
                        Drawable placeholderButtonBackground = new ColorDrawable(
                                android.graphics.Color.TRANSPARENT);

                        @Override
                        public boolean onPreDraw() {
                            final int paddingLeft = sourceButton.getPaddingLeft();
                            final int paddingTop = sourceButton.getPaddingLeft();
                            final int paddingRight = sourceButton.getPaddingLeft();
                            final int paddingBottom = sourceButton.getPaddingLeft();

                            if (paddingLeft != button.getPaddingLeft()
                                    || paddingTop != button.getPaddingTop()
                                    || paddingRight != button.getPaddingRight()
                                    || paddingBottom != button.getPaddingBottom()
                            ) {
                                button.setLayoutParams(sourceButton.getLayoutParams());

                                button.setPadding(
                                        paddingLeft,
                                        paddingTop,
                                        paddingRight,
                                        paddingBottom
                                );
                            }

                            Drawable sourceButtonBackground = sourceButton.getBackground();
                            if (placeholderButtonBackground != sourceButtonBackground) {
                                Drawable mutate = sourceButtonBackground.mutate();
                                button.setBackground(mutate);
                                placeholderButtonBackground = mutate;
                            }

                            final float sourceButtonAlpha = sourceButton.getAlpha();
                            if (button.getAlpha() != sourceButtonAlpha) {
                                button.setAlpha(sourceButtonAlpha);
                            }

                            final int sourceButtonVisibility = sourceButton.getVisibility();
                            if (button.getVisibility() != sourceButtonVisibility) {
                                button.setVisibility(sourceButtonVisibility);
                            }

                            final float xOffset = sourceButton.getX()
                                    - (buttonCount * (float) sourceButton.getWidth());
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

            button.setOnClickListener(onClickListener);
            button.setOnLongClickListener(onLongClickListener);

            sourceButtonViewGroup.addView(button);

            return button;
        }

        return null;
    }
}
