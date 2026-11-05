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
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.music.patches.soundproof.data.SoundProofApi;
import app.morphe.extension.music.patches.soundproof.data.SoundProofDatabaseLoader;
import app.morphe.extension.music.patches.soundproof.data.SoundProofDumpStore;
import app.morphe.extension.music.patches.soundproof.domain.AiDetectionPolicy;
import app.morphe.extension.music.patches.soundproof.domain.ArtistAssessment;
import app.morphe.extension.music.patches.soundproof.domain.ArtistAssessor;
import app.morphe.extension.music.patches.soundproof.domain.ArtistDirectory;
import app.morphe.extension.music.patches.soundproof.domain.ArtistRecord;
import app.morphe.extension.music.patches.soundproof.domain.ArtistStatus;
import app.morphe.extension.music.patches.soundproof.domain.LiveArtistStatus;
import app.morphe.extension.music.patches.soundproof.domain.VoteMajority.VoteSide;
import app.morphe.extension.music.patches.soundproof.domain.VoteTarget;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.IntegerSetting;

/**
 * Assesses each new track and skips it when its artist is AI.
 * <p>
 * The verdict comes from the daily dump. While the sheet is open, the votes of the current
 * artist are also read from the SoundProof server, and they replace the dump entry for a day,
 * so that the button, the sheet and the skip agree.
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

    /** Progress of reading the current artist from the SoundProof server. */
    public enum LiveState {
        LOADING,
        READY,
        FAILED
    }

    private static final String STORAGE_DIRECTORY_NAME = "soundproof";
    /** Server votes replace the dump entry until a newer dump is likely downloaded. */
    private static final long LIVE_OVERRIDE_LIFETIME_MILLIS = TimeUnit.HOURS.toMillis(24);

    private record LiveOverride(ArtistRecord artist, long fetchedAtMillis) {
    }

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

    /** Server votes by artist id, applied over the dump. */
    private final Map<String, LiveOverride> liveOverrides = new HashMap<>();
    /** Artist read from the server for the current track, null until the sheet asks for it. */
    private VoteTarget liveTarget;
    private LiveState liveState = LiveState.LOADING;
    private LiveArtistStatus liveStatus;
    private boolean isVoting;

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

    /**
     * @return The artist a vote applies to, empty while no track with a credit plays.
     */
    public Optional<VoteTarget> getVoteTarget() {
        return VoteTarget.of(currentAssessment, currentCredit);
    }

    /**
     * Reads the current artist from the server, unless it is already read or being read.
     * A failed read is retried on the next call. It does not tell the listeners that the state
     * is now {@link LiveState#LOADING}, so it can be called while rendering.
     */
    public void loadLiveStatus() {
        Optional<VoteTarget> target = getVoteTarget();
        if (target.isEmpty()) {
            return;
        }
        if (target.get().equals(liveTarget) && liveState != LiveState.FAILED) {
            return;
        }
        VoteTarget requested = target.get();
        liveTarget = requested;
        liveState = LiveState.LOADING;
        liveStatus = null;
        Utils.runOnBackgroundThread(() -> {
            try {
                Optional<LiveArtistStatus> status = SoundProofApi.fetchStatus(requested);
                Utils.runOnMainThread(() -> onLiveStatusLoaded(requested, status));
            } catch (IOException ex) {
                Logger.printInfo(() -> "SoundProof status request failed", ex);
                Utils.runOnMainThread(() -> onLiveStatusFailed(requested));
            }
        });
    }

    /**
     * @return The progress of {@link #loadLiveStatus()} for the current artist.
     */
    public LiveState getLiveState() {
        if (liveTarget == null || !getVoteTarget().map(liveTarget::equals).orElse(false)) {
            return LiveState.LOADING;
        }
        return liveState;
    }

    /**
     * @return The vote of this device for the current artist, empty when unknown or none.
     */
    public Optional<VoteSide> getMyVote() {
        if (getLiveState() != LiveState.READY || liveStatus == null) {
            return Optional.empty();
        }
        return liveStatus.myVote();
    }

    /**
     * @return True when a maintainer checked the artist, which closes the vote.
     */
    public boolean isVoteLocked() {
        if (getLiveState() == LiveState.READY && liveStatus != null) {
            return liveStatus.verified();
        }
        return currentAssessment.artist().map(ArtistRecord::verified).orElse(false);
    }

    public boolean isVoting() {
        return isVoting;
    }

    /**
     * Votes for the current artist, or removes the vote when it is for the same side.
     * The current track is not skipped, whatever the new votes say.
     */
    public void vote(VoteSide side) {
        Optional<VoteTarget> target = getVoteTarget();
        if (target.isEmpty() || isVoting || getLiveState() != LiveState.READY || isVoteLocked()) {
            return;
        }
        VoteTarget voted = target.get();
        isVoting = true;
        notifyListeners();
        Utils.runOnBackgroundThread(() -> {
            try {
                SoundProofApi.vote(voted, side);
                Optional<LiveArtistStatus> status = SoundProofApi.fetchStatus(voted);
                Utils.runOnMainThread(() -> {
                    isVoting = false;
                    onLiveStatusLoaded(voted, status);
                });
            } catch (IOException ex) {
                Logger.printInfo(() -> "SoundProof vote failed", ex);
                Utils.runOnMainThread(() -> {
                    isVoting = false;
                    Utils.showToastShort(str("morphe_music_soundproof_vote_failed_toast"));
                    notifyListeners();
                });
            }
        });
    }

    private void onLiveStatusLoaded(VoteTarget target, Optional<LiveArtistStatus> status) {
        if (status.isPresent()) {
            ArtistRecord fresh = status.get().toRecord(target);
            liveOverrides.put(fresh.id(), new LiveOverride(fresh, System.currentTimeMillis()));
        }
        // The track can have changed while the request ran: the override still applies.
        if (target.equals(liveTarget)) {
            liveState = LiveState.READY;
            liveStatus = status.orElse(null);
        }
        reassessWithoutSkip();
        notifyListeners();
    }

    private void onLiveStatusFailed(VoteTarget target) {
        if (target.equals(liveTarget)) {
            liveState = LiveState.FAILED;
            notifyListeners();
        }
    }

    /**
     * Updates the verdict shown for the current track after the votes changed.
     */
    private void reassessWithoutSkip() {
        if (currentCredit != null) {
            currentAssessment = assess(currentCredit);
        }
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
            return new ArtistAssessor(directory.withArtists(activeOverrides()), policyFromSettings()).assess(credit);
        }
    }

    private List<ArtistRecord> activeOverrides() {
        long oldestMillis = System.currentTimeMillis() - LIVE_OVERRIDE_LIFETIME_MILLIS;
        liveOverrides.values().removeIf(override -> override.fetchedAtMillis() < oldestMillis);
        List<ArtistRecord> artists = new ArrayList<>(liveOverrides.size());
        for (LiveOverride override : liveOverrides.values()) {
            artists.add(override.artist());
        }
        return artists;
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
