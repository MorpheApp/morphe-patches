/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import android.content.Context;
import android.media.MediaRouter;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Whether the app is casting. androidx MediaRouter mirrors its selected route into the framework
 * router as a user route, so a selected remote route there means a cast device is playing.
 */
@SuppressWarnings({"unused", "deprecation"})
public final class CastStatePatch {

    private static volatile boolean lastCasting;

    private CastStatePatch() {
    }

    public static boolean isCasting() {
        boolean casting = false;
        try {
            final Context context = Utils.getContext();
            final MediaRouter router = context == null
                    ? null : (MediaRouter) context.getSystemService(Context.MEDIA_ROUTER_SERVICE);
            if (router != null) {
                final MediaRouter.RouteInfo route = router.getSelectedRoute(
                        MediaRouter.ROUTE_TYPE_LIVE_AUDIO | MediaRouter.ROUTE_TYPE_USER);
                // A user route, as androidx publishes for a cast device; screen mirroring and
                // other system remote routes are not casting from this app.
                casting = route != null && route != router.getDefaultRoute()
                        && (route.getSupportedTypes() & MediaRouter.ROUTE_TYPE_USER) != 0
                        && route.getPlaybackType() == MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "isCasting failure", ex);
        }
        if (casting != lastCasting) {
            lastCasting = casting;
            final boolean state = casting;
            Logger.printDebug(() -> "Casting " + (state ? "started" : "stopped"));
        }
        return casting;
    }

    /**
     * Injection point: the queue restoration that runs when the app comes back to the
     * foreground. While casting it would load the last local song into the phone's player and
     * play it next to the cast device.
     */
    public static boolean skipQueueRestoration() {
        final boolean casting = isCasting();
        if (casting) {
            Logger.printDebug(() -> "Skipping queue restoration while casting");
        }
        return casting;
    }
}
