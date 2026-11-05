/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches.soundproof.ui;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;

import app.morphe.extension.music.patches.soundproof.SoundProofManager;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;

/**
 * Player top bar button showing the SoundProof status of the current track. A tap opens
 * {@link SoundProofSheet}.
 * <p>
 * Inflated from the player layout, where the patch puts it next to the cast button.
 */
@SuppressWarnings("unused")
public final class SoundProofButton extends ImageView implements SoundProofManager.StateListener {

    /**
     * Spacer the patch adds on the other side of the bar, so that the centered audio and video
     * switch stays centered. It is shown and hidden with this button.
     */
    private static final String BALANCING_SPACER_ID = "morphe_soundproof_spacer";

    /** Icon opacity while the skip is off, out of 255. */
    private static final int SKIP_OFF_ICON_ALPHA = 102; // 40 %
    private static final int SKIP_ON_ICON_ALPHA = 255;

    public SoundProofButton(Context context) {
        super(context);
        initialize();
    }

    public SoundProofButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        initialize();
    }

    public SoundProofButton(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initialize();
    }

    private void initialize() {
        setContentDescription(str("morphe_music_soundproof_button_description"));
        setOnClickListener(view -> SoundProofSheet.show(getContext()));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        try {
            boolean isShown = Settings.SOUNDPROOF_SHOW_BUTTON.get();
            setShown(isShown);
            if (isShown) {
                SoundProofManager manager = SoundProofManager.getInstance();
                manager.addListener(this);
                onStateChanged();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "SoundProof button attach failure", ex);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        SoundProofManager.getInstance().removeListener(this);
        super.onDetachedFromWindow();
    }

    @Override
    public void onStateChanged() {
        SoundProofManager manager = SoundProofManager.getInstance();
        Drawable icon = StatusAppearance.iconOf(manager.getCurrentAssessment().status());
        if (icon != null) {
            setImageDrawable(icon);
        }
        // Dimmed so that a skip left off is noticed.
        setImageAlpha(manager.isSkipEnabled() ? SKIP_ON_ICON_ALPHA : SKIP_OFF_ICON_ALPHA);
    }

    private void setShown(boolean isShown) {
        int visibility = isShown ? View.VISIBLE : View.GONE;
        setVisibility(visibility);
        if (getParent() instanceof View parent) {
            View spacer = parent.findViewById(ResourceUtils.getIdIdentifier(BALANCING_SPACER_ID));
            if (spacer != null) {
                spacer.setVisibility(visibility);
            }
        }
    }
}
