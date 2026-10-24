/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.queue;

import static org.junit.Assert.assertEquals;
import static app.morphe.extension.youtube.patches.queue.QueueShortsSession.Action.NONE;
import static app.morphe.extension.youtube.patches.queue.QueueShortsSession.Action.RELEASE;
import static app.morphe.extension.youtube.patches.queue.QueueShortsSession.Action.RESUME;
import static app.morphe.extension.youtube.patches.queue.QueueShortsSession.Action.SUSPEND;
import static app.morphe.extension.youtube.patches.queue.QueueShortsSession.Action.WAIT;

import org.junit.Test;

public class QueueShortsSessionTest {
    private final QueueShortsSession session = new QueueShortsSession();
    private static final String A = "abcdefghijk";
    private static final String B = "lmnopqrstuv";

    private QueueShortsSession.Action update(boolean open, boolean hidden, boolean visible,
                                            String active, String regular, boolean playlist, boolean service) {
        return session.update(open, hidden, visible, active, regular, playlist, service);
    }

    @Test public void eitherShortsAttachmentOrderRetainsQueue() {
        for (boolean hiddenFirst : new boolean[]{true, false}) {
            session.reset();
            assertEquals(NONE, update(false, false, true, A, A, false, false));
            assertEquals(SUSPEND, update(!hiddenFirst, hiddenFirst, false, A, A, false, false));
            assertEquals(WAIT, update(true, true, false, B, A, false, false));
            assertEquals(WAIT, update(true, false, true, A, A, false, false));
            assertEquals(WAIT, update(false, false, false, B, A, false, false));
            assertEquals(WAIT, update(false, false, true, B, A, false, false));
            assertEquals(RESUME, update(false, false, true, A, A, false, false));
            assertEquals(NONE, update(false, false, true, A, A, false, false));
        }
    }

    @Test public void restoredIdWaitsForShortsDetachAndVisiblePlayer() {
        assertEquals(SUSPEND, update(true, false, true, A, A, false, false));
        assertEquals(WAIT, update(true, false, true, A, A, false, false));
        assertEquals(WAIT, update(false, true, false, A, A, false, false));
        assertEquals(WAIT, update(false, false, false, A, A, false, false));
        assertEquals(RESUME, update(false, false, true, A, A, false, false));
    }

    @Test public void confirmedServiceResumesWithoutWatchScreen() {
        assertEquals(SUSPEND, update(true, false, true, A, A, false, false));
        assertEquals(WAIT, update(false, false, false, A, "", false, false));
        assertEquals(RESUME, update(false, false, false, A, "", false, true));
        assertEquals(NONE, update(false, false, false, A, "", false, true));
    }

    @Test public void serviceCannotOverrideShortsOrAccountPlaylist() {
        assertEquals(SUSPEND, update(true, false, true, A, A, false, true));
        assertEquals(WAIT, update(true, false, true, A, A, false, true));
        assertEquals(WAIT, update(false, true, false, A, A, false, true));
        assertEquals(WAIT, update(false, false, false, A, A, true, true));
        assertEquals(RELEASE, update(false, false, true, A, A, true, false));
    }

    @Test public void controllerAgreementResumesRegularVideoOrReleasesAccountPlaylist() {
        for (boolean playlist : new boolean[]{false, true}) {
            session.reset();
            assertEquals(SUSPEND, update(true, false, true, A, A, false, false));
            String replacement = playlist ? A : B;
            assertEquals(WAIT, update(true, false, true, replacement, replacement, playlist, false));
            assertEquals(WAIT, update(false, false, true, replacement, "", playlist, false));
            assertEquals(playlist ? RELEASE : RESUME,
                    update(false, false, true, replacement, replacement, playlist, false));
        }
    }

    @Test public void staleCallbacksWaitAndClosingOrResetEndsInterruption() {
        assertEquals(SUSPEND, update(false, true, false, A, A, false, false));
        assertEquals(WAIT, update(false, false, true, "", A, false, false));
        assertEquals(WAIT, update(false, false, true, A, "", false, false));
        assertEquals(WAIT, update(false, false, true, A, B, false, false));
        assertEquals(RELEASE, update(false, false, false, "", "", false, false));
        assertEquals(SUSPEND, update(true, false, true, A, A, false, false));
        session.reset();
        assertEquals(NONE, update(false, false, false, B, A, false, false));
        assertEquals(NONE, update(false, false, true, A, A, false, false));
    }
}
