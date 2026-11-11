/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4451
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.soundproof;

import static app.morphe.extension.shared.StringRef.str;

import android.media.MediaMetadata;
import android.media.session.MediaSession;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import app.morphe.extension.shared.settings.preference.SeekBarPreference;

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

    @Nullable
    private static SoundProofManager instance;

    private final SoundProofDatabaseLoader databaseLoader;
    private final List<StateListener> listeners = new ArrayList<>();

    /** Null until the first database is loaded. */
    @Nullable
    private ArtistDirectory directory;
    private ArtistAssessment currentAssessment = ArtistAssessment.unavailable();
    /** Artist credit of the current track, null until a track with a credit plays. */
    @Nullable
    private String currentCredit;

    @Nullable
    private MediaSession currentSession;
    /** {@code MediaSession.setMetadata} runs several times per track, the track is assessed once. */
    @Nullable
    private String assessedTrackKey;

    /** Server votes by artist id, applied over the dump. */
    private final Map<String, LiveOverride> liveOverrides = new HashMap<>();
    /** Artist read from the server for the current track, null until the sheet asks for it. */
    @Nullable
    private VoteTarget liveTarget;
    private LiveState liveState = LiveState.LOADING;
    @Nullable
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
    @Nullable
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
     * Reads the current artist from the server, unless it was already read, successfully or not.
     * It does not tell the listeners that the state is now {@link LiveState#LOADING}, so it can
     * be called while rendering.
     */
    public void loadLiveStatus() {
        Optional<VoteTarget> target = getVoteTarget();
        if (!target.isPresent() || target.get().equals(liveTarget)) {
            return;
        }
        VoteTarget requested = target.get();
        liveTarget = requested;
        liveState = LiveState.LOADING;
        liveStatus = null;
        Utils.runOnBackgroundThread(() -> fetchLiveStatus(requested));
    }

    /**
     * Same as {@link #loadLiveStatus()}, but also reads an artist that was already read, for fresh
     * votes and to retry a failed read.
     */
    public void reloadLiveStatus() {
        liveTarget = null;
        loadLiveStatus();
    }

    /**
     * Must be called off the main thread.
     */
    private void fetchLiveStatus(VoteTarget target) {
        try {
            Optional<LiveArtistStatus> status = SoundProofApi.fetchStatus(target);
            Utils.runOnMainThread(() -> onLiveStatusLoaded(target, status));
        } catch (IOException ex) {
            Logger.printInfo(() -> "SoundProof status request failed", ex);
            Utils.runOnMainThread(() -> onLiveStatusFailed(target));
        }
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
        if (!target.isPresent() || isVoting || getLiveState() != LiveState.READY || isVoteLocked()) {
            return;
        }
        VoteTarget voted = target.get();
        isVoting = true;
        notifyListeners();
        Utils.runOnBackgroundThread(() -> {
            try {
                SoundProofApi.vote(voted, side);
            } catch (IOException ex) {
                Logger.printInfo(() -> "SoundProof vote failed", ex);
                Utils.runOnMainThread(() -> {
                    isVoting = false;
                    Utils.showToastShort(str("morphe_music_soundproof_vote_failed_toast"));
                    notifyListeners();
                });
                return;
            }
            // The vote is in, so a read that fails from here on is not a failed vote.
            fetchLiveStatus(voted);
        });
    }

    private void onLiveStatusLoaded(VoteTarget target, Optional<LiveArtistStatus> status) {
        isVoting = false;
        if (status.isPresent()) {
            ArtistRecord fresh = status.get().toRecord(target);
            liveOverrides.put(fresh.id(), new LiveOverride(fresh, System.currentTimeMillis()));
        } else {
            liveOverrides.remove(target.id());
        }
        // The track can have changed while the request ran: the override still applies.
        if (target.equals(liveTarget)) {
            liveState = LiveState.READY;
            liveStatus = status.orElse(null);
        }
        reassessWithoutSkip();
    }

    private void onLiveStatusFailed(VoteTarget target) {
        isVoting = false;
        if (target.equals(liveTarget)) {
            liveState = LiveState.FAILED;
        }
        notifyListeners();
    }

    /**
     * Updates the verdict shown for the current track after its data changed, without skipping it.
     */
    private void reassessWithoutSkip() {
        currentAssessment = assess();
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
        databaseLoader.requestDatabase();

        String credit = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        // Metadata without an artist can come between two with the same track. Taking it for a new
        // track would assess the same track again, and a second skip would also skip the next one.
        if (credit == null || credit.trim().isEmpty()) {
            return;
        }
        String trackKey = trackKeyOf(metadata, credit);
        if (trackKey.equals(assessedTrackKey)) {
            return;
        }
        assessedTrackKey = trackKey;
        currentCredit = credit;
        assessCurrentTrack();
    }

    private void onDatabaseLoaded(ArtistDirectory loadedDirectory) {
        boolean isFirstDatabase = directory == null;
        directory = loadedDirectory;
        Logger.printDebug(() -> "SoundProof database ready: " + loadedDirectory.size() + " artists");
        if (isFirstDatabase) {
            // Until now the current track could not be assessed.
            assessCurrentTrack();
        } else {
            reassessWithoutSkip();
        }
    }

    private void assessCurrentTrack() {
        currentAssessment = assess();
        Logger.printDebug(() -> "SoundProof: " + currentCredit + " -> " + currentAssessment);
        notifyListeners();

        if (currentAssessment.status() == ArtistStatus.AI && isSkipEnabled()) {
            skipCurrentTrack(currentAssessment.artist().get());
        }
    }

    private ArtistAssessment assess() {
        if (directory == null) {
            return ArtistAssessment.unavailable();
        }
        if (currentCredit == null) {
            return ArtistAssessment.unknown();
        }
        return new ArtistAssessor(directory.withArtists(activeOverrides()), policyFromSettings()).assess(currentCredit);
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
        return mediaId + '\n' + title + '\n' + credit;
    }

    private static AiDetectionPolicy policyFromSettings() {
        return new AiDetectionPolicy(
                SeekBarPreference.clampToRange(Settings.SOUNDPROOF_MINIMUM_AI_PERCENT),
                SeekBarPreference.clampToRange(Settings.SOUNDPROOF_MINIMUM_VOTES)
        );
    }
}
