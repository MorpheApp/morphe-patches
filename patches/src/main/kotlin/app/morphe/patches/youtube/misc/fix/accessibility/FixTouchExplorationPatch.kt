/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4129
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.misc.fix.accessibility

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.returnEarly
import app.morphe.patches.youtube.misc.playservice.is_20_51_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_30_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_42_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

/**
 * Touch exploration events can loop between Litho's TouchDelegate and ComponentHost, causing StackOverflowError.
 * Fixes https://github.com/MorpheApp/morphe-patches/issues/4045.
 */
internal val fixTouchExplorationPatch = bytecodePatch(
    description = "Fixes stack overflow crashes caused by recursive TalkBack event forwarding in Litho."
) {
    dependsOn(versionCheckPatch)

    execute {
        if (!is_20_51_or_greater || is_21_42_or_greater) return@execute

        // The inherited TouchDelegate implementation forwards the event back to the ComponentHost.
        // Return false instead, the same as the flag gated workaround YouTube added in 21.30.
        // Ordinary touch events use the class's own implementation.
        if (is_21_30_or_greater) {
            TouchExplorationHoverEventFingerprint.method.returnEarly(false)
        } else {
            val classDef = TouchDelegateConstructorFingerprint.classDef

            classDef.methods.add(
                ImmutableMethod(
                    classDef.type,
                    "onTouchExplorationHoverEvent",
                    listOf(ImmutableMethodParameter("Landroid/view/MotionEvent;", null, null)),
                    "Z",
                    AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                    null,
                    null,
                    MutableMethodImplementation(2),
                ).toMutable().apply {
                    returnEarly(false)
                }
            )
        }
    }
}
