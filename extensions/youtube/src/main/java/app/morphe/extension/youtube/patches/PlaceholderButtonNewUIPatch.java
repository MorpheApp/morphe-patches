/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.Utils.getDrawableInt;

import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;

import java.util.Arrays;

import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public final class PlaceholderButtonNewUIPatch {
    public static void inject(View sourceButton) {
        if (sourceButton != null
                &&
            sourceButton.getParent() instanceof ViewGroup sourceButtonViewGroup
        ) {
            ImageView placeHolderButton = new ImageView(Utils.getContext());

            placeHolderButton.setId(View.generateViewId());
            placeHolderButton.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            placeHolderButton.setImageResource(getDrawableInt("quantum_ic_playlist_add_white_24"));
            sourceButton.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    int[] sourceButtonPadding;
                    Drawable sourceButtonBackground;
                    Drawable placeholderButtonBackground = new ColorDrawable(android.graphics.Color.TRANSPARENT);
                    float sourceButtonAlpha = 0;
                    int sourceButtonVisibility = 0;
                    float placeholderButtonNewPosX = 0;
                    float placeholderButtonNewPosY = 0;

                    @Override
                    public boolean onPreDraw() {
                        sourceButtonPadding = new int[] {
                            sourceButton.getPaddingLeft(),
                            sourceButton.getPaddingTop(),
                            sourceButton.getPaddingRight(),
                            sourceButton.getPaddingBottom()
                        };
                        if (!Arrays.equals(
                                new int[] {
                                    placeHolderButton.getPaddingLeft(),
                                    placeHolderButton.getPaddingTop(),
                                    placeHolderButton.getPaddingRight(),
                                    placeHolderButton.getPaddingBottom()
                                },

                                sourceButtonPadding
                            )
                        ) {
                            placeHolderButton.setLayoutParams(sourceButton.getLayoutParams());

                            placeHolderButton.setPadding(
                                sourceButtonPadding[0],
                                sourceButtonPadding[1],
                                sourceButtonPadding[2],
                                sourceButtonPadding[3]
                            );
                        }

                        sourceButtonBackground = sourceButton.getBackground();
                        if (placeholderButtonBackground != sourceButtonBackground) {
                            placeHolderButton.setBackground(sourceButtonBackground.mutate());

                            placeholderButtonBackground = placeHolderButton.getBackground();
                        }

                        sourceButtonAlpha = sourceButton.getAlpha();
                        if (placeHolderButton.getAlpha() != sourceButtonAlpha) {
                            placeHolderButton.setAlpha(sourceButtonAlpha);
                        }

                        sourceButtonVisibility = sourceButton.getVisibility();
                        if (placeHolderButton.getVisibility() != sourceButtonVisibility) {
                            placeHolderButton.setVisibility(sourceButtonVisibility);
                        }

                        placeholderButtonNewPosX = sourceButton.getX() - sourceButton.getWidth();
                        if (placeHolderButton.getX() != placeholderButtonNewPosX) {
                            placeHolderButton.setX(placeholderButtonNewPosX);
                        }

                        placeholderButtonNewPosY = sourceButton.getY();
                        if (placeHolderButton.getY() != placeholderButtonNewPosY) {
                            placeHolderButton.setY(placeholderButtonNewPosY);
                        }

                        return true;
                    }
                }
            );
            placeHolderButton.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    new Thread(() -> {
                        Utils.showToastLong("This is a placeholder button!");
                    }).start();
                }
            });

            sourceButtonViewGroup.addView(placeHolderButton);
        }
    }
}
