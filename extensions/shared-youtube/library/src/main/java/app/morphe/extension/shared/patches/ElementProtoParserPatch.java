/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5096
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches;

import androidx.annotation.Nullable;

/**
 * The element converter parses each element to check if it is rendered with RenderNext,
 * and the converted element then parses the element again. The bytes hooked for the check
 * are used again by the converted element, so the element hooks run once for each element.
 */
@SuppressWarnings("unused")
public final class ElementProtoParserPatch {

    private static final class HookedElement {
        final Thread thread;
        final byte[] bytes;
        final byte[] hookedBytes;

        HookedElement(Thread thread, byte[] bytes, byte[] hookedBytes) {
            this.thread = thread;
            this.bytes = bytes;
            this.hookedBytes = hookedBytes;
        }
    }

    /**
     * Thread that is about to parse an element for the RenderNext check.
     */
    @Nullable
    private static volatile Thread renderNextCheckThread;

    /**
     * Element hooked for the RenderNext check. Used only by the next element parsed on the same thread,
     * which is the same element parsed by the converted element right after the check.
     */
    @Nullable
    private static volatile HookedElement hookedElement;

    /**
     * Injection point. Called before the element is parsed for the RenderNext check.
     */
    public static void onRenderNextCheck() {
        hookedElement = null;
        renderNextCheckThread = Thread.currentThread();
    }

    /**
     * Injection point. Called before the element hooks.
     *
     * @return The hooked bytes, if the same bytes were hooked for the RenderNext check right before
     *         on this thread, or null to hook the bytes.
     */
    @Nullable
    public static byte[] getHookedElement(byte[] bytes) {
        HookedElement element = hookedElement;
        if (element == null || element.thread != Thread.currentThread()) {
            return null;
        }
        hookedElement = null;
        return element.bytes == bytes ? element.hookedBytes : null;
    }

    /**
     * Injection point. Called after the element hooks.
     */
    public static void setHookedElement(byte[] bytes, byte[] hookedBytes) {
        Thread thread = Thread.currentThread();
        if (renderNextCheckThread == thread) {
            renderNextCheckThread = null;
            hookedElement = new HookedElement(thread, bytes, hookedBytes);
        }
    }
}
