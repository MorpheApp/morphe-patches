/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.nativeplaylistqueue

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.matchSingle
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * Observe the shared native header/row service-menu callback. The callback already validates
 * and logs the command before this point. Local queue actions then call the same sheet dismissal
 * used by the native path. Unowned and other endpoints continue through the original call.
 */
internal fun installPlaylistMenuDispatch(context: BytecodePatchContext, callbackType: String) = with(context) {
    val dispatch = playlistMenuDispatchFingerprint(callbackType).matchSingle()
    val callback = dispatch.instructionMatches[1]
    val dismissal = dispatch.instructionMatches[2]

    val endpointType = callback.instruction.getReference<MethodReference>()!!.parameterTypes.first().toString()

    installQueueSavePicker(this, endpointType)

    val registers = callback.getInstruction<FiveRegisterInstruction>()
    val endpoint = registers.registerD
    dispatch.method.addInstructionsAtControlFlowLabel(
        callback.index,
        """
            invoke-static {v$endpoint, v${registers.registerE}}, $EXTENSION_CLASS->onQueueMenuCommand(Lcom/google/protobuf/MessageLite;Ljava/util/Map;)Lcom/google/protobuf/MessageLite;
            move-result-object v$endpoint
            if-nez v$endpoint, :native_menu_dispatch
            goto :native_menu_dismiss

            :native_menu_dispatch
            check-cast v$endpoint, $endpointType
        """,
        ExternalLabel("native_menu_dismiss", dispatch.method.getInstruction(dismissal.index))
    )
}
