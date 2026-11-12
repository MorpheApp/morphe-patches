/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches;

import static app.morphe.extension.shared.StringRef.str;
import static app.morphe.extension.shared.ui.Dim.dp;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioTrack;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;
import android.media.audiofx.PresetReverb;
import android.media.audiofx.Virtualizer;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.preference.SeekBarPreference;
import app.morphe.extension.shared.ui.SheetBottomDialog;

/**
 * Real-time DSP and AudioFX profile engine for YouTube Music.
 * Works across both online streaming and offline playback by hooking:
 * 1) ExoPlayer PlaybackParameters (tempo & pitch),
 * 2) AudioTrack.write(ByteBuffer, int, int) & AudioTrack.setStereoVolume (for real-time 8D binaural rotation),
 * 3) Android hardware AudioTrack session DSPs (Reverb, BassBoost, Equalizer, Virtualizer, Loudness).
 */
@SuppressWarnings("unused")
public final class AudioFxProfilesPatch {

    public enum AudioProfile {
        DEFAULT(
                "morphe_music_audio_fx_profile_entry_default",
                "Default",
                1.0f, 1.0f, PresetReverb.PRESET_NONE, (short) 0, (short) 0, 0
        ),
        NIGHTCORE(
                "morphe_music_audio_fx_profile_entry_nightcore",
                "Nightcore",
                1.25f, 1.25f, PresetReverb.PRESET_SMALLROOM, (short) 150, (short) 200, 100
        ),
        SLOWED_REVERB(
                "morphe_music_audio_fx_profile_entry_slowed_reverb",
                "Slowed + Reverb",
                0.85f, 0.85f, PresetReverb.PRESET_LARGEHALL, (short) 350, (short) 400, 0
        ),
        MUFFLED_NEXT_ROOM(
                "morphe_music_audio_fx_profile_entry_muffled_next_room",
                "Muffled / Next Room",
                1.0f, 1.0f, PresetReverb.PRESET_MEDIUMHALL, (short) 600, (short) 300, -200
        ),
        LOFI_STUDY(
                "morphe_music_audio_fx_profile_entry_lofi_study",
                "Lo-Fi Study",
                0.95f, 0.95f, PresetReverb.PRESET_MEDIUMROOM, (short) 250, (short) 150, 0
        ),
        DAYCORE(
                "morphe_music_audio_fx_profile_entry_daycore",
                "Daycore",
                0.80f, 0.80f, PresetReverb.PRESET_PLATE, (short) 450, (short) 200, 50
        ),
        VAPORWAVE(
                "morphe_music_audio_fx_profile_entry_vaporwave",
                "Vaporwave",
                0.75f, 0.75f, PresetReverb.PRESET_LARGEHALL, (short) 400, (short) 700, 0
        ),
        LIVE_CONCERT(
                "morphe_music_audio_fx_profile_entry_live_concert",
                "Live Concert",
                1.0f, 1.0f, PresetReverb.PRESET_LARGEHALL, (short) 500, (short) 850, 200
        ),
        AUDIO_8D(
                "morphe_music_audio_fx_profile_entry_audio_8d",
                "8D Audio",
                1.0f, 1.0f, PresetReverb.PRESET_LARGEHALL, (short) 350, (short) 1000, 150
        ),
        BASS_BOOSTED(
                "morphe_music_audio_fx_profile_entry_bass_boosted",
                "Bass Boosted",
                1.0f, 1.0f, PresetReverb.PRESET_NONE, (short) 1000, (short) 250, 350
        ),
        CUSTOM(
                "morphe_music_audio_fx_profile_entry_custom",
                "Custom",
                1.0f, 1.0f, PresetReverb.PRESET_MEDIUMHALL, (short) 500, (short) 500, 100
        );

        public final String labelKey;
        public final String shortName;
        public final float speed;
        public final float pitch;
        public final short reverbPreset;
        public final short bassStrength;
        public final short virtualizerStrength;
        public final int loudnessGainMb;

        AudioProfile(
                String labelKey,
                String shortName,
                float speed,
                float pitch,
                short reverbPreset,
                short bassStrength,
                short virtualizerStrength,
                int loudnessGainMb
        ) {
            this.labelKey = labelKey;
            this.shortName = shortName;
            this.speed = speed;
            this.pitch = pitch;
            this.reverbPreset = reverbPreset;
            this.bassStrength = bassStrength;
            this.virtualizerStrength = virtualizerStrength;
            this.loudnessGainMb = loudnessGainMb;
        }

        @NonNull
        public String getDisplayName() {
            try {
                String localized = str(labelKey);
                if (!localized.isEmpty() && !localized.equals(labelKey)) {
                    return localized;
                }
            } catch (Exception ignored) {
            }
            return shortName;
        }
    }

    private static final String TABBED_VIEW_ID = "bottom_sheet_tabbed_view";
    private static final String FX_TAB_TAG = "morphe_audio_fx_tab_button";

    // Active AudioTrack instances for hardware stereo panning and PCM rotation
    private static final Set<AudioTrack> activeAudioTracks =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private static final int ASSUMED_SAMPLE_RATE = 48000;
    private static double lfoPhase = 0.0;
    @Nullable
    private static ByteBuffer lastProcessedBuffer;
    private static int lastProcessedPosition;
    private static int lastProcessedLimit;

    private static final SharedPreferences.OnSharedPreferenceChangeListener PREF_LISTENER =
            (prefs, key) -> {
                if (key != null && key.startsWith("morphe_music_audio_fx")) {
                    applyActiveProfileEffects();
                    PlaybackSpeedPatch.setVideoTime(0L);
                    Utils.runOnMainThreadNowOrLater(AudioFxProfilesPatch::refreshPlayerTabButton);
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

    public static boolean isEnabled() {
        return Settings.AUDIO_FX_ENABLED.get();
    }

    @NonNull
    public static AudioProfile getActiveProfile() {
        if (!isEnabled()) {
            return AudioProfile.DEFAULT;
        }
        return Settings.AUDIO_FX_PROFILE.get();
    }

    public static void selectProfile(@NonNull AudioProfile profile) {
        Settings.AUDIO_FX_PROFILE.save(profile);
        resetStereoBalanceIfNeeded();
        applyActiveProfileEffects();
        PlaybackSpeedPatch.setVideoTime(0L);
        Utils.runOnMainThreadNowOrLater(AudioFxProfilesPatch::refreshPlayerTabButton);
    }

    public static boolean isProfileActive() {
        return getActiveProfile() != AudioProfile.DEFAULT;
    }

    /**
     * Preset profiles (Nightcore, Slowed + Reverb, etc.) use their exact built-in speed.
     * The custom speed slider ONLY applies when the CUSTOM profile is selected.
     */
    public static float getEffectiveSpeed(float baseSpeed) {
        AudioProfile profile = getActiveProfile();
        if (profile == AudioProfile.DEFAULT) {
            return baseSpeed;
        }
        if (profile == AudioProfile.CUSTOM) {
            return SeekBarPreference.clampToRange(Settings.AUDIO_FX_CUSTOM_SPEED) / 100f;
        }
        return profile.speed;
    }

    /**
     * Preset profiles use their exact built-in pitch.
     * The custom pitch slider ONLY applies when the CUSTOM profile is selected.
     */
    public static float getEffectivePitch(float basePitch) {
        AudioProfile profile = getActiveProfile();
        if (profile == AudioProfile.DEFAULT) {
            return basePitch;
        }
        if (profile == AudioProfile.CUSTOM) {
            return SeekBarPreference.clampToRange(Settings.AUDIO_FX_CUSTOM_PITCH) / 100f;
        }
        return profile.pitch;
    }

    /**
     * Injection point: captures newly created or active AudioTrack instances and processes
     * 8D Audio stereo rotation before AudioTrack.write(ByteBuffer, int, int) sends PCM to hardware.
     */
    @SuppressWarnings("deprecation")
    public static void onAudioTrackWrite(@Nullable AudioTrack track, @Nullable ByteBuffer buffer, int sizeInBytes) {
        if (track == null) {
            return;
        }
        try {
            activeAudioTracks.add(track);
            int sessionId = track.getAudioSessionId();
            if (sessionId > 0 && sessionId != activeSessionId) {
                onAudioSessionId(sessionId);
            }

            if (getActiveProfile() != AudioProfile.AUDIO_8D) {
                return;
            }

            // Update hardware left/right channel panning smoothly via LFO
            final int cycleSeconds = Math.max(2, SeekBarPreference.clampToRange(Settings.AUDIO_FX_8D_SPEED));
            final float depth = SeekBarPreference.clampToRange(Settings.AUDIO_FX_8D_DEPTH) / 100f;

            // Advance LFO phase proportional to the written byte chunk (4 bytes per 48kHz stereo frame)
            int frames = Math.max(256, sizeInBytes / 4);
            double phaseStep = (2.0 * Math.PI * frames) / (cycleSeconds * ASSUMED_SAMPLE_RATE);
            lfoPhase += phaseStep;
            if (lfoPhase >= 2.0 * Math.PI) {
                lfoPhase -= 2.0 * Math.PI;
            }

            float pan = (float) Math.sin(lfoPhase) * depth;
            // Smooth equal-power stereo panning across Left and Right speakers/earcups
            float leftVol = pan <= 0f ? 1.0f : Math.max(0.08f, 1.0f - pan * 0.92f);
            float rightVol = pan >= 0f ? 1.0f : Math.max(0.08f, 1.0f + pan * 0.92f);
            track.setStereoVolume(leftVol, rightVol);

            // Also apply in-place PCM crossfeed/panning if buffer is writable
            if (buffer != null && !buffer.isReadOnly()) {
                processAudioBuffer(buffer);
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "onAudioTrackWrite 8D failure: " + ex.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    private static void resetStereoBalanceIfNeeded() {
        if (getActiveProfile() == AudioProfile.AUDIO_8D) {
            return;
        }
        synchronized (activeAudioTracks) {
            for (AudioTrack track : activeAudioTracks) {
                if (track != null) {
                    try {
                        track.setStereoVolume(1.0f, 1.0f);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    /**
     * Real-time 16-bit stereo PCM processor for 8D Audio binaural LFO panning.
     */
    public static void processAudioBuffer(@Nullable ByteBuffer input) {
        if (input == null || getActiveProfile() != AudioProfile.AUDIO_8D) {
            return;
        }
        try {
            final int position = input.position();
            final int limit = input.limit();
            if (input == lastProcessedBuffer && limit == lastProcessedLimit && position > lastProcessedPosition) {
                lastProcessedPosition = position;
                return;
            }
            lastProcessedBuffer = input;
            lastProcessedPosition = position;
            lastProcessedLimit = limit;

            if (input.isReadOnly() || limit - position < 4) {
                return;
            }

            final int cycleSeconds = Math.max(2, SeekBarPreference.clampToRange(Settings.AUDIO_FX_8D_SPEED));
            final float depth = SeekBarPreference.clampToRange(Settings.AUDIO_FX_8D_DEPTH) / 100f;
            final double phaseStep = (2.0 * Math.PI) / (cycleSeconds * ASSUMED_SAMPLE_RATE);
            double phase = lfoPhase;

            final int end = limit - 3;
            for (int i = position; i < end; i += 4) {
                short left = (short) ((input.get(i) & 0xFF) | (input.get(i + 1) << 8));
                short right = (short) ((input.get(i + 2) & 0xFF) | (input.get(i + 3) << 8));

                float pan = (float) Math.sin(phase) * depth;
                phase += phaseStep;
                if (phase >= 2.0 * Math.PI) {
                    phase -= 2.0 * Math.PI;
                }

                float leftGain = pan <= 0f ? 1.0f : Math.max(0.08f, 1.0f - pan * 0.92f);
                float rightGain = pan >= 0f ? 1.0f : Math.max(0.08f, 1.0f + pan * 0.92f);

                float mixedLeft = (left * 0.88f + right * 0.12f) * leftGain;
                float mixedRight = (right * 0.88f + left * 0.12f) * rightGain;

                int outL = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(mixedLeft)));
                int outR = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(mixedRight)));

                input.put(i, (byte) (outL & 0xFF));
                input.put(i + 1, (byte) ((outL >> 8) & 0xFF));
                input.put(i + 2, (byte) (outR & 0xFF));
                input.put(i + 3, (byte) ((outR >> 8) & 0xFF));
            }

            lfoPhase = phase;
        } catch (Exception ex) {
            Logger.printDebug(() -> "8D PCM processing skipped: " + ex.getMessage());
        }
    }

    private static void ensureListenerRegistered() {
        if (!listenerRegistered && Setting.preferences != null && Setting.preferences.preferences != null) {
            Setting.preferences.preferences.registerOnSharedPreferenceChangeListener(PREF_LISTENER);
            listenerRegistered = true;
        }
    }

    /**
     * Injection point called when a player TabbedView is selected or updated.
     */
    public static void onTabSelected(ViewGroup tabbedView) {
        try {
            ensureListenerRegistered();
            if (tabbedView == null) return;
            int expectedId = ResourceUtils.getIdentifier(ResourceType.ID, TABBED_VIEW_ID);
            if (expectedId != 0 && tabbedView.getId() != expectedId) {
                return;
            }
            installTabStripButton(tabbedView);
        } catch (Exception ex) {
            Logger.printException(() -> "onTabSelected AudioFX failure", ex);
        }
    }

    public static void refreshPlayerTabButton() {
        try {
            Activity activity = Utils.getActivity();
            if (activity == null) return;
            View root = activity.getWindow().getDecorView();
            int id = ResourceUtils.getIdentifier(ResourceType.ID, TABBED_VIEW_ID);
            if (id != 0 && root.findViewById(id) instanceof ViewGroup tabbedView) {
                installTabStripButton(tabbedView);
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "refreshPlayerTabButton failure: " + ex.getMessage());
        }
    }

    static void installTabStripButton(ViewGroup tabbedView) {
        if (tabbedView.getChildCount() == 0) return;
        View firstChild = tabbedView.getChildAt(0);
        ViewGroup tabStrip = findTabRowContainer(firstChild);
        if (tabStrip == null) return;

        View existing = tabStrip.findViewWithTag(FX_TAB_TAG);
        boolean shouldShow = isEnabled() && Settings.AUDIO_FX_SHOW_PLAYER_TAB.get();
        if (!shouldShow) {
            if (existing != null) {
                existing.setVisibility(View.GONE);
            }
            return;
        }

        if (existing instanceof TextView fxTab) {
            fxTab.setVisibility(View.VISIBLE);
            updateTabButtonLabel(fxTab);
            return;
        }

        Context context = tabStrip.getContext();
        TextView fxButton = new TextView(context);
        fxButton.setTag(FX_TAB_TAG);
        fxButton.setGravity(Gravity.CENTER);
        fxButton.setSingleLine(true);
        fxButton.setAllCaps(true);
        fxButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        fxButton.setTypeface(Typeface.DEFAULT_BOLD);
        fxButton.setPadding(dp(12), dp(6), dp(12), dp(6));
        updateTabButtonLabel(fxButton);
        fxButton.setOnClickListener(v -> showProfileSelectorSheet(v.getContext()));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        params.gravity = Gravity.CENTER_VERTICAL;
        tabStrip.addView(fxButton, params);
    }

    static void updateTabButtonLabel(@NonNull TextView fxButton) {
        AudioProfile active = getActiveProfile();
        if (active == AudioProfile.DEFAULT) {
            fxButton.setText("FX");
            fxButton.setTextColor(0xB3FFFFFF);
        } else {
            fxButton.setText("FX: " + active.shortName);
            fxButton.setTextColor(0xFFFF4E45);
        }
    }

    @Nullable
    private static ViewGroup findTabRowContainer(View view) {
        if (view instanceof ViewGroup group) {
            int textOrTabChildren = 0;
            for (int i = 0, count = group.getChildCount(); i < count; i++) {
                View child = group.getChildAt(i);
                if (child instanceof TextView || hasTextViewChild(child)) {
                    textOrTabChildren++;
                }
            }
            if (textOrTabChildren >= 2 && group instanceof LinearLayout) {
                return group;
            }
            for (int i = 0, count = group.getChildCount(); i < count; i++) {
                ViewGroup found = findTabRowContainer(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean hasTextViewChild(View view) {
        if (view instanceof TextView) return true;
        if (view instanceof ViewGroup group) {
            for (int i = 0, count = group.getChildCount(); i < count; i++) {
                if (hasTextViewChild(group.getChildAt(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Opens a bottom sheet with all Audio FX profiles for instant selection.
     */
    public static void showProfileSelectorSheet(@NonNull Context context) {
        try {
            LinearLayout sheet = SheetBottomDialog.createMainLayout(context, 0xFF1C1C1E);
            sheet.setPadding(dp(20), dp(8), dp(20), dp(24));

            TextView header = new TextView(context);
            header.setText(str("morphe_music_audio_fx_profile_title"));
            header.setTextColor(Color.WHITE);
            header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            header.setTypeface(Typeface.DEFAULT_BOLD);
            header.setPadding(dp(4), dp(8), dp(4), dp(12));
            sheet.addView(header);

            ScrollView scrollView = new ScrollView(context);
            scrollView.setVerticalScrollBarEnabled(false);
            LinearLayout listLayout = new LinearLayout(context);
            listLayout.setOrientation(LinearLayout.VERTICAL);

            final Dialog dialog = SheetBottomDialog.createSlideDialog(context, sheet, 200);
            AudioProfile current = getActiveProfile();

            for (AudioProfile profile : AudioProfile.values()) {
                boolean isSelected = (profile == current);
                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(16), dp(14), dp(16), dp(14));

                GradientDrawable rowBg = new GradientDrawable();
                rowBg.setCornerRadius(dp(12));
                if (isSelected) {
                    rowBg.setColor(0x33FF4E45);
                    rowBg.setStroke(dp(1), 0xFFFF4E45);
                } else {
                    rowBg.setColor(0x14FFFFFF);
                }
                row.setBackground(rowBg);

                TextView label = new TextView(context);
                label.setText((isSelected ? "✓  " : "") + profile.getDisplayName());
                label.setTextColor(isSelected ? Color.WHITE : 0xFFD0D0D0);
                label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                if (isSelected) {
                    label.setTypeface(Typeface.DEFAULT_BOLD);
                }
                row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                row.setOnClickListener(v -> {
                    selectProfile(profile);
                    dialog.dismiss();
                });

                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
                rowParams.bottomMargin = dp(8);
                listLayout.addView(row, rowParams);
            }

            scrollView.addView(listLayout);
            sheet.addView(scrollView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));

            dialog.show();
        } catch (Exception ex) {
            Logger.printException(() -> "showProfileSelectorSheet failure", ex);
        }
    }

    /**
     * Injection point called when ExoPlayer initializes or switches AudioTrack session ID.
     */
    public static synchronized void onAudioSessionId(int sessionId) {
        try {
            ensureListenerRegistered();
            Utils.runOnMainThreadNowOrLater(AudioFxProfilesPatch::refreshPlayerTabButton);
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
        resetStereoBalanceIfNeeded();
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
            short targetPreset = profile.reverbPreset;
            if (profile == AudioProfile.CUSTOM) {
                int reverbScale = SeekBarPreference.clampToRange(Settings.AUDIO_FX_REVERB_LEVEL);
                if (reverbScale <= 0) {
                    targetPreset = PresetReverb.PRESET_NONE;
                } else if (reverbScale < 35) {
                    targetPreset = PresetReverb.PRESET_SMALLROOM;
                } else if (reverbScale < 70) {
                    targetPreset = PresetReverb.PRESET_MEDIUMHALL;
                } else {
                    targetPreset = PresetReverb.PRESET_LARGEHALL;
                }
            }
            if (targetPreset == PresetReverb.PRESET_NONE) {
                if (reverbEffect != null) {
                    reverbEffect.setEnabled(false);
                }
                return;
            }
            if (reverbEffect == null) {
                reverbEffect = new PresetReverb(0, activeSessionId);
            }
            reverbEffect.setPreset(targetPreset);
            reverbEffect.setEnabled(true);
        } catch (Exception ex) {
            Logger.printDebug(() -> "PresetReverb unavailable: " + ex.getMessage());
        }
    }

    private static void applyBassBoost(@NonNull AudioProfile profile) {
        try {
            short targetStrength = profile.bassStrength;
            if (profile == AudioProfile.CUSTOM) {
                int bassScale = SeekBarPreference.clampToRange(Settings.AUDIO_FX_BASS_LEVEL);
                targetStrength = (short) Math.min(1000, bassScale * 5);
            }
            if (targetStrength <= 0) {
                if (bassBoostEffect != null) {
                    bassBoostEffect.setEnabled(false);
                }
                return;
            }
            if (bassBoostEffect == null) {
                bassBoostEffect = new BassBoost(0, activeSessionId);
            }
            if (bassBoostEffect.getStrengthSupported()) {
                bassBoostEffect.setStrength(targetStrength);
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
            float customBassMultiplier = SeekBarPreference.clampToRange(Settings.AUDIO_FX_BASS_LEVEL) / 100f;

            for (short band = 0; band < bands; band++) {
                int centerFreqMilliHz = equalizerEffect.getCenterFreq(band);
                int freqHz = centerFreqMilliHz / 1000;
                short targetLevel = computeBandLevel(profile, freqHz, minLevel, maxLevel);
                if (profile == AudioProfile.CUSTOM && freqHz <= 200) {
                    targetLevel = (short) Math.min(maxLevel, Math.round((maxLevel * 0.5f) * customBassMultiplier));
                }
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
                if (freqHz <= 150) return (short) (maxLevel * 0.45f);
                if (freqHz <= 450) return 0;
                return minLevel;
            case LOFI_STUDY:
                if (freqHz < 120) return (short) (minLevel * 0.5f);
                if (freqHz <= 2500) return (short) (maxLevel * 0.3f);
                return (short) (minLevel * 0.75f);
            case NIGHTCORE:
                if (freqHz < 150) return (short) (maxLevel * 0.25f);
                if (freqHz > 4000) return (short) (maxLevel * 0.35f);
                return 0;
            case SLOWED_REVERB:
            case DAYCORE:
                if (freqHz <= 200) return (short) (maxLevel * 0.4f);
                if (freqHz > 5000) return (short) (minLevel * 0.35f);
                return 0;
            case VAPORWAVE:
                if (freqHz <= 250) return (short) (maxLevel * 0.3f);
                if (freqHz >= 1000 && freqHz <= 3500) return (short) (minLevel * 0.25f);
                return (short) (maxLevel * 0.15f);
            case LIVE_CONCERT:
                if (freqHz <= 160) return (short) (maxLevel * 0.45f);
                if (freqHz >= 3000) return (short) (maxLevel * 0.35f);
                return 0;
            case AUDIO_8D:
                if (freqHz >= 1500) return (short) (maxLevel * 0.3f);
                return (short) (maxLevel * 0.15f);
            case BASS_BOOSTED:
                if (freqHz <= 120) return maxLevel;
                if (freqHz <= 250) return (short) (maxLevel * 0.6f);
                return 0;
            case CUSTOM:
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
