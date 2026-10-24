/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import android.annotation.TargetApi;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.MediaRoute2Info;
import android.media.MediaRouter2;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;

/**
 * Keeps the real Google Play Services out of the app's cast route list. On a phone that has
 * it installed next to GmsCore, its MediaRouter2 provider offers the same speakers; picking one
 * of those makes Play Services connect to the speaker as well as GmsCore, two senders for one
 * session ("too many devices are playing", competing controllers). With these routes gone, the
 * app only ever sees GmsCore's routes and GmsCore is the only one that connects.
 */
@SuppressWarnings("unused")
public final class CastRoutesPatch {

    /**
     * Built at run time: the GmsCore patch rewrites every exact {@code "com.google.android.gms"}
     * string constant in the app to the GmsCore package.
     */
    private static final String PLAY_SERVICES = String.join(".", "com", "google", "android", "gms");
    private static final String PLAY_SERVICES_ROUTE_PREFIX = PLAY_SERVICES + "/";

    private CastRoutesPatch() {
    }

    /**
     * Injection point: the media route provider scan. Drops Play Services' route provider
     * services, legacy and MediaRouter2 alike.
     */
    public static List<ResolveInfo> queryProviderServices(PackageManager packageManager,
                                                          Intent intent, int flags) {
        final List<ResolveInfo> services = packageManager.queryIntentServices(intent, flags);
        try {
            final List<ResolveInfo> kept = new ArrayList<>(services);
            kept.removeIf(info -> info.serviceInfo != null
                    && PLAY_SERVICES.equals(info.serviceInfo.packageName));
            return kept;
        } catch (Exception ex) {
            Logger.printException(() -> "queryProviderServices failure", ex);
            return services;
        }
    }

    /**
     * Injection point: the MediaRouter2 route refresh. Drops routes that Play Services'
     * provider publishes; their ids carry the provider component as a prefix.
     */
    @TargetApi(Build.VERSION_CODES.R)
    public static List<MediaRoute2Info> getRoutes(MediaRouter2 router) {
        final List<MediaRoute2Info> routes = router.getRoutes();
        try {
            final List<MediaRoute2Info> kept = new ArrayList<>(routes.size());
            for (MediaRoute2Info route : routes) {
                if (route == null || !route.getId().startsWith(PLAY_SERVICES_ROUTE_PREFIX)) {
                    kept.add(route);
                }
            }
            return kept;
        } catch (Exception ex) {
            Logger.printException(() -> "getRoutes failure", ex);
            return routes;
        }
    }
}
