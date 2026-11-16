/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4659
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.videoplayer.AutoplayButton;

/**
 * Autoplay control: a rule for the next video, in place of YouTube's Autoplay.
 * Playlists and queues go on, a Mix stops after a number of videos, a suggested video never plays.
 * The rule is in AutoplayRule. With nothing next, "Miniplayer at video end" takes over.
 *
 * Hook: the navigation intent check of "Disable playlist autoplay" calls shouldSkip.
 */
@SuppressWarnings("unused")
public final class AutoplayControlPatch {
    private AutoplayControlPatch() {
    }

    private static final AutoplayRule rule = new AutoplayRule();

    /**
     * Called from {@link DisablePlaylistAutoplayPatch} while Autoplay control is on.
     */
    static boolean shouldSkip(String intent) {
        String listId = VideoInformation.getPlaylistId();
        final boolean skip = rule.shouldSkip(intent, listId, Settings.AUTOPLAY_MODE.get(), mixStopAfter());
        Logger.printDebug(() -> "Autoplay control: " + intent + " list " + listId + " count "
                + rule.mixCount() + (skip ? " -> skip" : " -> go on"));
        if (skip) {
            Utils.runOnMainThread(AutoplayButton::update);
        }
        return skip;
    }

    /**
     * Injection point. Every loaded regular video, also in the background,
     * and more than once for the same one.
     */
    public static void newVideoLoaded(String videoId) {
        try {
            if (!Settings.AUTOPLAY_CONTROL.get()) {
                return;
            }
            rule.videoLoaded(VideoInformation.getPlaylistId(), videoId);
            Utils.runOnMainThread(AutoplayButton::update);
        } catch (Exception ex) {
            Logger.printException(() -> "Autoplay control newVideoLoaded failure", ex);
        }
    }

    /**
     * @return Mix videos to play in mode Auto, 0 for no limit.
     */
    public static int mixStopAfter() {
        return Math.max(0, Settings.AUTOPLAY_MIX_STOP_AFTER.get());
    }

    /**
     * @return The position in the Mix, or 0 outside a Mix.
     */
    public static int mixCount() {
        return rule.mixCount();
    }

    /**
     * Sets the mode and updates the button.
     */
    public static void setMode(AutoplayRule.Mode mode) {
        Settings.AUTOPLAY_MODE.save(mode);
        Utils.runOnMainThread(AutoplayButton::update);
    }
}
