/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4931
 *
 * Adapted from
 * https://codeberg.org/JohnInNL/nl-morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches;

import androidx.annotation.Nullable;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.shared.Logger;

/**
 * Lets media browser apps that YTM does not allowlist, such as third-party Android Auto apps,
 * connect and browse. {@link AndroidAutoPatch} serves their Home and Library.
 */
@SuppressWarnings("unused")
public final class AndroidAutoThirdPartyAppsPatch {

    /** Apps YTM rejected and this patch allowed. YTM uses the package name as their root media ID. */
    private static final Set<String> allowedPackages = ConcurrentHashMap.newKeySet();

    private AndroidAutoThirdPartyAppsPatch() {
    }

    /**
     * Injection point. Called when YTM's allowlist rejects an app in onGetRoot.
     * The media browser service has already checked that the package belongs to the calling app.
     */
    public static boolean allowMediaBrowser(@Nullable String packageName) {
        if (packageName == null) return false;
        if (allowedPackages.add(packageName)) {
            Logger.printDebug(() -> "Allowing media browser: " + packageName);
        }
        return true;
    }

    /**
     * Injection point. Called when YTM's allowlist does not let a client browse.
     */
    public static boolean isAllowedMediaBrowser(@Nullable String packageName) {
        return packageName != null && allowedPackages.contains(packageName);
    }
}
