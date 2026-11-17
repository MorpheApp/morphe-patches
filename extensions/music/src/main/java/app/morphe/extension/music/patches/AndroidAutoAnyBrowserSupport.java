/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://codeberg.org/JohnInNL/nl-morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches;

import android.content.Context;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;

/**
 * Music browser access for any Android Auto backend. Caller authentication and browser eligibility
 * are separate decisions, and no package is trusted by name: a caller is only admitted when it owns
 * its claimed package under the callback UID, which every backend can prove and a spoofer cannot.
 */
public final class AndroidAutoAnyBrowserSupport {
    private AndroidAutoAnyBrowserSupport() {
    }

    /** Implemented by the patch using the request's captured MediaBrowser RemoteUserInfo. */
    public interface Request {
        @Nullable
        String patch_getCallerPackage();
    }

    /**
     * -1 leaves the decision to YTM's original policy, which cannot be improved on here:
     * no caller to verify, or the ownership lookup is unavailable. 0 rejects a caller that does not
     * own its claimed package under the callback UID. 1 admits a verified caller of any backend.
     */
    public static int decision(@Nullable Context context, @Nullable String packageName, int callbackUid) {
        if (packageName == null) return -1;
        if (context == null || callbackUid < 0) return -1;
        try {
            String[] owned = context.getPackageManager().getPackagesForUid(callbackUid);
            if (owned != null) {
                for (String candidate : owned) {
                    if (packageName.equals(candidate)) return 1;
                }
            }
        } catch (RuntimeException ex) {
            Logger.printException(() -> "Could not verify the music browser caller", ex);
            return -1;
        }
        return 0;
    }

    /** 0 delegates unchanged; 1 root, 2 personal Home, 3 personal Library. */
    public static int route(@Nullable Object request, @Nullable String mediaId) {
        if (!(request instanceof Request) || mediaId == null) return 0;
        String caller = ((Request) request).patch_getCallerPackage();
        if (caller == null) return 0;
        if (caller.equals(mediaId)) return 1;
        if (mediaId.equals("morphe:aa:any:home")) return 2;
        if (mediaId.equals("morphe:aa:any:library")) return 3;
        return 0;
    }
}
