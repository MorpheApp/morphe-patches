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
 * An overlay replaces a color in every configuration, so it cannot be qualified the way a resource
 * variant is. Each theme is given an overlay of its own instead, and a context is only ever loaded
 * with the overlay of the theme it shows. Otherwise, the background of one theme replaces the color
 * the other theme uses for its text and icons.
 * <p>
 * Kept in a class of its own so the API 34 classes are never loaded on an older device.
 */
@RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
final class ThemeColorOverlay {

    private static final String OVERLAY_NAME_DARK = "morphe_theme_background_dark";
    private static final String OVERLAY_NAME_LIGHT = "morphe_theme_background_light";

    /**
     * An overlay is rejected unless the target declares the resources it may change, so the patch
     * adds this to {@code res/values/overlayable.xml} and both names must stay identical.
     */
    private static final String OVERLAYABLE_NAME = "MorpheThemeColor";

    /**
     * Gives every color resource of {@code colors} the color it is mapped to, in the overlay of one
     * theme. A resource that is not included keeps the value it has in the app.
     *
     * @param dark If the overlay of the dark theme is registered.
     */
    static void register(Context context, boolean dark, Map<String, Integer> colors) {
        String packageName = context.getPackageName();
        FabricatedOverlay overlay = new FabricatedOverlay(overlayName(dark), packageName);
        overlay.setTargetOverlayable(OVERLAYABLE_NAME);

        for (Map.Entry<String, Integer> entry : colors.entrySet()) {
            overlay.setResourceValue(packageName + ":color/" + entry.getKey(),
                    TypedValue.TYPE_INT_COLOR_ARGB8, entry.getValue(), null);
        }

        OverlayManagerTransaction transaction = OverlayManagerTransaction.newInstance();
        transaction.registerFabricatedOverlay(overlay);
        commit(context, transaction);
        setResourcesLoader(dark, null);

        Logger.printDebug(() -> "Registered " + overlayName(dark)
                + " overlay of " + colors.size() + " colors");
    }

    /**
     * Removes the overlay of a theme, and does nothing if the app has none.
     */
    static void unregisterIfRegistered(Context context, boolean dark) {
        if (findOverlay(context, dark) != null) {
            unregister(context, dark);
        }
    }

    private static void unregister(Context context, boolean dark) {
        // An identifier cannot be created on its own, but an overlay of the same name has the
        // identifier of the overlay that is registered.
        FabricatedOverlay overlay = new FabricatedOverlay(
                overlayName(dark), context.getPackageName());

        OverlayManagerTransaction transaction = OverlayManagerTransaction.newInstance();
        transaction.unregisterFabricatedOverlay(overlay.getIdentifier());
        commit(context, transaction);
        setResourcesLoader(dark, null);

        Logger.printDebug(() -> "Unregistered " + overlayName(dark) + " overlay");
    }

    /**
     * The overlay of a theme is a file of the app, and loading it once is enough for the
     * whole process.
     */
    @Nullable
    private static ResourcesLoader darkResourcesLoader;
    @Nullable
    private static ResourcesLoader lightResourcesLoader;

    @Nullable
    private static ResourcesLoader resourcesLoader(boolean dark) {
        return dark ? darkResourcesLoader : lightResourcesLoader;
    }

    private static void setResourcesLoader(boolean dark, @Nullable ResourcesLoader loader) {
        if (dark) {
            darkResourcesLoader = loader;
        } else {
            lightResourcesLoader = loader;
        }
    }

    /**
     * Loads the overlay of a theme into the resources of {@code context}. Without this the overlay
     * is registered but nothing of the app uses it.
     *
     * @param dark If the app shows its dark theme. The overlay of the other theme is never loaded,
     *             its colors are the text and the icons of this one.
     */
    static void applyTo(Context context, boolean dark) {
        try {
            ResourcesLoader loader = resourcesLoader(dark);
            if (loader == null) {
                OverlayInfo overlayInfo = findOverlay(context, dark);
                if (overlayInfo == null) {
                    Logger.printException(() -> "Overlay " + overlayName(dark)
                            + " of the app is not registered");
                    return;
                }

                loader = new ResourcesLoader();
                loader.addProvider(ResourcesProvider.loadOverlay(overlayInfo));
                setResourcesLoader(dark, loader);
            }

            // Adding the same loader again is ignored, and every context of the app needs it
            // because the loaders of a context are not inherited.
            context.getResources().addLoaders(loader);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not apply the overlay of the app", ex);
        }
    }

    /**
     * Removes the overlay of both themes from the resources of {@code context}, which is needed to
     * read a color the app declares. An overlay replaces the color of every configuration, so a
     * context that uses one cannot resolve the color of another background.
     */
    static void removeFrom(Context context) {
        try {
            for (ResourcesLoader loader : new ResourcesLoader[]{
                    darkResourcesLoader, lightResourcesLoader}) {
                if (loader != null) {
                    context.getResources().removeLoaders(loader);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not remove the overlay of the app", ex);
        }
    }

    private static String overlayName(boolean dark) {
        return dark ? OVERLAY_NAME_DARK : OVERLAY_NAME_LIGHT;
    }

    @Nullable
    private static OverlayInfo findOverlay(Context context, boolean dark) {
        OverlayManager overlayManager = context.getSystemService(OverlayManager.class);
        if (overlayManager == null) {
            return null;
        }

        final String overlayName = overlayName(dark);
        for (OverlayInfo overlayInfo : overlayManager.getOverlayInfosForTarget(
                context.getPackageName())) {
            if (overlayName.equals(overlayInfo.getOverlayName())) {
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
