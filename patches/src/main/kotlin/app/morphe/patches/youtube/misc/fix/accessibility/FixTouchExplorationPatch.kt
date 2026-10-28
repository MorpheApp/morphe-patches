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

        val classDef = TouchDelegateConstructorFingerprint.classDef

        // Skip the inherited forwarding back to ComponentHost, as YouTube 21.41's workaround does.
        // Ordinary touch events use the class's own implementation.
        val hoverMethod = classDef.methods.firstOrNull {
            it.name == "onTouchExplorationHoverEvent" &&
                    it.parameterTypes == listOf("Landroid/view/MotionEvent;")
        } ?: ImmutableMethod(
            classDef.type,
            "onTouchExplorationHoverEvent",
            listOf(ImmutableMethodParameter("Landroid/view/MotionEvent;", null, null)),
            "Z",
            AccessFlags.PUBLIC.value,
            null,
            null,
            MutableMethodImplementation(2),
        ).toMutable().also { classDef.methods.add(it) }

        hoverMethod.returnEarly(false)
    }
}
