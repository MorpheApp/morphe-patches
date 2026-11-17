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
import android.content.ContextWrapper;

import org.junit.Test;

import app.morphe.extension.music.patches.AndroidAutoAnyBrowserSupport.Request;

import static org.junit.Assert.assertEquals;

/** Exercises the production caller policy: any backend is admitted, but only with its real UID. */
public class AndroidAutoAnyBrowserSupportTest {

    @Test
    public void ownsPackage_admitsAnyBackendThatOwnsTheCallbackUid() {
        // No whitelist: a package of any backend, known or not, is trusted once the UID owns it.
        assertEquals(1, AndroidAutoAnyBrowserSupport.ownsPackage(
                new String[]{"app.someone.flywheel"}, "app.someone.flywheel"));

        // All packages owned by the UID count, such as a host and its sibling package.
        assertEquals(1, AndroidAutoAnyBrowserSupport.ownsPackage(
                new String[]{"app.someone.host", "app.someone.host.dev"}, "app.someone.host.dev"));
    }

    @Test
    public void ownsPackage_rejectsACallerThatDoesNotOwnTheCallbackUid() {
        // A spoofed claim of a package owned by another UID.
        assertEquals(0, AndroidAutoAnyBrowserSupport.ownsPackage(
                new String[]{"unrelated.client"}, "app.someone.flywheel"));
        // No prefix matching.
        assertEquals(0, AndroidAutoAnyBrowserSupport.ownsPackage(
                new String[]{"app.someone.flywheel"}, "app.someone.flywheel.spoof"));
        // The UID owns no package at all.
        assertEquals(0, AndroidAutoAnyBrowserSupport.ownsPackage(null, "app.someone.flywheel"));
    }

    @Test
    public void decision_keepsTheOriginalPolicyWhereIdentityCannotBeVerified() {
        Context throwing = new ContextWrapper(null) {
            @Override
            public android.content.pm.PackageManager getPackageManager() {
                throw new SecurityException("fixture");
            }
        };

        // No package to verify.
        assertEquals(-1, AndroidAutoAnyBrowserSupport.decision(throwing, null, 12345));
        // No context to resolve the UID ownership with.
        assertEquals(-1, AndroidAutoAnyBrowserSupport.decision(null, "app.someone.flywheel", 12345));
        // An invalid callback UID.
        assertEquals(-1, AndroidAutoAnyBrowserSupport.decision(throwing, "app.someone.flywheel", -1));
        // The ownership lookup itself fails: YTM's own policy decides.
        assertEquals(-1, AndroidAutoAnyBrowserSupport.decision(throwing, "app.someone.flywheel", 12345));
    }

    @Test
    public void route_returnsRootHomeAndLibraryOfTheAnyBackendPages() {
        Request request = () -> "app.someone.flywheel";
        assertEquals(1, AndroidAutoAnyBrowserSupport.route(request, "app.someone.flywheel"));
        assertEquals(2, AndroidAutoAnyBrowserSupport.route(request, "morphe:aa:any:home"));
        assertEquals(3, AndroidAutoAnyBrowserSupport.route(request, "morphe:aa:any:library"));
    }

    @Test
    public void route_delegatesEverythingItDoesNotHandle() {
        Request request = () -> "app.someone.flywheel";
        assertEquals(0, AndroidAutoAnyBrowserSupport.route(request, "morphe:aa:speed-dial"));
        assertEquals(0, AndroidAutoAnyBrowserSupport.route(request, null));
        // A root request of another backend, not this connection's caller.
        assertEquals(0, AndroidAutoAnyBrowserSupport.route(request, "app.someone.else"));
    }

    @Test
    public void route_delegatesWithoutACallableIdentity() {
        assertEquals(0, AndroidAutoAnyBrowserSupport.route(null, "morphe:aa:any:home"));
        assertEquals(0, AndroidAutoAnyBrowserSupport.route("not a request", "morphe:aa:any:home"));
        assertEquals(0, AndroidAutoAnyBrowserSupport.route((Request) () -> null, "morphe:aa:any:home"));
    }
}
