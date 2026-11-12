/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches;

import android.content.SharedPreferences;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;
import android.media.audiofx.PresetReverb;
import android.media.audiofx.Virtualizer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.Setting;

/**
 * Real-time DSP and AudioFX profile engine for YouTube Music.
 * Works across both online streaming and offline playback by hooking ExoPlayer
 * PlaybackParameters (tempo & pitch) and Android hardware AudioTrack session DSPs.
 */
@SuppressWarnings("unused")
public final class AudioFxProfilesPatch {

    public enum AudioProfile {
        DEFAULT(1.0f, 1.0f, PresetReverb.PRESET_NONE, (short) 0, (short) 0, 0),
        NIGHTCORE(1.25f, 1.25f, PresetReverb.PRESET_SMALLROOM, (short) 150, (short) 200, 100),
        SLOWED_REVERB(0.85f, 0.85f, PresetReverb.PRESET_LARGEHALL, (short) 350, (short) 400, 0),
        MUFFLED_NEXT_ROOM(1.0f, 1.0f, PresetReverb.PRESET_MEDIUMHALL, (short) 600, (short) 300, -200),
        LOFI_STUDY(0.95f, 0.95f, PresetReverb.PRESET_MEDIUMROOM, (short) 250, (short) 150, 0),
        DAYCORE(0.80f, 0.80f, PresetReverb.PRESET_PLATE, (short) 450, (short) 200, 50),
        VAPORWAVE(0.75f, 0.75f, PresetReverb.PRESET_LARGEHALL, (short) 400, (short) 700, 0),
        LIVE_CONCERT(1.0f, 1.0f, PresetReverb.PRESET_LARGEHALL, (short) 500, (short) 850, 200),
        AUDIO_8D(1.0f, 1.0f, PresetReverb.PRESET_MEDIUMHALL, (short) 300, (short) 1000, 150),
        BASS_BOOSTED(1.0f, 1.0f, PresetReverb.PRESET_NONE, (short) 1000, (short) 250, 350);

        public final float speed;
        public final float pitch;
        public final short reverbPreset;
        public final short bassStrength;
        public final short virtualizerStrength;
        public final int loudnessGainMb;

        AudioProfile(
                float speed,
                float pitch,
                short reverbPreset,
                short bassStrength,
                short virtualizerStrength,
                int loudnessGainMb
        ) {
            this.speed = speed;
            this.pitch = pitch;
            this.reverbPreset = reverbPreset;
            this.bassStrength = bassStrength;
            this.virtualizerStrength = virtualizerStrength;
            this.loudnessGainMb = loudnessGainMb;
        }
    }

    private static final SharedPreferences.OnSharedPreferenceChangeListener PREF_LISTENER =
            (prefs, key) -> {
                if (Settings.AUDIO_FX_PROFILE.key.equals(key)) {
                    applyActiveProfileEffects();
                }
            };

    private static boolean listenerRegistered;
    private static int activeSessionId;

    @Nullable
    private static PresetReverb reverbEffect;
    @Nullable
    private static BassBoost bassBoostEffect;
    @Nullable
    private static Virtualizer virtualizerEffect;
    @Nullable
    private static Equalizer equalizerEffect;
    @Nullable
    private static LoudnessEnhancer loudnessEffect;

    private AudioFxProfilesPatch() {
    }

    @NonNull
    public static AudioProfile getActiveProfile() {
        return Settings.AUDIO_FX_PROFILE.get();
    }

    public static boolean isProfileActive() {
        return getActiveProfile() != AudioProfile.DEFAULT;
    }

    public static float getEffectiveSpeed(float baseSpeed) {
        AudioProfile profile = getActiveProfile();
        return profile != AudioProfile.DEFAULT ? profile.speed : baseSpeed;
    }

    public static float getEffectivePitch(float basePitch) {
        AudioProfile profile = getActiveProfile();
        return profile != AudioProfile.DEFAULT ? profile.pitch : basePitch;
    }

    /**
     * Injection point called when ExoPlayer initializes or switches AudioTrack session ID.
     */
    public static synchronized void onAudioSessionId(int sessionId) {
        try {
            if (!listenerRegistered && Setting.preferences != null && Setting.preferences.preferences != null) {
                Setting.preferences.preferences.registerOnSharedPreferenceChangeListener(PREF_LISTENER);
                listenerRegistered = true;
            }
            if (sessionId <= 0) {
                return;
            }
            if (sessionId != activeSessionId) {
                activeSessionId = sessionId;
                releaseEffects();
            }
            applyActiveProfileEffects();
        } catch (Exception ex) {
            Logger.printException(() -> "onAudioSessionId failure", ex);
        }
    }

    private static synchronized void applyActiveProfileEffects() {
        if (activeSessionId <= 0) {
            return;
        }
        AudioProfile profile = getActiveProfile();
        if (profile == AudioProfile.DEFAULT) {
            releaseEffects();
            return;
        }

        try {
            applyReverb(profile);
            applyBassBoost(profile);
            applyVirtualizer(profile);
            applyEqualizerCurve(profile);
            applyLoudness(profile);
            Logger.printDebug(() -> "Applied AudioFX profile: " + profile.name() + " on session " + activeSessionId);
        } catch (Exception ex) {
            Logger.printException(() -> "applyActiveProfileEffects failure", ex);
        }
    }

    private static void applyReverb(@NonNull AudioProfile profile) {
        try {
            if (profile.reverbPreset == PresetReverb.PRESET_NONE) {
                if (reverbEffect != null) {
                    reverbEffect.setEnabled(false);
                }
                return;
            }
            if (reverbEffect == null) {
                reverbEffect = new PresetReverb(0, activeSessionId);
            }
            reverbEffect.setPreset(profile.reverbPreset);
            reverbEffect.setEnabled(true);
        } catch (Exception ex) {
            Logger.printDebug(() -> "PresetReverb unavailable: " + ex.getMessage());
        }
    }

    private static void applyBassBoost(@NonNull AudioProfile profile) {
        try {
            if (profile.bassStrength <= 0) {
                if (bassBoostEffect != null) {
                    bassBoostEffect.setEnabled(false);
                }
                return;
            }
            if (bassBoostEffect == null) {
                bassBoostEffect = new BassBoost(0, activeSessionId);
            }
            if (bassBoostEffect.getStrengthSupported()) {
                bassBoostEffect.setStrength(profile.bassStrength);
            }
            bassBoostEffect.setEnabled(true);
        } catch (Exception ex) {
            Logger.printDebug(() -> "BassBoost unavailable: " + ex.getMessage());
        }
    }

    private static void applyVirtualizer(@NonNull AudioProfile profile) {
        try {
            if (profile.virtualizerStrength <= 0) {
                if (virtualizerEffect != null) {
                    virtualizerEffect.setEnabled(false);
                }
                return;
            }
            if (virtualizerEffect == null) {
                virtualizerEffect = new Virtualizer(0, activeSessionId);
            }
            if (virtualizerEffect.getStrengthSupported()) {
                virtualizerEffect.setStrength(profile.virtualizerStrength);
            }
            virtualizerEffect.setEnabled(true);
        } catch (Exception ex) {
            Logger.printDebug(() -> "Virtualizer unavailable: " + ex.getMessage());
        }
    }

    private static void applyLoudness(@NonNull AudioProfile profile) {
        try {
            if (profile.loudnessGainMb == 0) {
                if (loudnessEffect != null) {
                    loudnessEffect.setEnabled(false);
                }
                return;
            }
            if (loudnessEffect == null) {
                loudnessEffect = new LoudnessEnhancer(activeSessionId);
            }
            loudnessEffect.setTargetGain(profile.loudnessGainMb);
            loudnessEffect.setEnabled(true);
        } catch (Exception ex) {
            Logger.printDebug(() -> "LoudnessEnhancer unavailable: " + ex.getMessage());
        }
    }

    private static void applyEqualizerCurve(@NonNull AudioProfile profile) {
        try {
            if (equalizerEffect == null) {
                equalizerEffect = new Equalizer(0, activeSessionId);
            }
            short bands = equalizerEffect.getNumberOfBands();
            short[] range = equalizerEffect.getBandLevelRange();
            if (bands <= 0 || range == null || range.length < 2) {
                return;
            }
            short minLevel = range[0];
            short maxLevel = range[1];

            for (short band = 0; band < bands; band++) {
                int centerFreqMilliHz = equalizerEffect.getCenterFreq(band);
                int freqHz = centerFreqMilliHz / 1000;
                short targetLevel = computeBandLevel(profile, freqHz, minLevel, maxLevel);
                equalizerEffect.setBandLevel(band, targetLevel);
            }
            equalizerEffect.setEnabled(true);
        } catch (Exception ex) {
            Logger.printDebug(() -> "Equalizer unavailable: " + ex.getMessage());
        }
    }

    static short computeBandLevel(@NonNull AudioProfile profile, int freqHz, short minLevel, short maxLevel) {
        switch (profile) {
            case MUFFLED_NEXT_ROOM:
                // Low-pass party wall effect: boost sub-bass, heavily attenuate mids and highs > 450Hz
                if (freqHz <= 150) return (short) (maxLevel * 0.45f);
                if (freqHz <= 450) return 0;
                return minLevel;
            case LOFI_STUDY:
                // Warm analog tape curve: roll off sub-bass and harsh highs, boost warm mids
                if (freqHz < 120) return (short) (minLevel * 0.5f);
                if (freqHz <= 2500) return (short) (maxLevel * 0.3f);
                return (short) (minLevel * 0.75f);
            case NIGHTCORE:
                // Crisp highs and punchy low end
                if (freqHz < 150) return (short) (maxLevel * 0.25f);
                if (freqHz > 4000) return (short) (maxLevel * 0.35f);
                return 0;
            case SLOWED_REVERB:
            case DAYCORE:
                // Deep warm low-end with smooth high roll-off
                if (freqHz <= 200) return (short) (maxLevel * 0.4f);
                if (freqHz > 5000) return (short) (minLevel * 0.35f);
                return 0;
            case VAPORWAVE:
                // Lush retro scoop
                if (freqHz <= 250) return (short) (maxLevel * 0.3f);
                if (freqHz >= 1000 && freqHz <= 3500) return (short) (minLevel * 0.25f);
                return (short) (maxLevel * 0.15f);
            case LIVE_CONCERT:
                // Stadium presence curve
                if (freqHz <= 160) return (short) (maxLevel * 0.45f);
                if (freqHz >= 3000) return (short) (maxLevel * 0.35f);
                return 0;
            case AUDIO_8D:
                // Spatial clarity boost
                if (freqHz >= 1500) return (short) (maxLevel * 0.3f);
                return (short) (maxLevel * 0.15f);
            case BASS_BOOSTED:
                // Heavy sub-bass shelf without muddying vocals
                if (freqHz <= 120) return maxLevel;
                if (freqHz <= 250) return (short) (maxLevel * 0.6f);
                return 0;
            case DEFAULT:
            default:
                return 0;
        }
    }

    private static void releaseEffects() {
        if (reverbEffect != null) {
            try { reverbEffect.release(); } catch (Exception ignored) {}
            reverbEffect = null;
        }
        if (bassBoostEffect != null) {
            try { bassBoostEffect.release(); } catch (Exception ignored) {}
            bassBoostEffect = null;
        }
        if (virtualizerEffect != null) {
            try { virtualizerEffect.release(); } catch (Exception ignored) {}
            virtualizerEffect = null;
        }
        if (equalizerEffect != null) {
            try { equalizerEffect.release(); } catch (Exception ignored) {}
            equalizerEffect = null;
        }
        if (loudnessEffect != null) {
            try { loudnessEffect.release(); } catch (Exception ignored) {}
            loudnessEffect = null;
        }
    }
}
