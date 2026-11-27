/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.interaction.downloads

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patcher.util.cloneParameters
import app.morphe.patcher.util.numberOfParameterRegistersLogical
import app.morphe.patcher.util.registersUsed
import app.morphe.patches.music.layout.hide.flyoutmenu.MenuItemFingerprint
import app.morphe.patches.music.layout.hide.flyoutmenu.MenuItemIconFingerprint
import app.morphe.patches.shared.TextComponentLookupFingerprint
import app.morphe.patches.shared.misc.fix.proto.parseByteArrayWithRegistryMethodRef
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/downloads/InAppDownloadState;"
private const val ENDPOINT_RESOLVER =
    "Lapp/morphe/extension/music/patches/downloads/InAppDownloadState\$EndpointResolver;"
private const val TRACK_STORE =
    "Lapp/morphe/extension/music/patches/downloads/InAppDownloadState\$TrackStore;"
private const val ALREADY_SAVED_CALLBACK =
    "Lapp/morphe/extension/music/patches/downloads/InAppDownloadState\$AlreadySavedCallback;"

/**
 * Shows the downloaded songs in their menus, and asks to remove the download
 * when the download button of a downloaded song is tapped.
 */
internal fun hookInAppDownloadState() {
    // The resolver of the offline video endpoint is created when a download is first tapped.
    MusicOfflineVideoEndpointFingerprint.let {
        val resolveMethod = it.method
        val commandType = resolveMethod.parameterTypes.first().toString()

        it.classDef.addPatchInterface(
            ENDPOINT_RESOLVER,
            "patch_resolveCommand",
            """
                sget-object v0, ${protoDefaultInstance(commandType)}
                invoke-static { }, $GET_GENERATED_REGISTRY
                move-result-object v1
                invoke-static { v0, p1, v1 }, ${parseByteArrayWithRegistryMethodRef.get()!!}
                move-result-object v0
                check-cast v0, $commandType
                new-instance v1, Ljava/util/HashMap;
                invoke-direct { v1 }, Ljava/util/HashMap;-><init>()V
                invoke-virtual { p0, v0, v1 }, $resolveMethod
                return-void
            """
        )
    }

    MusicOfflineVideoEndpointConstructorFingerprint.let {
        it.method.addInstruction(
            it.instructionMatches.first().index,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->setEndpointResolver($ENDPOINT_RESOLVER)V"
        )
    }

    MusicSaveTrackFingerprint.let {
        val trackStoreClass = mutableClassDefBy(it.instructionMatches.first().fieldReference!!.type)

        MusicTrackStoreConstructorFingerprint.match(trackStoreClass).let { constructor ->
            constructor.method.addInstruction(
                constructor.instructionMatches.first().index,
                "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->setTrackStore($TRACK_STORE)V"
            )
        }

        trackStoreClass.addPatchInterface(
            TRACK_STORE,
            "patch_readTrackState",
            it.instructionMatches[1].methodReference!!
        )
        trackStoreClass.addPatchInterface(
            TRACK_STORE,
            "patch_isDownloadComplete",
            MusicDownloadCompletedNotificationFingerprint.instructionMatches[1].methodReference!!
        )
    }

    // Ask to remove the download instead of showing "Video already saved".
    MusicVideoAlreadySavedFingerprint.let {
        it.classDef.addPatchInterface(
            ALREADY_SAVED_CALLBACK,
            "patch_getVideoId",
            it.instructionMatches.last().fieldReference!!
        )

        val toastIndex = it.instructionMatches.first().index
        val freeRegister = it.method.getInstruction<OneRegisterInstruction>(toastIndex).registerA
        it.method.cloneParameters().addInstructionsAtControlFlowLabel(
            toastIndex + it.method.numberOfParameterRegistersLogical,
            """
                invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->onAlreadySaved($ALREADY_SAVED_CALLBACK)Z
                move-result v$freeRegister
                if-eqz v$freeRegister, :show_toast
                return-void
                :show_toast
                nop
            """
        )
    }

    // Native menus, such as the menus of the downloads list, bind their labels to text views.
    MenuItemIconFingerprint.let {
        // Another patch can have changed the method.
        it.clearMatch()
        val setText = it.instructionMatches.first()
        val textViewRegister = setText.instruction.registersUsed.first()

        it.method.addInstruction(
            setText.index + 1,
            "invoke-static/range { v$textViewRegister .. v$textViewRegister }, " +
                    "$EXTENSION_CLASS->onNativeMenuLabelBound(Landroid/widget/TextView;)V"
        )

        // Before 9.44 this is the same method as the menu item, so its match is outdated too.
        it.clearMatch()
        MenuItemFingerprint.clearMatch()
    }

    // Litho menus reuse the labels they cached, so the label is updated when it's bound.
    TextComponentLookupFingerprint.method.apply {
        val setTextIndex = indexOfFirstInstructionOrThrow(
            0,
            fieldAccess(opcode = Opcode.IPUT_OBJECT, type = "Ljava/lang/CharSequence;")
        )
        val textRegister = getInstruction<TwoRegisterInstruction>(setTextIndex).registerA

        addInstructionsAtControlFlowLabel(
            setTextIndex,
            """
                invoke-static/range { v$textRegister .. v$textRegister }, $EXTENSION_CLASS->onLithoMenuLabelBound(Ljava/lang/CharSequence;)Ljava/lang/CharSequence;
                move-result-object v$textRegister
            """
        )
    }
}
