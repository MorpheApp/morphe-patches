/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.interaction.downloads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.checkCast
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patcher.util.cloneParameters
import app.morphe.patcher.util.getMutableMethod
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.numberOfParameterRegistersLogical
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val STATE = "Lapp/morphe/extension/music/patches/downloads/InAppDownloadState"
private const val RESOLVER = "$STATE\$Resolver;"
private const val CONTROLLER = "$STATE\$Controller;"
private const val SAVE_CALLBACK = "$STATE\$SaveCallback;"
private const val STRING = "Ljava/lang/String;"
private const val OBJECT = "Ljava/lang/Object;"
private const val LIST = "Ljava/util/List;"
private const val FUTURE = "Lcom/google/common/util/concurrent/ListenableFuture;"

private object AlreadySavedFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(OBJECT),
    strings = listOf("PPSV"),
    filters = listOf(resourceLiteral(ResourceType.STRING, "video_already_added_to_offline")),
)

private fun Method.calls() = implementation!!.instructions.mapNotNull { it.getReference<MethodReference>() }

internal fun hookInAppDownloadState() {
    val resolver = MusicOfflineVideoEndpointFingerprint
    val save = resolver.instructionMatches.first()
    val remove = resolver.method.calls().distinct().single {
        it.definingClass == save.methodReference.definingClass &&
            it.parameterTypes == listOf(STRING) && it.returnType == "V"
    }
    val alreadySaved = AlreadySavedFingerprint
    val controllerField = alreadySaved.classDef.instanceFields.single {
        it.type == remove.definingClass || classDefByOrNull(it.type)?.interfaces?.contains(remove.definingClass) == true
    }
    val videoIdField = alreadySaved.classDef.instanceFields.single { it.type == STRING }
    val controller = mutableClassDefBy(controllerField.type)
    val promptRemoval = controller.methods.single { it.name == remove.name && it.parameterTypes == remove.parameterTypes }

    // Read the same current-account playlist snapshot used by the app's removal action.
    val read = promptRemoval.calls().single { it.parameterTypes == listOf(STRING) && it.returnType == FUTURE }
    val databaseField = promptRemoval.implementation!!.instructions.mapNotNull { it.getReference<FieldReference>() }
        .distinct().single { it.type == read.definingClass }
    val playlistKey = promptRemoval.calls().single { it.parameterTypes.isEmpty() && it.returnType == STRING }
    val removalCallback = promptRemoval.calls().single {
        it.name == "<init>" && it.parameterTypes == listOf(controller.type, STRING)
    }
    val onOfflinePlaylistsLoaded = classDefBy(removalCallback.definingClass).virtualMethods.single {
        it.parameterTypes == listOf(OBJECT) && it.returnType == "V"
    }
    val calls = onOfflinePlaylistsLoaded.calls()
    val optionalGet = calls.single { it.name == "get" && it.parameterTypes.isEmpty() && it.returnType == OBJECT }
    val optionalIsPresent = calls.single { it.definingClass == optionalGet.definingClass && it.name == "isPresent" }
    val trackKey = calls.distinct().single { it.parameterTypes == listOf(STRING) && it.returnType == STRING }
    val songLists = calls.filter { it.parameterTypes.isEmpty() && it.returnType == LIST }
    check(songLists.size == 3 && songLists.map { it.definingClass }.distinct().size == 1) {
        "Unexpected YouTube Music offline song lists"
    }
    val containsTrack = songLists.joinToString("\n") { getter ->
        """
            invoke-virtual { v0 }, $getter
            move-result-object v2
            invoke-interface { v2, v1 }, $LIST->contains(Ljava/lang/Object;)Z
            move-result v2
            if-nez v2, :found
        """.trimIndent()
    }
    controller.addPatchInterface(CONTROLLER, "patch_readDownloadedSongs", """
        iget-object v0, p0, $databaseField
        invoke-static { }, $playlistKey
        move-result-object v1
        invoke-interface { v0, v1 }, $read
        move-result-object v0
        return-object v0
    """)
    controller.addPatchInterface(CONTROLLER, "patch_containsDownloadedSong", """
        if-eqz p1, :absent
        check-cast p1, ${optionalGet.definingClass}
        invoke-virtual { p1 }, $optionalIsPresent
        move-result v0
        if-eqz v0, :absent
        invoke-virtual { p1 }, $optionalGet
        move-result-object v0
        check-cast v0, ${songLists.first().definingClass}
        invoke-static { p2 }, $trackKey
        move-result-object v1
        $containsTrack
        :absent
        const/4 v0, 0x0
        return v0
        :found
        const/4 v0, 0x1
        return v0
    """)
    controller.addPatchInterface(CONTROLLER, "patch_promptRemoval", promptRemoval)

    // Playlist membership also includes queued transfers. Use the same per-track state as
    // the save controller to distinguish completed downloads.
    val saveMethod = controller.methods.single {
        it.name == save.methodReference.name && it.parameterTypes == save.methodReference.parameterTypes
    }
    val readTrack = saveMethod.calls().single {
        it.parameterTypes == listOf(STRING) && it.returnType == FUTURE && it != read
    }
    val trackRepositoryField = saveMethod.implementation!!.instructions.mapNotNull { it.getReference<FieldReference>() }
        .distinct().single { it.type == readTrack.definingClass }
    val trackRepository = classDefBy(readTrack.definingClass)
    val offlineChecks = alreadySaved.method.calls().distinct().filter {
        it.definingClass == trackRepository.type && it.returnType == "Z"
    }
    val offlineEligibility = offlineChecks.single { it.parameterTypes.size == 1 }
    val trackState = offlineEligibility.parameterTypes.single().toString()
    val optionalType = optionalGet.definingClass
    val trackStateGetters = trackRepository.methods.single { it.name == offlineEligibility.name }.calls()
        .filter { it.definingClass == trackState && it.returnType == optionalType }
    val (_, getTransfer, getProgress) = trackStateGetters.also { check(it.size == 3) }
    val transferCheck = offlineChecks.single { it.parameterTypes == listOf(optionalType, optionalType) }
    val getTransferState = trackRepository.methods.single { it.name == transferCheck.name }.calls().single {
        it.definingClass == trackRepository.type && it.parameterTypes == listOf(optionalType)
    }
    val progressComplete = Fingerprint(
        parameters = listOf(optionalType),
        returnType = "Z",
        filters = listOf(methodCall(name = "getStreamsProgress", returnType = LIST)),
    ).match(trackRepository).method

    controller.addPatchInterface(CONTROLLER, "patch_readTrackState", """
        iget-object v0, p0, $trackRepositoryField
        invoke-virtual { v0, p1 }, $readTrack
        move-result-object v0
        return-object v0
    """)
    controller.addPatchInterface(CONTROLLER, "patch_isTrackDownloadComplete", """
        if-eqz p1, :pending
        check-cast p1, $trackState
        invoke-virtual { p1 }, $getTransfer
        move-result-object v0
        invoke-static { v0 }, $getTransferState
        move-result-object v0
        invoke-virtual { v0 }, Ljava/lang/Enum;->name()Ljava/lang/String;
        move-result-object v0
        const-string v1, "TRANSFER_STATE_COMPLETE"
        invoke-virtual { v1, v0 }, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
        move-result v0
        if-nez v0, :complete
        iget-object v0, p0, $trackRepositoryField
        invoke-virtual { p1 }, $getProgress
        move-result-object v1
        invoke-virtual { v0, v1 }, $progressComplete
        move-result v0
        return v0
        :complete
        const/4 v0, 0x1
        return v0
        :pending
        const/4 v0, 0x0
        return v0
    """)

    // Use the command resolver's provider so a menu can check the store before the first download tap.
    val provider = Fingerprint(
        filters = listOf(
            fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = resolver.classDef.type),
            methodCall(
                opcodes = listOf(Opcode.INVOKE_INTERFACE, Opcode.INVOKE_VIRTUAL),
                parameters = emptyList(),
                returnType = OBJECT,
                location = MatchAfterImmediately(),
            ),
            opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
            checkCast(remove.definingClass, location = MatchAfterImmediately()),
        ),
    ).match(resolver.method, resolver.classDef).instructionMatches
    val providerField = provider.first().fieldReference
    val getController = provider[1]
    val invokeProvider = if (getController.instruction.opcode == Opcode.INVOKE_INTERFACE) {
        "invoke-interface"
    } else {
        "invoke-virtual"
    }
    resolver.classDef.addPatchInterface(RESOLVER, "patch_downloadController", """
        iget-object v0, p0, $providerField
        $invokeProvider { v0 }, ${getController.methodReference}
        move-result-object v0
        check-cast v0, $CONTROLLER
        return-object v0
    """)
    resolver.classDef.directMethods.filter { it.name == "<init>" }.forEach { constructor ->
        constructor.getMutableMethod().addInstruction(
            constructor.implementation!!.instructions.count() - 1,
            "invoke-static/range { p0 .. p0 }, $STATE;->setResolver($RESOLVER)V",
        )
    }

    alreadySaved.classDef.addPatchInterface(SAVE_CALLBACK, "patch_videoId", videoIdField)
    alreadySaved.classDef.addPatchInterface(SAVE_CALLBACK, "patch_downloadController", """
        iget-object v0, p0, $controllerField
        check-cast v0, $CONTROLLER
        return-object v0
    """)
    val toast = alreadySaved.instructionMatches.single()
    val toastRegister = (toast.instruction as OneRegisterInstruction).registerA
    alreadySaved.method.cloneParameters().apply {
        addInstructionsAtControlFlowLabel(
            toast.index + numberOfParameterRegistersLogical,
            """
                invoke-static/range { p0 .. p1 }, $STATE;->onAlreadySaved($SAVE_CALLBACK$OBJECT)Z
                move-result v$toastRegister
                if-eqz v$toastRegister, :show_toast
                return-void
                :show_toast
                nop
            """,
        )
    }
}
