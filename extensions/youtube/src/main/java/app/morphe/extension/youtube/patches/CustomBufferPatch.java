package app.morphe.extension.youtube.patches;

import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch;
import app.morphe.extension.shared.spoof.requests.StreamingDataRequest;
import app.morphe.extension.youtube.settings.Settings;

/**
 * mpv-style buffering for the YouTube player:
 * <ul>
 *   <li>forward buffer limit with hysteresis (like demuxer-max-secs + demuxer-hysteresis-secs)</li>
 *   <li>back buffer (like demuxer-max-back-secs)</li>
 *   <li>donate unused forward buffer to the back buffer (like demuxer-donate-buffer=yes)</li>
 *   <li>RAM limit for the whole buffer (like demuxer-max-bytes)</li>
 *   <li>optionally no media disk cache, so the buffer stays in RAM only</li>
 * </ul>
 */
@SuppressWarnings("unused")
public final class CustomBufferPatch {

    /**
     * Added to the player load control by the patch.
     */
    public interface LoadControlAccess {
        /** Bytes the buffer allocator currently holds (forward and back buffer). */
        int patch_getAllocatedBytes();

        /** The allocator byte target (RAM limit), or 0 if not set yet. */
        int patch_getTargetBytes();
    }

    private static final long US_PER_SECOND = 1_000_000L;

    /** Below this the player is about to stall, so always load. Same as the player itself. */
    private static final long BUFFER_EMPTY_US = 500_000L;

    private static final int MIN_RAM_MB = 16;
    private static final int MAX_RAM_MB = 384;

    private static final class LoadState {
        boolean loading = true;
    }

    /** Hysteresis state for each load control (player). */
    private static final Map<Object, LoadState> loadStates = new WeakHashMap<>();

    /** Forward buffered duration from the last loading decision. */
    private static volatile long lastForwardUs;

    /** True if the buffer uses at least 7/8 of the RAM limit. */
    private static volatile boolean memoryTight;

    private static long forwardMaxUs() {
        return Math.max(1, Settings.CUSTOM_BUFFER_FORWARD_MAX.get()) * US_PER_SECOND;
    }

    private static long resumeBelowUs() {
        final long resumeUs = Math.max(0, Settings.CUSTOM_BUFFER_RESUME_BELOW.get()) * US_PER_SECOND;
        return Math.min(resumeUs, forwardMaxUs());
    }

    /**
     * SABR streams are paced by the server, so they use a simpler profile and the
     * loading decision of the app. Plain stream urls (visionOS, TV Simply) use the full profile.
     * The streams of the app itself (spoofing off, or no spoofed client) are SABR.
     */
    private static boolean isSabr() {
        if (!SpoofVideoStreamsPatch.isSpoofingEnabled()) {
            return true;
        }
        return !StreamingDataRequest.playerClientUsesStreamUrls(VideoInformation.getVideoId());
    }

    private static LoadState getLoadState(Object loadControl) {
        synchronized (loadStates) {
            LoadState state = loadStates.get(loadControl);
            if (state == null) {
                state = new LoadState();
                loadStates.put(loadControl, state);
            }
            return state;
        }
    }

    /**
     * Injection point: shouldContinueLoading.
     *
     * @param bufferedUs Forward buffered duration, before any other patch scales it.
     * @return -1 to use the original logic, 0 to stop loading, 1 to keep loading.
     */
    public static int shouldContinueLoading(Object loadControl, long bufferedUs) {
        try {
            if (!Settings.CUSTOM_BUFFER.get()) {
                return -1;
            }

            lastForwardUs = bufferedUs;

            int allocatedBytes = 0;
            int targetBytes = 0;
            if (loadControl instanceof LoadControlAccess) {
                LoadControlAccess access = (LoadControlAccess) loadControl;
                allocatedBytes = access.patch_getAllocatedBytes();
                targetBytes = access.patch_getTargetBytes();
            }
            memoryTight = targetBytes > 0 && allocatedBytes >= targetBytes - targetBytes / 8;

            if (isSabr()) {
                // Keep the server pacing of the app, only cap the forward buffer.
                final long sabrForwardMaxUs = Math.max(1, Settings.CUSTOM_BUFFER_SABR_FORWARD_MAX.get()) * US_PER_SECOND;
                return bufferedUs >= sabrForwardMaxUs ? 0 : -1;
            }

            LoadState state = getLoadState(loadControl);

            if (bufferedUs < BUFFER_EMPTY_US) {
                state.loading = true;
                return 1;
            }

            // RAM limit always wins.
            if (targetBytes > 0 && allocatedBytes >= targetBytes) {
                state.loading = false;
                return 0;
            }

            // Hysteresis: load up to the forward limit, then wait until the buffer
            // drops below the resume point before loading again.
            if (bufferedUs >= forwardMaxUs()) {
                state.loading = false;
            } else if (bufferedUs < resumeBelowUs()) {
                state.loading = true;
            }

            return state.loading ? 1 : 0;
        } catch (Exception ex) {
            Logger.printException(() -> "shouldContinueLoading failure", ex);
            return -1;
        }
    }

    /**
     * Injection point: getBackBufferDurationUs. The player reads this once when it is created.
     */
    public static long getBackBufferUs(long originalUs) {
        if (!Settings.CUSTOM_BUFFER.get()) {
            return originalUs;
        }
        // Read only once when the player is created, and the player can play both kinds
        // of streams, so this is the larger one. The discard hooks use the value of each kind.
        final int backSeconds = Math.max(Settings.CUSTOM_BUFFER_BACK.get(), Settings.CUSTOM_BUFFER_SABR_BACK.get());
        return Math.max(0, backSeconds) * US_PER_SECOND;
    }

    /**
     * Injection point: the player discards everything older than (position - returned value).
     * <p>
     * With donate on, the back buffer also gets the unused part of the forward buffer,
     * so played data stays until new data is loaded:
     * back = backBuffer + (forwardMax - forwardBuffered).
     */
    public static long getDiscardBackBufferUs(long backBufferUs) {
        try {
            if (!Settings.CUSTOM_BUFFER.get()) {
                return backBufferUs;
            }
            if (isSabr()) {
                // No donation for SABR.
                return Math.max(0, Settings.CUSTOM_BUFFER_SABR_BACK.get()) * US_PER_SECOND;
            }
            final long baseUs = Math.max(0, Settings.CUSTOM_BUFFER_BACK.get()) * US_PER_SECOND;
            if (!Settings.CUSTOM_BUFFER_DONATE.get() || memoryTight) {
                return baseUs;
            }
            final long unusedForwardUs = Math.max(0, forwardMaxUs() - lastForwardUs);
            return baseUs + unusedForwardUs;
        } catch (Exception ex) {
            Logger.printException(() -> "getDiscardBackBufferUs failure", ex);
            return backBufferUs;
        }
    }

    /**
     * Injection point: the allocator byte target (RAM limit of the whole buffer).
     */
    public static int getByteLimit(int originalBytes) {
        if (!Settings.CUSTOM_BUFFER.get()) {
            return originalBytes;
        }
        final int ramMb = Math.max(MIN_RAM_MB, Math.min(MAX_RAM_MB, Settings.CUSTOM_BUFFER_RAM_MB.get()));
        return ramMb * 1024 * 1024;
    }

    /**
     * Injection point: true to use an empty media cache instead of the disk cache.
     */
    public static boolean disableDiskCache() {
        return Settings.CUSTOM_BUFFER_DISABLE_DISK_CACHE.get();
    }
}
