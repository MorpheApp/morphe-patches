package app.morphe.extension.youtube.patches.playback.speed;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class ReplayVideoControllerTest {
    private static final class Player implements ReplayVideoController.Player {
        String id = "video-one";
        long position = 60000;
        long elapsed;
        float speed = 2;
        boolean seekSucceeds = true;
        boolean asynchronousSeek;
        boolean speedChangeAvailable = true;
        boolean failSeek;
        boolean failAfterChangingSpeed;
        boolean queueSpeedEvents;
        long seekTarget = -1;
        List<Float> changes = new ArrayList<>();
        List<Float> speedEvents = new ArrayList<>();
        ReplayVideoController replay = new ReplayVideoController(this);

        @Override public String videoId() { return id; }
        @Override public long position() { return position; }
        @Override public float speed() { return speed; }
        @Override public long elapsedRealtime() { return elapsed; }

        @Override public boolean seekTo(long position) {
            // Native hooks are synchronous, including for the replay's own operations.
            replay.onSeek();
            seekTarget = position;
            if (failSeek) throw new IllegalStateException("Seek failed");
            if (seekSucceeds && !asynchronousSeek) this.position = position;
            return seekSucceeds;
        }

        @Override public void changeSpeed(float speed) {
            if (!speedChangeAvailable) return;
            changes.add(speed);
            this.speed = speed;
            if (queueSpeedEvents) speedEvents.add(speed);
            else replay.onSpeedChanged(speed);
            if (failAfterChangingSpeed) {
                failAfterChangingSpeed = false;
                throw new IllegalStateException("Speed change failed after applying");
            }
        }

        void deliverSpeedEvents() {
            List<Float> pending = new ArrayList<>(speedEvents);
            speedEvents.clear();
            pending.forEach(replay::onSpeedChanged);
        }
    }

    @Test public void replaysOnceAndRestoresTheActualPreviousSpeed() {
        Player p = new Player();
        p.speed = 2.5f;
        assertTrue(p.replay.start(5000, 5000, 1));
        assertEquals(55000, p.seekTarget);
        assertEquals(1, p.speed, 0);
        p.position = 59999;
        p.replay.tick();
        assertTrue(p.replay.isActive());
        p.position = 60000;
        p.replay.tick();
        assertFalse(p.replay.isActive());
        assertEquals(List.of(1.0f, 2.5f), p.changes);
        p.replay.tick();
        assertEquals(2, p.changes.size());
    }

    @Test public void swipeSelectedRewindKeepsTheIndependentSlowDuration() {
        Player p = new Player();
        assertTrue(p.replay.start(10000, 2000, 1));
        assertEquals(50000, p.position);
        p.position = 51999;
        p.replay.tick();
        assertEquals(1, p.speed, 0);
        p.position = 52000;
        p.replay.tick();
        assertEquals(2, p.speed, 0);
        assertFalse(p.replay.isActive());
    }

    @Test public void anotherSwipeCanRestoreThenRestartWithoutLosingTheOriginalSpeed() {
        Player p = new Player();
        p.speed = 2.5f;
        assertTrue(p.replay.start(4000, 2000, 1));
        p.position = 57000;
        p.replay.cancel(true);
        assertTrue(p.replay.start(1000, 2000, 1));
        assertEquals(56000, p.position);
        p.position = 58000;
        p.replay.tick();
        assertEquals(2.5f, p.speed, 0);
        assertEquals(List.of(1.0f, 2.5f, 1.0f, 2.5f), p.changes);
    }

    @Test public void repeatedSwipesKeepOneOriginalSpeedWithoutIntermediateRestores() {
        Player p = new Player();
        p.speed = 2.5f;
        assertTrue(p.replay.start(4000, 2000, 1));
        p.position = 57000;
        assertTrue(p.replay.start(1000, 2000, 1));
        p.position = 56500;
        assertTrue(p.replay.start(3000, 2000, 1));
        assertEquals(53500, p.seekTarget);
        p.position = 55499;
        p.replay.tick();
        assertEquals(1, p.speed, 0);
        p.position = 55500;
        p.replay.tick();
        assertFalse(p.replay.isActive());
        assertEquals(2.5f, p.speed, 0);
        assertEquals(List.of(1.0f, 2.5f), p.changes);
    }

    @Test public void delayedOldSpeedEventsDoNotCancelTheNextReplay() {
        Player p = new Player();
        p.queueSpeedEvents = true;
        assertTrue(p.replay.start(4000, 2000, 1));
        p.deliverSpeedEvents();
        p.replay.cancel(true);
        assertTrue(p.replay.start(1000, 2000, 1));
        // The old restore notification arrives after the next replay is already slow.
        p.deliverSpeedEvents();
        assertTrue(p.replay.isActive());
        p.position += 2000;
        p.replay.tick();
        assertEquals(2, p.speed, 0);
    }

    @Test public void aFailedRepeatedSeekRestoresTheOriginalSpeed() {
        Player p = new Player();
        assertTrue(p.replay.start(4000, 2000, 1));
        p.seekSucceeds = false;
        assertFalse(p.replay.start(1000, 2000, 1));
        assertFalse(p.replay.isActive());
        assertEquals(2, p.speed, 0);
    }

    @Test public void aManualSpeedChoiceBetweenSwipesBecomesTheNewOriginalSpeed() {
        Player p = new Player();
        assertTrue(p.replay.start(4000, 2000, 1));
        p.replay.onUserSelectedSpeed();
        p.changeSpeed(1.5f);
        assertTrue(p.replay.start(1000, 2000, 1));
        p.position += 2000;
        p.replay.tick();
        assertEquals(1.5f, p.speed, 0);
    }

    @Test public void clampsRewindAtTheStartOfTheVideo() {
        Player p = new Player();
        p.position = 2000;
        assertTrue(p.replay.start(5000, 2000, 0.75f));
        assertEquals(0, p.seekTarget);
        p.position = 2000;
        p.replay.tick();
        assertEquals(2, p.speed, 0);
        assertFalse(p.replay.isActive());
    }

    @Test public void pauseAndBufferingDoNotConsumeTheReplaySegment() {
        Player p = new Player();
        assertTrue(p.replay.start(5000, 5000, 1));
        p.elapsed = 600000;
        p.replay.tick();
        assertTrue(p.replay.isActive());
        assertEquals(1, p.speed, 0);
        p.position = 59000;
        p.replay.tick();
        assertTrue(p.replay.isActive());
        p.position = 60100;
        p.replay.tick();
        assertEquals(2, p.speed, 0);
    }

    @Test public void waitsForTheSeekBeforeAcceptingTheOriginalTimestamp() {
        Player p = new Player();
        p.asynchronousSeek = true;
        assertTrue(p.replay.start(5000, 5000, 1));
        p.replay.tick();
        assertTrue(p.replay.isActive());
        p.position = 60100;
        p.replay.tick();
        assertTrue(p.replay.isActive());
        p.position = 55000;
        p.replay.tick();
        p.position = 60000;
        p.replay.tick();
        assertFalse(p.replay.isActive());
        assertEquals(2, p.speed, 0);
    }

    @Test public void abandonsAnUnacknowledgedSeekAndRestoresSpeed() {
        Player p = new Player();
        p.asynchronousSeek = true;
        assertTrue(p.replay.start(5000, 5000, 1));
        p.elapsed = 4999;
        p.replay.tick();
        assertTrue(p.replay.isActive());
        p.elapsed = 5000;
        p.replay.tick();
        assertFalse(p.replay.isActive());
        assertEquals(2, p.speed, 0);
    }

    @Test public void failedSeekDoesNotChangeTheSpeed() {
        Player p = new Player();
        p.seekSucceeds = false;
        assertFalse(p.replay.start(5000, 5000, 1));
        assertFalse(p.replay.isActive());
        assertTrue(p.changes.isEmpty());
    }

    @Test public void unavailableSpeedControllerDoesNotLeaveAnActiveReplay() {
        Player p = new Player();
        p.speedChangeAvailable = false;
        assertFalse(p.replay.start(5000, 5000, 1));
        assertFalse(p.replay.isActive());
        assertEquals(2, p.speed, 0);
    }

    @Test public void seekExceptionClearsTheReplayWithoutChangingSpeed() {
        Player p = new Player();
        p.failSeek = true;
        assertThrows(IllegalStateException.class, () -> p.replay.start(5000, 5000, 1));
        assertFalse(p.replay.isActive());
        assertTrue(p.changes.isEmpty());
    }

    @Test public void exceptionAfterApplyingSpeedRestoresTheOriginalSpeed() {
        Player p = new Player();
        p.failAfterChangingSpeed = true;
        assertThrows(IllegalStateException.class, () -> p.replay.start(5000, 5000, 1));
        assertFalse(p.replay.isActive());
        assertEquals(2, p.speed, 0);
        assertEquals(List.of(1.0f, 2.0f), p.changes);
    }

    @Test public void manualSeekCancelsAndRestoresWithoutAnotherSeek() {
        Player p = new Player();
        assertTrue(p.replay.start(5000, 5000, 1));
        p.replay.onSeek();
        p.position = 57000;
        p.replay.tick();
        assertFalse(p.replay.isActive());
        assertEquals(2, p.speed, 0);
        assertEquals(55000, p.seekTarget);
    }

    @Test public void selectingTheTemporarySpeedManuallyKeepsThatSelection() {
        Player p = new Player();
        assertTrue(p.replay.start(5000, 5000, 1));
        p.replay.onUserSelectedSpeed();
        p.changeSpeed(1);
        p.position = 60000;
        p.replay.tick();
        assertFalse(p.replay.isActive());
        assertEquals(1, p.speed, 0);
    }

    @Test public void anotherSpeedChangeWinsOverAutomaticRestoration() {
        Player p = new Player();
        assertTrue(p.replay.start(5000, 5000, 1));
        p.changeSpeed(1.5f);
        p.position = 60000;
        p.replay.tick();
        assertFalse(p.replay.isActive());
        assertEquals(1.5f, p.speed, 0);
    }

    @Test public void cancellationDoesNotOverwriteAnUnreportedSpeedChange() {
        Player p = new Player();
        assertTrue(p.replay.start(5000, 5000, 1));
        p.speed = 1.75f;
        p.replay.cancel(true);
        assertEquals(1.75f, p.speed, 0);
        assertFalse(p.replay.isActive());
    }

    @Test public void newVideoDoesNotReceiveTheOldVideosSavedSpeed() {
        Player p = new Player();
        assertTrue(p.replay.start(5000, 5000, 1));
        p.id = "video-two";
        p.speed = 1.25f;
        p.replay.tick();
        assertFalse(p.replay.isActive());
        assertEquals(1.25f, p.speed, 0);
    }

    @Test public void resetBeforeReplacingThePlayerRestoresItsSpeed() {
        Player p = new Player();
        assertTrue(p.replay.start(5000, 5000, 1));
        p.replay.cancel(true);
        p.id = "video-two";
        p.replay.tick();
        assertEquals(2, p.speed, 0);
        assertFalse(p.replay.isActive());
    }

    @Test public void repeatedTapCancelsAndAFreshReplayCapturesAFreshReturnPosition() {
        Player p = new Player();
        assertTrue(p.replay.start(5000, 5000, 1));
        p.replay.cancel(true);
        p.position = 80000;
        assertTrue(p.replay.start(10000, 10000, 0.75f));
        assertEquals(70000, p.seekTarget);
        p.position = 80000;
        p.replay.tick();
        assertEquals(2, p.speed, 0);
        assertEquals(List.of(1.0f, 2.0f, 0.75f, 2.0f), p.changes);
    }

    @Test public void neverAcceleratesAnAlreadySlowerVideo() {
        Player p = new Player();
        p.speed = 0.5f;
        assertTrue(p.replay.start(5000, 5000, 1));
        assertEquals(0.5f, p.speed, 0);
        p.position = 60000;
        p.replay.tick();
        assertEquals(0.5f, p.speed, 0);
    }

    @Test public void invalidInputsAndUnavailablePositionHaveNoPlayerEffects() {
        for (float speed : new float[] {0, -1, Float.NaN, Float.POSITIVE_INFINITY}) {
            Player p = new Player();
            assertFalse(p.replay.start(5000, 5000, speed));
            assertEquals(-1, p.seekTarget);
        }
        Player p = new Player();
        assertFalse(p.replay.start(0, 5000, 1));
        p.position = -1;
        assertFalse(p.replay.start(5000, 5000, 1));
        p.position = 60000;
        p.id = "";
        assertFalse(p.replay.start(5000, 5000, 1));
        assertTrue(p.changes.isEmpty());
    }

    @Test public void checksMoreCloselyNearTheReturnPosition() {
        Player p = new Player();
        assertTrue(p.replay.start(5000, 5000, 1));
        assertEquals(100, p.replay.nextCheckDelayMillis());
        p.position = 59975;
        assertEquals(25, p.replay.nextCheckDelayMillis());
        p.position = 60001;
        assertEquals(1, p.replay.nextCheckDelayMillis());
    }

    @Test public void rewindAndSlowDurationAreIndependent() {
        Player p = new Player();
        p.speed = 2.5f;
        assertTrue(p.replay.start(4000, 3000, 1));
        assertEquals(56000, p.seekTarget);
        p.position = 58999;
        p.replay.tick();
        assertTrue(p.replay.isActive());
        assertEquals(1, p.speed, 0);
        assertEquals(1, p.replay.nextCheckDelayMillis());
        p.position = 59000;
        p.replay.tick();
        assertFalse(p.replay.isActive());
        assertEquals(2.5f, p.speed, 0);
    }

    @Test public void slowDurationStartsAtTheObservedSeekLandingPoint() {
        Player p = new Player();
        p.asynchronousSeek = true;
        assertTrue(p.replay.start(4000, 3000, 1));
        p.position = 60100;
        p.replay.tick();
        assertTrue(p.replay.isActive());
        p.position = 56200;
        p.replay.tick();
        p.position = 59199;
        p.replay.tick();
        assertTrue(p.replay.isActive());
        p.position = 59200;
        p.replay.tick();
        assertFalse(p.replay.isActive());
        assertEquals(2, p.speed, 0);
    }

    @Test public void slowDurationCanExtendPastTheOriginalPosition() {
        Player p = new Player();
        assertTrue(p.replay.start(4000, 6000, 1));
        p.position = 60000;
        p.replay.tick();
        assertTrue(p.replay.isActive());
        p.position = 62000;
        p.replay.tick();
        assertFalse(p.replay.isActive());
        assertEquals(2, p.speed, 0);
    }

    @Test public void slowDurationStartsAtZeroWhenRewindIsClamped() {
        Player p = new Player();
        p.position = 2000;
        assertTrue(p.replay.start(4000, 3000, 1));
        assertEquals(0, p.seekTarget);
        p.position = 2999;
        p.replay.tick();
        assertTrue(p.replay.isActive());
        p.position = 3000;
        p.replay.tick();
        assertEquals(2, p.speed, 0);
        assertFalse(p.replay.isActive());
    }

    @Test public void slowDurationUsesVideoTimeAtHalfSpeed() {
        Player p = new Player();
        assertTrue(p.replay.start(4000, 3000, 0.5f));
        p.elapsed = 6000;
        p.position = 58999;
        p.replay.tick();
        assertTrue(p.replay.isActive());
        assertEquals(0.5f, p.speed, 0);
        p.position = 59000;
        p.replay.tick();
        assertEquals(2, p.speed, 0);
    }

    @Test public void invalidSlowDurationHasNoPlayerEffects() {
        for (long duration : new long[] {0, -1}) {
            Player p = new Player();
            assertFalse(p.replay.start(4000, duration, 1));
            assertEquals(-1, p.seekTarget);
            assertTrue(p.changes.isEmpty());
        }
    }
}
