/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4451
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.soundproof.ui;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
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
 * Inflated from the player layout, where the patch puts it next to the collapse button.
 */
@SuppressWarnings("unused")
public final class SoundProofButton extends ImageView implements SoundProofManager.StateListener {

    /**
     * Spacer of the app that keeps the audio and video switch centered. This button is as wide and
     * takes its place, so the switch keeps its width.
     */
    private static final String REPLACED_SPACER_ID = "morphe_soundproof_spacer";

    /** Icon opacity while the skip is off, out of 255. */
    private static final int SKIP_OFF_ICON_ALPHA = 102; // 40 %
    private static final int SKIP_ON_ICON_ALPHA = 255;

    public SoundProofButton(Context context, AttributeSet attrs) {
        super(context, attrs);
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
                SoundProofManager.getInstance().addListener(this);
                onStateChanged();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "SoundProof button attach failure", ex);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (Settings.SOUNDPROOF_SHOW_BUTTON.get()) {
            SoundProofManager.getInstance().removeListener(this);
        }
        super.onDetachedFromWindow();
    }

    @Override
    public void onStateChanged() {
        SoundProofManager manager = SoundProofManager.getInstance();
        setImageDrawable(StatusAppearance.iconOf(manager.getCurrentAssessment().status()));
        // Dimmed so that a skip left off is noticed.
        setImageAlpha(manager.isSkipEnabled() ? SKIP_ON_ICON_ALPHA : SKIP_OFF_ICON_ALPHA);
    }

    private void setShown(boolean isShown) {
        setVisibility(isShown ? View.VISIBLE : View.GONE);
        ((View) getParent()).findViewById(ResourceUtils.getIdIdentifier(REPLACED_SPACER_ID))
                .setVisibility(isShown ? View.GONE : View.VISIBLE);
    }
}
