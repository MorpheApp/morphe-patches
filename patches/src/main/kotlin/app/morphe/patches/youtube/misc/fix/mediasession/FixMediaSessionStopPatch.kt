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
 * All players share one media session and one background playback. A player that did not start
 * the session can stop them, for example the player of the comment replies panel when the panel is closed.
 * The media notification goes away, and the background player service is stopped. When the app goes
 * to the background, the service is started again from the background, and Android mutes the audio
 * of a service started from the background (seen on Android 17), while the video keeps playing.
 *
 * The player that starts the session is its owner, and a session stop or a background playback stop
 * from another player is ignored. 21.42+ has an A/B flag that does the same for the session stop only.
 */
internal val fixMediaSessionStopPatch = patch {
    dependsOn(
        sharedExtensionPatch,
        versionCheckPatch,
    )

    execute {
        val sessionClass = MediaSessionStartFingerprint.originalClassDef.type
        val startMethodName = MediaSessionStartFingerprint.originalMethod.name
        val playerClass = playerMediaSessionStartFingerprint(sessionClass, startMethodName)
            .originalClassDef.type

        /**
         * Calls the extension method with the player that calls the method,
         * right before the player reads the object of the method from its field (the register
         * of the player can be reused for the object). Calls that are not made by a player are not changed.
         */
        fun hookPlayerCalls(
            definingClass: String,
            methodName: String,
            parameters: List<String>,
            extensionMethod: String
        ) {
            val call = methodCall(
                definingClass = definingClass,
                name = methodName,
                parameters = parameters,
                returnType = "V"
            )
            val playerField = fieldAccess(
                opcode = Opcode.IGET_OBJECT,
                definingClass = playerClass,
                type = definingClass
            )

            methodCallFingerprint(definingClass, methodName, parameters).matchAll().forEach { match ->
                match.method.apply {
                    // Several calls can use the object read once.
                    findInstructionIndicesReversedOrThrow(call).map { callIndex ->
                        indexOfFirstInstructionReversed(callIndex, playerField)
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

        hookPlayerCalls(sessionClass, startMethodName, listOf(), "setMediaSessionOwner")

        BackgroundPlaybackStopFingerprint.let {
            hookPlayerCalls(
                it.originalClassDef.type,
                it.originalMethod.name,
                listOf(),
                "setBackgroundPlaybackStoppingPlayer"
            )

            it.method.addInstructionsWithLabels(
                0,
                """
                    invoke-static { }, $EXTENSION_CLASS->isBackgroundPlaybackStopAllowed()Z
                    move-result v0
                    if-nez v0, :stop
                    return-void
                    :stop
                    nop
                """
            )
        }

        if (is_21_42_or_greater) {
            MediaSessionOwnerFeatureFlagFingerprint.method.insertLiteralOverride(
                MEDIA_SESSION_OWNER_FEATURE_FLAG,
                true
            )
            return@execute
        }

        hookPlayerCalls(
            sessionClass,
            MediaSessionStopFingerprint.originalMethod.name,
            listOf("Z"),
            "setMediaSessionStoppingPlayer"
        )

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
