/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2524
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.theme;

import android.content.Context;
import android.content.om.FabricatedOverlay;
import android.content.om.OverlayInfo;
import android.content.om.OverlayManager;
import android.content.om.OverlayManagerTransaction;
import android.content.res.loader.ResourcesLoader;
import android.content.res.loader.ResourcesProvider;
import android.os.Build;
import android.util.TypedValue;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.util.Map;

import app.morphe.extension.shared.Logger;

/**
 * Overrides the color resources of the app with an arbitrary color, using an overlay the app
 * registers for itself. Unlike the resource variants this needs no value that was compiled in,
 * but it exists only on Android 14 and later.
 * <p>
 * A transaction of {@link OverlayManagerTransaction#newInstance()} is always self targeting and
 * needs no permission, but the target must declare the resources that may be changed in
 * {@code res/values/overlayable.xml}.
 * <p>
 * Registering only creates the overlay. The system does not apply an overlay an app registers for
 * itself, and the app loads it into the resources of every context it creates.
 * <p>
 * Kept in a class of its own so the API 34 classes are never loaded on an older device.
 */
@RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
final class ThemeColorOverlay {

    private static final String OVERLAY_NAME = "morphe_theme_background";

    /**
     * An overlay is rejected unless the target declares the resources it may change, so the patch
     * adds this to {@code res/values/overlayable.xml} and both names must stay identical.
     */
    private static final String OVERLAYABLE_NAME = "MorpheThemeColor";

    /**
     * Gives every color resource of {@code colors} the color it is mapped to.
     * A resource that is not included keeps the value it has in the app.
     */
    static void register(Context context, Map<String, Integer> colors) {
        String packageName = context.getPackageName();
        FabricatedOverlay overlay = new FabricatedOverlay(OVERLAY_NAME, packageName);
        overlay.setTargetOverlayable(OVERLAYABLE_NAME);

        for (Map.Entry<String, Integer> entry : colors.entrySet()) {
            overlay.setResourceValue(packageName + ":color/" + entry.getKey(),
                    TypedValue.TYPE_INT_COLOR_ARGB8, entry.getValue(), null);
        }

        OverlayManagerTransaction transaction = OverlayManagerTransaction.newInstance();
        transaction.registerFabricatedOverlay(overlay);
        commit(context, transaction);
        resourcesLoader = null;

        Logger.printDebug(() -> "Registered overlay of " + colors.size() + " colors");
    }

    /**
     * Removes the overlay of the app, and does nothing if the app has none.
     */
    static void unregisterIfRegistered(Context context) {
        if (findOverlay(context) != null) {
            unregister(context);
        }
    }

    private static void unregister(Context context) {
        // An identifier cannot be created on its own, but an overlay of the same name has the
        // identifier of the overlay that is registered.
        FabricatedOverlay overlay = new FabricatedOverlay(OVERLAY_NAME, context.getPackageName());

        OverlayManagerTransaction transaction = OverlayManagerTransaction.newInstance();
        transaction.unregisterFabricatedOverlay(overlay.getIdentifier());
        commit(context, transaction);
        resourcesLoader = null;

        Logger.printDebug(() -> "Unregistered overlay");
    }

    /**
     * The overlay is a file of the app, and loading it once is enough for the whole process.
     */
    @Nullable
    private static ResourcesLoader resourcesLoader;

    /**
     * Loads the overlay into the resources of {@code context}. Without this the overlay is
     * registered but nothing of the app uses it.
     */
    static void applyTo(Context context) {
        try {
            ResourcesLoader loader = resourcesLoader;
            if (loader == null) {
                OverlayInfo overlayInfo = findOverlay(context);
                if (overlayInfo == null) {
                    Logger.printException(() -> "Overlay of the app is not registered");
                    return;
                }

                loader = new ResourcesLoader();
                loader.addProvider(ResourcesProvider.loadOverlay(overlayInfo));
                resourcesLoader = loader;
            }

            // Adding the same loader again is ignored, and every context of the app needs it
            // because the loaders of a context are not inherited.
            context.getResources().addLoaders(loader);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not apply the overlay of the app", ex);
        }
    }

    /**
     * Removes the overlay from the resources of {@code context}, which is needed to read a color
     * the app declares. An overlay replaces the color of every configuration, so a context that
     * uses it cannot resolve the color of another background.
     */
    static void removeFrom(Context context) {
        try {
            ResourcesLoader loader = resourcesLoader;
            if (loader != null) {
                context.getResources().removeLoaders(loader);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not remove the overlay of the app", ex);
        }
    }

    @Nullable
    private static OverlayInfo findOverlay(Context context) {
        OverlayManager overlayManager = context.getSystemService(OverlayManager.class);
        if (overlayManager == null) {
            return null;
        }

        for (OverlayInfo overlayInfo : overlayManager.getOverlayInfosForTarget(
                context.getPackageName())) {
            if (OVERLAY_NAME.equals(overlayInfo.getOverlayName())) {
                return overlayInfo;
            }
        }

        return null;
    }

    private static void commit(Context context, OverlayManagerTransaction transaction) {
        OverlayManager overlayManager = context.getSystemService(OverlayManager.class);
        if (overlayManager == null) {
            throw new IllegalStateException("OverlayManager is not available");
        }

        overlayManager.commit(transaction);
    }

    private ThemeColorOverlay() {
    }
}
