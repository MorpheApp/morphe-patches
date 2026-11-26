/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/issues/4863
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.playback;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaybackSessionStartupTest {
    @Test
    public void launcherReopensAfterTaskRemovalInTheSameProcess() throws Exception {
        var saved = PlaybackSessionPatch.class.getDeclaredField("saved");
        var pending = PlaybackSessionPatch.class.getDeclaredField("checkingStartup");
        var callbacks = PlaybackSessionPatch.class.getDeclaredField("LIFECYCLE");
        var initialized = PlaybackSessionPatch.class.getDeclaredField("initialized");
        saved.setAccessible(true);
        pending.setAccessible(true);
        callbacks.setAccessible(true);
        initialized.setAccessible(true);
        var lifecycle = (Application.ActivityLifecycleCallbacks) callbacks.get(null);
        var previousSnapshot = saved.get(null);
        boolean wasInitialized = initialized.getBoolean(null);
        var firstLaunch = activity(Intent.ACTION_MAIN);
        var secondLaunch = activity(Intent.ACTION_MAIN);
        var recreated = activity(Intent.ACTION_MAIN);
        var deepLink = activity(Intent.ACTION_VIEW);
        try {
            initialized.setBoolean(null, true);
            saved.set(null, new PlaybackSessionSnapshot("account", "abcdefghijk", 10_000,
                    PlaybackSessionSnapshot.Kind.VIDEO, "", false, null));
            PlaybackSessionPatch.initializeActivity(firstLaunch, null);
            assertTrue(pending.getBoolean(null));
            lifecycle.onActivityDestroyed(firstLaunch);
            assertFalse(pending.getBoolean(null));

            PlaybackSessionPatch.initializeActivity(secondLaunch, null);
            assertTrue(pending.getBoolean(null));
            lifecycle.onActivityDestroyed(firstLaunch);
            assertTrue(pending.getBoolean(null));

            PlaybackSessionPatch.initializeActivity(recreated, new Bundle());
            assertFalse(pending.getBoolean(null));
            PlaybackSessionPatch.initializeActivity(deepLink, null);
            assertFalse(pending.getBoolean(null));
        } finally {
            lifecycle.onActivityDestroyed(firstLaunch);
            lifecycle.onActivityDestroyed(secondLaunch);
            lifecycle.onActivityDestroyed(recreated);
            lifecycle.onActivityDestroyed(deepLink);
            saved.set(null, previousSnapshot);
            initialized.setBoolean(null, wasInitialized);
        }
    }

    private static Activity activity(String action) {
        return new Activity() {
            @Override public Intent getIntent() {
                return new Intent() {
                    @Override public String getAction() {
                        return action;
                    }
                };
            }
        };
    }
}
