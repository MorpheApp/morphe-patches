/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches.soundproof;

import static app.morphe.extension.shared.StringRef.str;

import android.media.MediaMetadata;
import android.media.session.MediaSession;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import app.morphe.extension.music.patches.soundproof.data.SoundProofDatabaseLoader;
import app.morphe.extension.music.patches.soundproof.data.SoundProofDumpStore;
import app.morphe.extension.music.patches.soundproof.domain.AiDetectionPolicy;
import app.morphe.extension.music.patches.soundproof.domain.ArtistAssessment;
import app.morphe.extension.music.patches.soundproof.domain.ArtistAssessor;
import app.morphe.extension.music.patches.soundproof.domain.ArtistDirectory;
import app.morphe.extension.music.patches.soundproof.domain.ArtistRecord;
import app.morphe.extension.music.patches.soundproof.domain.ArtistStatus;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.IntegerSetting;

/**
 * Assesses each new track and skips it when its artist is AI.
 * <p>
 * All methods must be called on the main thread.
 */
public final class SoundProofManager {

    /**
     * Told on the main thread whenever the assessment of the current track or the skip setting
     * changes. Listeners read the new state from the manager.
     */
    public interface StateListener {
        void onStateChanged();
    }

    private static final String STORAGE_DIRECTORY_NAME = "soundproof";

    private static SoundProofManager instance;

    private final SoundProofDatabaseLoader databaseLoader;
    private final List<StateListener> listeners = new ArrayList<>();

    /** Null until the first database is loaded. */
    private ArtistDirectory directory;
    private ArtistAssessment currentAssessment = ArtistAssessment.unavailable();
    /** Artist credit of the current track, null until a track with a credit plays. */
    private String currentCredit;

    private MediaSession currentSession;
    private MediaMetadata currentMetadata;
    /** {@code MediaSession.setMetadata} runs several times per track, the track is assessed once. */
    private String assessedTrackKey;

    public static SoundProofManager getInstance() {
        Utils.verifyOnMainThread();
        if (instance == null) {
            instance = new SoundProofManager();
        }
        return instance;
    }

    private SoundProofManager() {
        File storageDirectory = new File(Utils.getContext().getFilesDir(), STORAGE_DIRECTORY_NAME);
        databaseLoader = new SoundProofDatabaseLoader(new SoundProofDumpStore(storageDirectory), this::onDatabaseLoaded);
    }

    public ArtistAssessment getCurrentAssessment() {
        return currentAssessment;
    }

    /**
     * @return The artist credit of the current track, as shown by the app, or null.
     */
    public String getCurrentCredit() {
        return currentCredit;
    }

    public boolean isSkipEnabled() {
        return Settings.SOUNDPROOF_SKIP_AI_ARTISTS.get();
    }

    /**
     * Saves the skip setting. The current track is not assessed again, so turning the skip on
     * while an AI track plays lets it finish.
     */
    public void setSkipEnabled(boolean isEnabled) {
        if (isEnabled == isSkipEnabled()) {
            return;
        }
        Settings.SOUNDPROOF_SKIP_AI_ARTISTS.save(isEnabled);
        notifyListeners();
    }

    public void addListener(StateListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(StateListener listener) {
        listeners.remove(listener);
    }

    public void onSetMetadata(MediaSession session, MediaMetadata metadata) {
        currentSession = session;
        currentMetadata = metadata;
        databaseLoader.requestDatabase();
        assessCurrentTrack();
    }

    private void onDatabaseLoaded(ArtistDirectory loadedDirectory) {
        directory = loadedDirectory;
        Logger.printDebug(() -> "SoundProof database ready: " + loadedDirectory.size() + " artists");
        // The current track was assessed without this database, assess it again.
        assessedTrackKey = null;
        assessCurrentTrack();
    }

    private void assessCurrentTrack() {
        MediaMetadata metadata = currentMetadata;
        if (metadata == null) {
            return;
        }
        String credit = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (credit == null || credit.trim().isEmpty()) {
            return;
        }
        String trackKey = trackKeyOf(metadata, credit);
        if (trackKey.equals(assessedTrackKey)) {
            return;
        }
        assessedTrackKey = trackKey;
        currentCredit = credit;
        currentAssessment = assess(credit);
        Logger.printDebug(() -> "SoundProof: " + credit + " -> " + currentAssessment);
        notifyListeners();

        if (currentAssessment.status() == ArtistStatus.AI && isSkipEnabled()) {
            skipCurrentTrack(currentAssessment.artist().get());
        }
    }

    private ArtistAssessment assess(String credit) {
        if (directory == null) {
            return ArtistAssessment.unavailable();
        } else {
            return new ArtistAssessor(directory, policyFromSettings()).assess(credit);
        }
    }

    private void notifyListeners() {
        // Copied, since a listener can remove itself while being told.
        for (StateListener listener : new ArrayList<>(listeners)) {
            try {
                listener.onStateChanged();
            } catch (Exception ex) {
                Logger.printException(() -> "SoundProof listener failure", ex);
            }
        }
    }

    private void skipCurrentTrack(ArtistRecord aiArtist) {
        // Going through the MediaSession is the only skip that advances the native queue on 9.x.
        currentSession.getController().getTransportControls().skipToNext();
        if (Settings.SOUNDPROOF_TOAST_ON_SKIP.get()) {
            Utils.showToastShort(str("morphe_music_soundproof_skipped_toast", aiArtist.name(), aiArtist.aiVotePercent()));
        }
    }

    private static String trackKeyOf(MediaMetadata metadata, String credit) {
        String mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        return Objects.toString(mediaId, "") + '\n' + Objects.toString(title, "") + '\n' + credit;
    }

    /**
     * Settings are validated here because an imported backup can hold any value.
     */
    private static AiDetectionPolicy policyFromSettings() {
        int minimumAiPercent = validSettingOrDefault(Settings.SOUNDPROOF_MINIMUM_AI_PERCENT, 1, 100);
        int minimumVotes = validSettingOrDefault(Settings.SOUNDPROOF_MINIMUM_VOTES, 1, Integer.MAX_VALUE);
        return new AiDetectionPolicy(minimumAiPercent, minimumVotes);
    }

    private static int validSettingOrDefault(IntegerSetting setting, int minimum, int maximum) {
        int value = setting.get();
        if (value < minimum || value > maximum) {
            Logger.printInfo(() -> "Resetting invalid " + setting.key + ": " + value);
            setting.resetToDefault();
            return setting.defaultValue;
        } else {
            return value;
        }
    }
}
