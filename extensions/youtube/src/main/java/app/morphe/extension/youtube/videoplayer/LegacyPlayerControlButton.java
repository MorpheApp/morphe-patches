/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.videoplayer;

import static app.morphe.extension.youtube.patches.LegacyPlayerControlsPatch.RESTORE_OLD_PLAYER_BUTTONS;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewTreeObserver;
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
import app.morphe.extension.shared.settings.BooleanSetting;

public class LegacyPlayerControlButton {

    public enum ButtonVisibility {
        ENABLED,
        DISABLED,
        FORCE_HIDDEN,
        FORCE_SHOW
    }

    public interface PlayerControlButtonStatus {
        ButtonVisibility status();
    }

    public static final int fadeInDuration = ResourceUtils.getInteger("fade_duration_fast");
    public static final int fadeOutDuration = ResourceUtils.getInteger("fade_duration_scheduled");

    private static final List<ViewTreeObserver.OnPreDrawListener> pendingListeners = new ArrayList<>();

    /**
     * Number of Morphe legacy upper buttons that are enabled.
     */
    private static int totalUpperButtonCount;

    public static void incrementUpperButtonCount() {
        totalUpperButtonCount++;
    }

    public static int getTotalUpperButtonCount() {
        return totalUpperButtonCount;
    }

    /**
     * Returns the appropriate dialog background color depending on the current theme.
     */
    public static int getDialogBackgroundColor() {
        return ResourceUtils.getColor(Utils.isDarkModeEnabled() ? "yt_black1" : "yt_white1");
    }

    private final WeakReference<View> buttonRef;
    private final WeakReference<TextView> textOverlayRef;

    private static WeakReference<View> ytSourceButtonRef = new WeakReference<>(null);
    private final PlayerControlButtonStatus enabledStatus;
    private final WeakReference<View> containerRef;

    public LegacyPlayerControlButton(View controlsViewGroup,
                                     String buttonId,
                                     @Nullable String textOverlayId,
                                     @Nullable String imageResourceName,
                                     BooleanSetting setting,
                                     View.OnClickListener onClickListener,
                                     @Nullable View.OnLongClickListener longClickListener) {
        this(
                controlsViewGroup,
                buttonId,
                buttonId,
                textOverlayId,
                imageResourceName,
                setting,
                onClickListener,
                longClickListener
        );
    }

    public LegacyPlayerControlButton(View controlsViewGroup,
                                     String buttonId,
                                     @Nullable String textOverlayId,
                                     @Nullable String imageResourceName,
                                     PlayerControlButtonStatus enabledStatus,
                                     View.OnClickListener onClickListener,
                                     @Nullable View.OnLongClickListener longClickListener) {
        this(
                controlsViewGroup,
                buttonId,
                buttonId,
                textOverlayId,
                imageResourceName,
                enabledStatus,
                onClickListener,
                longClickListener
        );
    }

    public LegacyPlayerControlButton(View controlsViewGroup,
                                     String viewToHide,
                                     String buttonId,
                                     @Nullable String textOverlayId,
                                     @Nullable String imageResourceName,
                                     BooleanSetting enabledStatus,
                                     View.OnClickListener onClickListener,
                                     @Nullable View.OnLongClickListener longClickListener) {
        this(
                controlsViewGroup,
                viewToHide,
                buttonId,
                textOverlayId,
                imageResourceName,
                () -> enabledStatus.get()
                        ? ButtonVisibility.ENABLED
                        : ButtonVisibility.DISABLED,
                onClickListener,
                longClickListener
        );
    }

    public LegacyPlayerControlButton(View controlsViewGroup,
                                     String viewToHide,
                                     String buttonId,
                                     @Nullable String textOverlayId,
                                     @Nullable String imageResourceName,
                                     PlayerControlButtonStatus enabledStatus,
                                     View.OnClickListener onClickListener,
                                     @Nullable View.OnLongClickListener longClickListener) {
        this.enabledStatus = enabledStatus;

        View containerView = Utils.getChildViewByResourceName(controlsViewGroup, viewToHide);
        containerView.setVisibility(View.GONE);
        containerRef = new WeakReference<>(containerView);

        ViewTreeObserver.OnPreDrawListener onPreDrawListener = () -> {
            updateLayoutFromSourceButton();
            return true;
        };

        View ytButton = ytSourceButtonRef.get();
        if (ytButton == null) {
            ytButton = Utils.getChildViewByResourceName(controlsViewGroup, "player_overflow_button");
            if (ytButton == null) {
                // Currently only happens with SB skip button.
                Logger.printDebug(() -> "Adding pending listener");
                pendingListeners.add(onPreDrawListener);
            } else {
                ytSourceButtonRef = new WeakReference<>(ytButton);
            }
        }
        if (ytButton != null) {
            ViewTreeObserver viewTreeObserver = ytButton.getViewTreeObserver();
            viewTreeObserver.addOnPreDrawListener(onPreDrawListener);
            for (ViewTreeObserver.OnPreDrawListener pendingListener : pendingListeners) {
                viewTreeObserver.addOnPreDrawListener(pendingListener);
            }
            pendingListeners.clear();
        }

        View button = Utils.getChildViewByResourceName(controlsViewGroup, buttonId);

        if (imageResourceName != null) {
            final int iconResourceId = ResourceUtils.getIdentifierOrThrow(ResourceType.DRAWABLE,
                    RESTORE_OLD_PLAYER_BUTTONS
                            ? imageResourceName
                            : imageResourceName + "_bold"
            );
            ((ImageView) button).setImageResource(iconResourceId);
        }

        // Wrap click listener to trigger animation.
        button.setOnClickListener(view -> {
            animateIcon();
            if (onClickListener != null) {
                onClickListener.onClick(view);
            }
        });

        if (longClickListener != null) {
            // Wrap long click listener to trigger animation.
            button.setOnLongClickListener(view -> {
                animateIcon();
                return longClickListener.onLongClick(view);
            });
        }

        buttonRef = new WeakReference<>(button);

        TextView tempTextOverlay = null;
        if (textOverlayId != null) {
            tempTextOverlay = Utils.getChildViewByResourceName(controlsViewGroup, textOverlayId);
        }
        textOverlayRef = new WeakReference<>(tempTextOverlay);
    }

    private void updateLayoutFromSourceButton() {
        View source = ytSourceButtonRef.get();
        View container = containerRef.get();

        if (source == null || container == null) {
            Logger.printDebug(() -> "Button views are null, source: " + source + " container: " + container);
            return;
        }

        final float sourceButtonAlpha = source.getAlpha();
        if (container.getAlpha() != sourceButtonAlpha) {
            container.setAlpha(sourceButtonAlpha);
        }

        final int sourceButtonVisibility = switch (enabledStatus.status()) {
            case ENABLED -> source.getVisibility();
            case DISABLED, FORCE_HIDDEN -> View.GONE;
            case FORCE_SHOW -> View.VISIBLE;
        };

        if (container.getVisibility() != sourceButtonVisibility) {
            container.setVisibility(sourceButtonVisibility);
        }
    }

    /**
     * Animates the button icon if it's an AnimatedVectorDrawable.
     */
    public void animateIcon() {
        try {
            View button = buttonRef.get();
            if (button instanceof ImageView imageView) {
                Drawable drawable = imageView.getDrawable();
                if (drawable instanceof AnimatedVectorDrawable avd) {
                    avd.start();
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "animateIcon failure", ex);
        }
    }

    /**
     * Sets the icon of the button.
     * @param resourceId Drawable identifier, or zero to hide the icon.
     */
    public void setIcon(int resourceId) {
        Utils.verifyOnMainThread();

        View button = buttonRef.get();
        if (button instanceof ImageView imageButton) {
            imageButton.setImageResource(resourceId);
            imageButton.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        }
    }

    /**
     * Sets the alpha of the button's image drawable (0–255).
     * Unlike {@link View#setAlpha}, this is not overridden by visibility animations.
     */
    public void setImageAlpha(int alpha) {
        Utils.verifyOnMainThread();

        View button = buttonRef.get();
        if (button instanceof ImageView imageButton) {
            imageButton.setImageAlpha(alpha);
        }
    }

    /**
     * Sets the text to be displayed on the text overlay.
     * @param text The text to set on the overlay, or null to clear the text.
     */
    public void setTextOverlay(CharSequence text) {
        Utils.verifyOnMainThread();

        TextView textOverlay = textOverlayRef.get();
        if (textOverlay != null) {
            textOverlay.setText(text);
        }
    }

    public void setVisibility(int visiblity) {
        Utils.verifyOnMainThread();
        View button = buttonRef.get();
        if (button != null) {
            button.setVisibility(visiblity);
        }
    }
}
