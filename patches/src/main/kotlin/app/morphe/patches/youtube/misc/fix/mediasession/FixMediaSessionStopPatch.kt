/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.misc.fix.mediasession

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.patch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_21_42_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.indexOfFirstInstructionReversed
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/FixMediaSessionStopPatch;"

/**
 * Fixes https://github.com/MorpheApp/morphe-patches/issues/4762.
 *
 * All players share one media session. A player that did not start the session can stop it,
 * for example the player of the comment replies panel when the panel is closed.
 * The media notification goes away, and in the background the audio stops
 * while the video keeps playing.
 *
 * 21.42+ has an A/B flag that ignores a media session stop from a player that did not start
 * the session. Older versions get the same check: the player that starts the session
 * is its owner, and a session stop from another player is ignored.
 */
internal val fixMediaSessionStopPatch = patch {
    dependsOn(
        sharedExtensionPatch,
        versionCheckPatch,
    )

    execute {
        if (is_21_42_or_greater) {
            MediaSessionOwnerFeatureFlagFingerprint.method.insertLiteralOverride(
                MEDIA_SESSION_OWNER_FEATURE_FLAG,
                true
            )
            return@execute
        }

        val sessionClass = MediaSessionStartFingerprint.originalClassDef.type
        val startMethodName = MediaSessionStartFingerprint.originalMethod.name
        val stopMethodName = MediaSessionStopFingerprint.originalMethod.name
        val playerClass = playerMediaSessionStartFingerprint(sessionClass, startMethodName)
            .originalClassDef.type

        // The player reads the session from its field before it starts or stops the session.
        val playerSessionField = fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = playerClass,
            type = sessionClass
        )

        /**
         * Calls the extension method with the player that calls the session method,
         * right before the player reads the session from its field (the register of the player
         * can be reused for the session). Calls of the session method that are not made
         * by a player are not changed.
         */
        fun hookPlayerSessionCalls(methodName: String, parameters: List<String>, extensionMethod: String) {
            val sessionCall = methodCall(
                definingClass = sessionClass,
                name = methodName,
                parameters = parameters,
                returnType = "V"
            )

            mediaSessionCallFingerprint(sessionClass, methodName, parameters).matchAll().forEach { match ->
                match.method.apply {
                    // Several calls can use the session read once.
                    findInstructionIndicesReversedOrThrow(sessionCall).map { callIndex ->
                        indexOfFirstInstructionReversed(callIndex, playerSessionField)
                    }.filter { it >= 0 }.distinct().sortedDescending().forEach { fieldIndex ->
                        val playerRegister = getInstruction<TwoRegisterInstruction>(fieldIndex).registerB

                        // The field read can be a jump target.
                        addInstructionsAtControlFlowLabel(
                            fieldIndex,
                            "invoke-static/range { v$playerRegister .. v$playerRegister }, " +
                                "$EXTENSION_CLASS->$extensionMethod(Ljava/lang/Object;)V"
                        )
                    }
                }
            }
        }

        hookPlayerSessionCalls(startMethodName, listOf(), "setMediaSessionOwner")
        hookPlayerSessionCalls(stopMethodName, listOf("Z"), "setMediaSessionStoppingPlayer")

        MediaSessionStopFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->isMediaSessionStopAllowed(Z)Z
                move-result v0
                if-nez v0, :stop
                return-void
                :stop
                nop
            """
        )
    }
}
