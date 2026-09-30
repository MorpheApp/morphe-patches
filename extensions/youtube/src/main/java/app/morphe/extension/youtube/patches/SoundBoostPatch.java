/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3412
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.content.SharedPreferences;
import android.media.audiofx.LoudnessEnhancer;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class SoundBoostPatch {

    public static final int MAX_BOOST_STEPS = 4;
    private static final int BOOST_STEP_MILLIBELS = 300;

    /**
     * Must be a field, SharedPreferences only holds listeners weakly.
     */
    private static final SharedPreferences.OnSharedPreferenceChangeListener SETTING_LISTENER =
            (prefs, key) -> {
                if (Settings.VOLUME_BOOST.key.equals(key)) {
                    onSettingChanged();
                }
            };

    private static boolean settingListenerRegistered;
    private static int sessionId;

    /**
     * Not saved. Starts at zero every app start and is kept across videos.
     */
    private static int boostStep;
    private static LoudnessEnhancer effect;

    /**
     * Injection point.
     */
    public static synchronized void onAudioSessionId(int id) {
        try {
            if (!settingListenerRegistered) {
                Setting.preferences.preferences.registerOnSharedPreferenceChangeListener(SETTING_LISTENER);
                settingListenerRegistered = true;
            }

            if (id == sessionId) {
                return;
            }

            sessionId = id;
            releaseEffect();
            applyBoost();
        } catch (Exception ex) {
            Logger.printException(() -> "onAudioSessionId failure", ex);
        }
    }

    public static boolean isBoostAllowed() {
        return Settings.VOLUME_BOOST.get();
    }

    public static synchronized int getBoostStep() {
        return isBoostAllowed() ? boostStep : 0;
    }

    /**
     * Raises or lowers the boost by whole steps.
     *
     * @return If the boost changed.
     */
    public static synchronized boolean adjustBoostStep(int steps) {
        if (!isBoostAllowed()) {
            return false;
        }

        final int oldStep = boostStep;
        setBoostStep(oldStep + steps);
        return boostStep != oldStep;
    }

    private static synchronized void onSettingChanged() {
        setBoostStep(boostStep);
    }

    private static synchronized void setBoostStep(int step) {
        boostStep = isBoostAllowed() ? Math.max(0, Math.min(MAX_BOOST_STEPS, step)) : 0;
        applyBoost();
    }

    private static void applyBoost() {
        // Session 0 is the global output mix and must never be touched.
        if (boostStep == 0 || sessionId <= 0) {
            releaseEffect();
            return;
        }

        try {
            if (effect == null) {
                LoudnessEnhancer enhancer = new LoudnessEnhancer(sessionId);
                enhancer.setEnabled(true);
                effect = enhancer;
            }
            effect.setTargetGain(boostStep * BOOST_STEP_MILLIBELS);
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to apply volume boost", ex);
            releaseEffect();
        }
    }

    private static void releaseEffect() {
        if (effect != null) {
            effect.release();
            effect = null;
        }
    }
}
