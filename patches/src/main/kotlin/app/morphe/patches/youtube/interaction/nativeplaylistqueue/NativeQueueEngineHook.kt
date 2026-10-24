/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.nativeplaylistqueue

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.cloneParameters
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.matchSingle
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findFieldFromToString
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField

/** Supply local rows to the stock mutable queue; its sequencer owns navigation. */
internal fun installNativeQueueEngine(
    context: BytecodePatchContext,
    serviceType: String,
    enumType: String
) = with(context) {
    // The native playback queue, its navigator and the transaction around its edits.
    val queueMatch = NativeQueueDescriptorFingerprint.matchSingle()
    val queueType = queueMatch.originalClassDef.type
    val requestType = queueMatch.originalMethod.parameterTypes.single().toString()
    val descriptorType = queueMatch.originalMethod.returnType
    val beginQueueChange = queueMatch.instructionMatches[0].instruction.getReference<MethodReference>()!!
    val queueOwner = queueMatch.instructionMatches[2].instruction.getReference<FieldReference>()!!
    val endQueueChange = queueMatch.instructionMatches[5].instruction.getReference<MethodReference>()!!

    val queueChangeEnd = nativeQueueChangeEndFingerprint(endQueueChange).matchSingle()
    val changeGuard = queueChangeEnd.instructionMatches[0].instruction.getReference<FieldReference>()!!
    val releaseChange = queueChangeEnd.instructionMatches[1].instruction.getReference<MethodReference>()!!
    val refresh = queueChangeEnd.instructionMatches[2].instruction.getReference<MethodReference>()!!

    val queueDisposeMatch = nativeQueueDisposeFingerprint(queueType).matchSingle()
    val queueDispose = queueDisposeMatch.originalMethod

    // The wrapper switches between the server's navigator and the local queue.
    val wrapperFlag = nativeQueueWrapperConstructorFingerprint(queueType).matchSingle()
        .instructionMatches.last().instruction.getReference<FieldReference>()!!
    val wrapperType = wrapperFlag.definingClass

    val receive = nativeQueueReceiveFingerprint(wrapperFlag).matchSingle()
    fun receiveField(index: Int) = receive.instructionMatches[index].instruction.getReference<FieldReference>()!!
    fun receiveMethod(index: Int) = receive.instructionMatches[index].instruction.getReference<MethodReference>()!!
    val responseType = receive.originalMethod.parameterTypes.single().toString()
    val responsePanel = receiveField(1)
    val nativeNavigator = receiveField(3)
    val container = receiveField(4)
    val builderConstructor = receiveMethod(6)
    val watchCommand = receiveField(7)
    val commandField = receiveField(8)
    val buildDescriptor = receiveMethod(9)
    val containerFactory = receiveMethod(11)
    val switchNavigator = receiveMethod(13)
    val response = receiveField(16)
    val nativeReceive = receiveMethod(18)
    val navigatorType = nativeNavigator.type
    val panelType = responsePanel.type

    val acceptMatch = nativeQueueAcceptFingerprint(queueType, responseType, beginQueueChange).matchSingle()
    val accept = acceptMatch.originalMethod
    val storedResponse = acceptMatch.instructionMatches.last().instruction.getReference<FieldReference>()!!

    val repeatSetter = nativeQueueRepeatSetterFingerprint(wrapperType).matchSingle()
        .instructionMatches.last().instruction.getReference<MethodReference>()!!
    val repeatGetter = nativeQueueRepeatGetterFingerprint(wrapperType).matchSingle()
        .instructionMatches.last().instruction.getReference<MethodReference>()!!

    val responseConstructor = nativeQueueResponseConstructorFingerprint(responseType).matchSingle().originalMethod
    val sourceResponse = nativeQueueResponseSourceFingerprint(
        responseType,
        responseConstructor.parameterTypes.single().toString()
    ).matchSingle().instructionMatches.single().instruction.getReference<FieldReference>()!!
    val navigationType = PlaylistModelDescriptionFingerprint.matchSingle().originalMethod
        .findFieldFromToString(", navigationSets=").type
    val responseNavigation = nativeQueueResponseNavigationFingerprint(responseType, navigationType).matchSingle()
        .instructionMatches.single().instruction.getReference<FieldReference>()!!

    // The mutable queue holds local rows and builds the shuffle order.
    val shuffle = NativeQueueShuffleFingerprint.matchSingle()
    val mutableType = shuffle.originalClassDef.type
    val shuffleSetter = shuffle.originalMethod
    val position = shuffle.instructionMatches[2].instruction.getReference<MethodReference>()!!
    val shuffleLinks = listOf(
        shuffle.instructionMatches[5].instruction.getReference<FieldReference>()!!,
        shuffle.instructionMatches[7].instruction.getReference<FieldReference>()!!
    )

    val localLoad = nativeQueueLocalLoadFingerprint(mutableType, descriptorType, requestType).matchSingle()
    val playlistId = localLoad.instructionMatches.first().instruction.getReference<MethodReference>()!!

    val localFlag = ImmutableField(
        wrapperType, "patch_localQueue", "Z", AccessFlags.PRIVATE.value, null, null, null
    ).toMutable()
    val localItemsFlag = ImmutableField(
        mutableType, "patch_localQueue", "Z", AccessFlags.PUBLIC.value, null, null, null
    ).toMutable()
    mutableClassDefBy(wrapperType).fields.add(localFlag)
    mutableClassDefBy(mutableType).fields.add(localItemsFlag)
    mutableClassDefBy(wrapperType).interfaces.add(NATIVE_PLAYBACK_INTERFACE)

    fun bridge(type: String, name: String, parameters: List<String>, result: String, locals: Int, body: String) =
        mutableClassDefBy(type).addBridgeMethod(name, parameters, result, locals, body)

    bridge(wrapperType, "patch_isLocalQueue", emptyList(), "Z", 1, """
        iget-boolean v0, p0, $localFlag
        return v0
    """)

    // Copy the response envelope. Other native consumers retain the untouched server model.
    bridge(wrapperType, "patch_localPanel", listOf("[B"), responseType, 4, """
        iget-object v1, p0, $response
        if-eqz v1, :missing
        iget-object v1, v1, $sourceResponse
        new-instance v0, $responseType
        invoke-direct {v0, v1}, $responseConstructor
        ${parseProtoInstructions(panelType, bytes = "p1", result = "v1", registry = "v2")}
        check-cast v1, $panelType
        iput-object v1, v0, $responsePanel
        return-object v0

        :missing
        const/4 v0, 0x0
        return-object v0
    """)

    bridge(wrapperType, "patch_restoreQueueRepeat", listOf("I"), "V", 1, """
        iget-boolean v0, p0, $localFlag
        if-eqz v0, :done
        iget-object v0, p0, $nativeNavigator
        invoke-interface {v0, p1}, $repeatSetter

        :done
        return-void
    """)

    // Disable only the mutable shuffler, without publishing an intermediate mode.
    // The old index links cannot be used while the accepted rows are replaced.
    bridge(mutableType, "patch_clearShuffleOrder", emptyList(), "V", 1, """
        const/4 v0, 0x0
        invoke-virtual {p0, v0}, $shuffleSetter
        iput-object v0, p0, ${shuffleLinks[0]}
        iput-object v0, p0, ${shuffleLinks[1]}
        return-void
    """)

    bridge(wrapperType, "patch_attachQueue", listOf("[B", "[B"), "Z", 7, """
        invoke-virtual {p0, p1}, $wrapperType->patch_localPanel([B)$responseType
        move-result-object v0
        if-eqz v0, :missing
        iget-object v1, p0, $nativeNavigator
        invoke-interface {v1}, $repeatGetter
        move-result v6
        # Reuse an existing playlist queue before constructing an INSERT request.
        # Its acceptance transaction suppresses selected-item playback callbacks.
        instance-of v3, v1, $queueType
        if-nez v3, :existing
        new-instance v2, ${builderConstructor.definingClass}
        invoke-direct {v2}, $builderConstructor
        ${parseProtoInstructions(watchCommand.type, bytes = "p2", result = "v3", registry = "v4")}
        check-cast v3, ${watchCommand.type}
        iput-object v3, v2, $commandField
        invoke-virtual {v2}, $buildDescriptor
        move-result-object v2
        invoke-virtual {v2}, $playlistId
        move-result-object v3
        invoke-virtual {v3}, Ljava/lang/String;->isEmpty()Z
        move-result v3
        if-eqz v3, :missing
        iget-object v3, p0, $container
        invoke-virtual {v3, v2}, $containerFactory
        move-result-object v3
        goto :populate

        :existing
        move-object v3, v1

        :populate
        check-cast v3, $queueType
        iget-object v4, v3, $queueOwner
        instance-of v5, v4, $mutableType
        if-eqz v5, :missing
        check-cast v4, $mutableType
        invoke-virtual {v4}, $mutableType->patch_clearShuffleOrder()V
        invoke-virtual {v3, v0}, $accept
        const/4 v5, 0x1
        iput-boolean v5, v4, $localItemsFlag
        invoke-direct {p0, v1, v3}, $switchNavigator
        iput-boolean v5, p0, $wrapperFlag
        iput-boolean v5, p0, $localFlag
        invoke-interface {v3, v6}, $repeatSetter
        invoke-virtual {v3, v5}, $refresh
        return v5

        :missing
        const/4 v0, 0x0
        return v0
    """)

    bridge(wrapperType, "patch_updateSnapshot", listOf("[B"), "V", 2, """
        iget-boolean v0, p0, $localFlag
        if-eqz v0, :done
        invoke-virtual {p0, p1}, $wrapperType->patch_localPanel([B)$responseType
        move-result-object v0
        iget-object v1, p0, $nativeNavigator
        check-cast v1, $queueType
        invoke-virtual {v1, v0}, $queueType->patch_updateSnapshot($responseType)V

        :done
        return-void
    """)

    bridge(queueType, "patch_updateSnapshot", listOf(responseType), "V", 0, """
        iput-object p1, p0, $storedResponse
        return-void
    """)

    bridge(wrapperType, "patch_getSelectedIndex", emptyList(), "I", 1, """
        iget-object v0, p0, $nativeNavigator
        check-cast v0, $queueType
        iget-object v0, v0, $queueOwner
        invoke-interface {v0}, ${queueOwner.type}->${position.name}()I
        move-result v0
        return v0
    """)

    bridge(wrapperType, "patch_getRepeatMode", emptyList(), "I", 1, """
        iget-object v0, p0, $nativeNavigator
        check-cast v0, $queueType
        invoke-interface {v0}, $repeatGetter
        move-result v0
        return v0
    """)

    bridge(wrapperType, "patch_ignorePlaylistResponse", listOf(responseType), "Z", 1, """
        if-eqz p1, :missing
        iget-object p1, p1, $watchCommand
        invoke-static/range {p0 .. p1}, $EXTENSION_CLASS->shouldIgnorePlaylistResponse(${NATIVE_PLAYBACK_INTERFACE}Lcom/google/protobuf/MessageLite;)Z
        move-result v0
        return v0

        :missing
        const/4 v0, 0x0
        return v0
    """)

    // Required native response feed, separate from the engagement-panel presentation feed.
    // The native parser reuses its receiver register for the delegated navigator.
    // Preserve parameter registers before adding the post-response ownership callback.
    val receiveMethod = receive.method.cloneParameters()
    receiveMethod.findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { index ->
        receiveMethod.addInstruction(
            index,
            "invoke-static/range {p0 .. p0}, $EXTENSION_CLASS->onNativeQueueResponse($NATIVE_PLAYBACK_INTERFACE)V"
        )
    }

    val receiveRegister = receiveMethod.findFreeRegister(0)
    receiveMethod.addInstructionsWithLabels(0, """
        invoke-virtual/range {p0 .. p1}, $wrapperType->patch_ignorePlaylistResponse($responseType)Z
        move-result v$receiveRegister
        if-eqz v$receiveRegister, :current_response
        return-void

        :current_response
        iget-boolean v$receiveRegister, p0, $localFlag
        if-eqz v$receiveRegister, :server_queue
        iput-object p1, p0, $response
        return-void

        :server_queue
        nop
    """)

    // Server autoplay replaces the navigator. Remember its last state for Clear.
    val autoplay = nativeQueueAutoplayFingerprint(nativeNavigator, switchNavigator).matchSingle()
    val autoplayType = autoplay.originalMethod.parameterTypes.single().toString()
    val autoplayConstructor = nativeQueueAutoplayConstructorFingerprint(autoplayType).matchSingle().originalMethod
    val optionalType = autoplayConstructor.parameterTypes.first().toString()
    val savedAutoplay = ImmutableField(
        wrapperType, "patch_lastAutoplay", autoplayType, AccessFlags.PRIVATE.value, null, null, null
    ).toMutable()
    mutableClassDefBy(wrapperType).fields.add(savedAutoplay)

    var autoplayMethod = autoplay.method
    if (autoplayMethod.implementation!!.registerCount <= 2) autoplayMethod = autoplayMethod.cloneParameters()
    val autoplayRegister = autoplayMethod.findFreeRegister(0)
    autoplayMethod.addInstructionsWithLabels(0, """
        iput-object p1, p0, $savedAutoplay
        iget-boolean v$autoplayRegister, p0, $localFlag
        if-eqz v$autoplayRegister, :server_autoplay
        return-void

        :server_autoplay
        nop
    """)

    // Queue storage also owns the selected-item listener that loads/unloads playback.
    // Reuse the native transaction guard (used by accept/commit) while disposing;
    // removing the last item must not send that listener a null current video.
    bridge(queueType, "patch_disposeWithoutPlayback", emptyList(), "V", 3, """
        invoke-direct {p0}, $beginQueueChange
        move-result-object v0
        iget-object v1, p0, $changeGuard
        invoke-virtual {p0}, $queueDispose
        invoke-virtual {v1, v0}, $releaseChange
        return-void

        move-exception v2
        invoke-virtual {v1, v0}, $releaseChange
        throw v2
    """).implementation!!.apply {
        // Guard the dispose call (instruction 3) with the handler at instruction 6.
        addCatch(newLabelForIndex(3), newLabelForIndex(4), newLabelForIndex(6))
    }

    // Reuse the ordinary autoplay navigator's initialization and response handling.
    // This detaches the local sequence without loading/seeking/stopping the player.
    bridge(wrapperType, "patch_clearQueue", emptyList(), "Z", 6, """
        iget-boolean v0, p0, $localFlag
        if-eqz v0, :missing
        iget-object v1, p0, $response
        if-eqz v1, :missing
        iget-object v2, p0, $nativeNavigator
        instance-of v0, v2, $queueType
        if-eqz v0, :missing
        new-instance v4, ${builderConstructor.definingClass}
        invoke-direct {v4}, $builderConstructor
        iget-object v0, v1, $watchCommand
        iput-object v0, v4, $commandField
        invoke-virtual {v4}, $buildDescriptor
        move-result-object v4
        invoke-virtual {v4}, $playlistId
        move-result-object v4
        invoke-virtual {v4}, Ljava/lang/String;->isEmpty()Z
        move-result v0
        if-nez v0, :ordinary_autoplay
        # A replaced playlist must not return through its old next/previous sets.
        # Copy the response, leaving the running descriptor and server data untouched.
        iget-object v0, v1, $sourceResponse
        new-instance v1, $responseType
        invoke-direct {v1, v0}, $responseConstructor
        const/4 v0, 0x0
        iput-object v0, v1, $responseNavigation
        const/4 v3, 0x0
        const/4 v5, 0x0
        goto :create_autoplay

        :ordinary_autoplay
        iget-object v3, p0, $savedAutoplay
        move-object v5, v3
        if-nez v3, :autoplay_ready

        :create_autoplay
        invoke-static {}, $optionalType->empty()$optionalType
        move-result-object v4
        new-instance v3, $autoplayType
        invoke-direct {v3, v4, v4, v4}, $autoplayConstructor

        :autoplay_ready
        const/4 v0, 0x0
        iput-boolean v0, p0, $localFlag
        invoke-virtual {p0, v3}, ${autoplay.originalMethod}
        iput-object v5, p0, $savedAutoplay
        iget-object v4, p0, $nativeNavigator
        invoke-interface {v4, v1}, $nativeReceive
        if-eqz v5, :dispose_queue
        invoke-virtual {p0, v5}, ${autoplay.originalMethod}

        :dispose_queue
        check-cast v2, $queueType
        invoke-virtual {v2}, $queueType->patch_disposeWithoutPlayback()V
        const/4 v0, 0x1
        return v0

        :missing
        const/4 v0, 0x0
        return v0
    """)

    bridge(queueType, "patch_releaseLocalQueue", emptyList(), "V", 2, """
        iget-object v0, p0, $queueOwner
        instance-of v1, v0, $mutableType
        if-eqz v1, :done
        check-cast v0, $mutableType
        const/4 v1, 0x0
        iput-boolean v1, v0, $localItemsFlag

        :done
        return-void
    """)

    queueDisposeMatch.method.addInstruction(
        0,
        "invoke-virtual/range {p0 .. p0}, $queueType->patch_releaseLocalQueue()V"
    )

    val wrapperDispose = nativeQueueWrapperDisposeFingerprint(nativeNavigator, queueDispose.name).matchSingle()
    val disposeRegister = wrapperDispose.method.findFreeRegister(0)
    wrapperDispose.method.addInstructionsWithLabels(0, """
        const/4 v$disposeRegister, 0x0
        iput-boolean v$disposeRegister, p0, $localFlag
        iput-object v$disposeRegister, p0, $savedAutoplay
    """)

    // Local row loads must be JUMP within the native queue, rather than new ordinary playback.
    val requestConstructor = playlistNavigationRequestFingerprint(enumType).matchSingle().originalMethod
    val jump = playlistNavigationIntentFingerprint(enumType, "JUMP").matchSingle()
        .instructionMatches.last().instruction.getReference<FieldReference>()!!
    bridge(
        mutableType,
        "patch_localJump",
        listOf(descriptorType, requestConstructor.parameterTypes.last().toString()),
        requestType,
        2,
        """
            new-instance v0, $requestType
            sget-object v1, $jump
            invoke-direct {v0, v1, p1, p2}, $requestConstructor
            return-object v0
        """
    )

    // Only one native local is needed: the request result occupies it after the flag check.
    val insertMethod = localLoad.method.cloneParameters()
    val insertRegister = insertMethod.findFreeRegister(0)
    val loadParameters = localLoad.originalMethod.parameterTypes.joinToString("")
    insertMethod.addInstructionsWithLabels(0, """
        iget-boolean v$insertRegister, p0, $localItemsFlag
        if-eqz v$insertRegister, :account_insert
        invoke-virtual {p0, p1, p2}, $mutableType->patch_localJump($loadParameters)$requestType
        move-result-object v$insertRegister
        return-object v$insertRegister

        :account_insert
        nop
    """)

    // The playback service holds the active sequencer, which holds the wrapper.
    val loader = nativeQueueLoaderFingerprint(serviceType).matchSingle()
    val sequencer = loader.instructionMatches[0].instruction.getReference<FieldReference>()!!
    val sequencerType = loader.instructionMatches[2].instruction.getReference<TypeReference>()!!.type
    val sequencerOwner = nativeQueueSequencerOwnerFingerprint(serviceType, sequencer.definingClass).matchSingle()
        .instructionMatches.first().instruction.getReference<FieldReference>()!!
    val sequencerField = nativeQueueSequencerNavigatorFingerprint(sequencerType, navigatorType).matchSingle()
        .instructionMatches.single().instruction.getReference<FieldReference>()!!

    bridge(serviceType, "patch_getQueuePlayback", emptyList(), NATIVE_PLAYBACK_INTERFACE, 1, """
        iget-object v0, p0, $sequencerOwner
        iget-object v0, v0, $sequencer
        if-eqz v0, :missing
        check-cast v0, $sequencerType
        iget-object v0, v0, $sequencerField
        instance-of p0, v0, $wrapperType
        if-eqz p0, :missing
        check-cast v0, $wrapperType
        return-object v0

        :missing
        const/4 v0, 0x0
        return-object v0
    """)

    // Selection can commit after the shared video-ID event. Observe native completion
    // without passing a receiver register that the native method may have reused.
    val refreshMethod = nativeQueueRefreshFingerprint(refresh).method
    refreshMethod.findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { index ->
        refreshMethod.addInstructionsAtControlFlowLabel(
            index,
            "invoke-static {}, $EXTENSION_CLASS->onNativeQueueSelection()V"
        )
    }
}
