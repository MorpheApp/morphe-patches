/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3899
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import android.content.Context;
import android.content.pm.ResolveInfo;
import android.media.MediaRoute2Info;
import android.media.MediaRouter;
import android.os.Build;

import androidx.annotation.RequiresApi;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Fixes for casting through a GmsCore with Cast support.
 */
@SuppressWarnings("unused")
public final class CastFixesPatch {

    /**
     * Built at runtime, because the GmsCore patch replaces every
     * {@code "com.google.android.gms"} string constant with the GmsCore package.
     */
    private static final String PLAY_SERVICES_PACKAGE = String.join(".", "com", "google", "android", "gms");

    /**
     * MediaRouter2 route ids start with the provider component.
     */
    private static final String PLAY_SERVICES_ROUTE_ID_PREFIX = PLAY_SERVICES_PACKAGE + "/";

    /**
     * Injection point.
     * <p>
     * Removes the real Play Services' media route provider services. On a phone that has it
     * next to GmsCore, it offers the same cast devices, and picking one of its routes makes it
     * connect to the device as well as GmsCore.
     */
    public static List<ResolveInfo> filterRouteProviderServices(List<ResolveInfo> services) {
        try {
            List<ResolveInfo> filtered = new ArrayList<>(services.size());
            for (ResolveInfo info : services) {
                if (info.serviceInfo == null || !PLAY_SERVICES_PACKAGE.equals(info.serviceInfo.packageName)) {
                    filtered.add(info);
                }
            }
            return filtered;
        } catch (Exception ex) {
            Logger.printException(() -> "filterRouteProviderServices failure", ex);
            return services;
        }
    }

    /**
     * Injection point.
     * <p>
     * Removes the MediaRouter2 routes of the real Play Services.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    public static List<MediaRoute2Info> filterRoutes(List<MediaRoute2Info> routes) {
        try {
            List<MediaRoute2Info> filtered = new ArrayList<>(routes.size());
            for (MediaRoute2Info route : routes) {
                if (route == null || !route.getId().startsWith(PLAY_SERVICES_ROUTE_ID_PREFIX)) {
                    filtered.add(route);
                }
            }
            return filtered;
        } catch (Exception ex) {
            Logger.printException(() -> "filterRoutes failure", ex);
            return routes;
        }
    }

    /**
     * Injection point.
     * <p>
     * The queue restoration when the app returns to the foreground loads and plays the last song
     * on the phone, next to the cast device. Skipped while casting.
     */
    public static boolean skipQueueRestoration() {
        try {
            if (isCasting()) {
                Logger.printDebug(() -> "Skipping queue restoration while casting");
                return true;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "skipQueueRestoration failure", ex);
        }
        return false;
    }

    /**
     * androidx MediaRouter publishes its selected cast route to the framework router as a
     * remote user route. Screen mirroring and other system routes are not user routes.
     */
    private static boolean isCasting() {
        MediaRouter router = (MediaRouter) Utils.getContext().getSystemService(Context.MEDIA_ROUTER_SERVICE);
        MediaRouter.RouteInfo route = router.getSelectedRoute(MediaRouter.ROUTE_TYPE_USER);
        return route != null && route.getPlaybackType() == MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE;
    }
}
