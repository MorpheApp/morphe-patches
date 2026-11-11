/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.shared;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;

/**
 * Provides the visibility status of the fullscreen player controls_layout view.
 * This can be used for detecting when the player controls are shown.
 */
public final class PlayerControlsVisibilityObserver {

    /**
     * Activity that contains the controls_layout view.
     */
    private final Activity activity;

    /**
     * ID of the direct parent of player_control_play_pause_replay_button_touch_area, R.id.controls_button_group_layout
     */
    private final int controlsButtonGroupId;

    /**
     * id of R.id.player_control_play_pause_replay_button_touch_area
     */
    private final int controlButtonId;

    /**
     * reference to the control button view
     */
    private WeakReference<View> controlButtonView = new WeakReference<>(null);

    /**
     * @param activity activity that contains the controls_layout view
     */
    public PlayerControlsVisibilityObserver(Activity activity) {
        this.activity = activity;
        controlsButtonGroupId = ResourceUtils.getIdentifier(
                activity, ResourceType.ID, "controls_button_group_layout");
        controlButtonId = ResourceUtils.getIdentifier(
                activity, ResourceType.ID, "player_control_play_pause_replay_button_touch_area");
    }

    /**
     * is the {@link #controlButtonView} set to a valid reference of a view?
     */
    private boolean isAttached() {
        View view = controlButtonView.get();
        return view != null && view.getParent() != null;
    }

    /**
     * find and attach the player_control_play_pause_replay_button_touch_area view if needed
     */
    private void maybeAttach() {
        if (isAttached()) return;

        // find parent, then player_control_play_pause_replay_button_touch_area view
        // this is needed because there may be two views where id=R.id.player_control_play_pause_replay_button_touch_area
        // because why should google confine themselves to their own guidelines...
        ViewGroup parent = activity.findViewById(controlsButtonGroupId);
        if (parent != null) {
            View view = parent.findViewById(controlButtonId);
            if (view != null) {
                controlButtonView = new WeakReference<>(view);
            }
        }
    }

    /**
     * current visibility int of the controls_layout view
     */
    public int getPlayerControlsVisibility() {
        maybeAttach();
        View view = controlButtonView.get();
        return view == null ? View.GONE : view.getVisibility();
    }

    /**
     * is the value of {@link #getPlayerControlsVisibility()} equal to {@link View#VISIBLE}?
     */
    public boolean arePlayerControlsVisible() {
        return getPlayerControlsVisibility() == View.VISIBLE;
    }
}
