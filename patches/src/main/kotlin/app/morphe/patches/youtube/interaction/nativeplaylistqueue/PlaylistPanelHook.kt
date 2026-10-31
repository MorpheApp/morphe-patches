/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.nativeplaylistqueue

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.cloneParameters
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.numberOfParameterRegistersLogical
import app.morphe.patcher.util.p0Register
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findFieldFromToString
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

// The native model factory initializes queueMode to 1 before accepting a watch response.
// Keep that neutral mode: rows use ordinary watch endpoints, without attaching
// a native playlist navigator through navigationSets.
private const val DEFAULT_QUEUE_MODE = 1

/** Bridge the accepted watch model; the playback engine is attached separately at runtime. */
internal fun installPlaylistPanel(context: BytecodePatchContext) = with(context) {
    val description = PlaylistModelDescriptionFingerprint.originalMethod
    val modelType = description.definingClass
    val responseVideoField = description.findFieldFromToString("{videoId=")
    val playlistField = description.findFieldFromToString(", playlistId=")
    val panelField = description.findFieldFromToString(", playlistPanel=")
    val navigationField = description.findFieldFromToString(", navigationSets=")
    val panelType = panelField.type
    val controllerType = PlaylistControllerConstructorFingerprint.originalClassDef.type
    val controller = mutableClassDefBy(controllerType)

    val accept = acceptedPlaylistModelFingerprint(controllerType, modelType, responseVideoField)
    val modelField = accept.instructionMatches.last().fieldReference
    val activePlaylistField = playlistControllerIdentityFingerprint(accept.originalMethod)
        .instructionMatches.first().fieldReference
    val activeVideoMatch = accept.instructionMatches[2]
    val activeVideoField = activeVideoMatch.fieldReference
    check(activeVideoMatch.getInstruction<TwoRegisterInstruction>().registerB == accept.originalMethod.p0Register) {
        "Native playlist queue: active video must belong to the playlist controller"
    }

    // The collapsed panel view, whose click opens the playlist.
    val collapsedView = playlistControlsCreationFingerprint(controllerType)
        .instructionMatches.first().fieldReference

    val render = playlistPanelRenderFingerprint(modelField)
    val transition = playlistVideoTransitionFingerprint(activeVideoField)

    // Ordinary videos can have no playlist model at all. Use the native builder's
    // defaults, including required tracking parameters, rather than inventing them.
    val buildModel = playlistModelBuildFingerprint(modelType).originalMethod
    val modelFactory = playlistModelFactoryFingerprint(modelType, buildModel.definingClass)
        .originalMethod

    // Constructor parameter positions are derived from the actual field stores.
    val constructor = playlistModelConstructorFingerprint(modelType)
    val constructorRegisters = constructor.originalMethod.p0Register
    val constructorFields = constructor.instructionMatches.sortedBy {
        it.getInstruction<TwoRegisterInstruction>().registerA
    }.map { it.fieldReference }
    check(
        constructor.instructionMatches.map {
            it.getInstruction<TwoRegisterInstruction>().registerA - constructorRegisters - 1
        }.sorted() == constructorFields.indices.toList()
    ) { "Native playlist queue: each model parameter must be stored directly" }

    fun addBridge(name: String, parameters: List<String>, returnType: String, locals: Int, body: String) =
        controller.addBridgeMethod(name, parameters, returnType, locals, body)

    controller.interfaces.add(CONTROLLER_INTERFACE)
    mutableClassDefBy(modelType).apply {
        interfaces.add(PLAYLIST_MODEL_INTERFACE)
        addBridgeMethod(
            "patch_getPlaylistId",
            emptyList(),
            "Ljava/lang/String;",
            1,
            """
                iget-object v0, p0, $playlistField
                return-object v0
            """
        )
    }

    // A watch response can omit its video ID. Use the native stale-response guard's
    // active video instead of treating an optional response field as playback state.
    addBridge(
        "patch_getVideoId",
        emptyList(),
        "Ljava/lang/String;",
        1,
        """
            iget-object v0, p0, $activeVideoField
            if-nez v0, :done
            const-string v0, ""

            :done
            return-object v0
        """
    )

    addBridge(
        "patch_getPlaylistId",
        emptyList(),
        "Ljava/lang/String;",
        1,
        """
            iget-object v0, p0, $modelField
            if-eqz v0, :empty
            iget-object v0, v0, $playlistField
            if-nez v0, :done

            :empty
            const-string v0, ""

            :done
            return-object v0
        """
    )

    addBridge(
        "patch_getModel",
        emptyList(),
        "Ljava/lang/Object;",
        1,
        """
            iget-object v0, p0, $modelField
            return-object v0
        """
    )

    addBridge(
        "patch_getPanel",
        emptyList(),
        "Ljava/lang/Object;",
        1,
        """
            iget-object v0, p0, $modelField
            if-eqz v0, :done
            iget-object v0, v0, $panelField

            :done
            return-object v0
        """
    )

    addBridge(
        "patch_isAttached",
        emptyList(),
        "Z",
        1,
        """
            iget-object v0, p0, $collapsedView
            invoke-virtual {v0}, Landroid/view/View;->isAttachedToWindow()Z
            move-result v0
            return v0
        """
    )

    val copyArguments = constructorFields.mapIndexed { index, field ->
        when {
            field == responseVideoField -> "iget-object v${index + 5}, p0, $activeVideoField"
            field == playlistField -> "const-string v${index + 5}, \"\""
            field == panelField -> "move-object v${index + 5}, v1"
            field == navigationField -> "const/4 v${index + 5}, 0x0"
            field.type == "I" -> "const/4 v${index + 5}, $DEFAULT_QUEUE_MODE"
            else -> "iget-object v${index + 5}, v0, $field"
        }
    }.joinToString("\n")

    // The model arguments use v5-v11. With 12 locals, p0 and p1 remain 4-bit registers.
    addBridge(
        "patch_updatePanel",
        listOf("[B"),
        "Ljava/lang/Object;",
        12,
        """
            iget-object v0, p0, $modelField
            if-nez v0, :model_ready
            invoke-static {}, $modelFactory
            move-result-object v0
            invoke-virtual {v0}, $buildModel
            move-result-object v0

            :model_ready
            ${parseProtoInstructions(panelType, bytes = "p1", result = "v1", registry = "v2")}
            check-cast v1, $panelType
            new-instance v4, $modelType
            $copyArguments
            invoke-direct/range {v4 .. v11}, ${constructor.originalMethod}
            invoke-direct {p0, v4}, ${accept.originalMethod}
            iget-object v0, p0, $modelField
            if-ne v0, v4, :rejected
            const-string v1, ""
            iput-object v1, p0, $activePlaylistField
            return-object v4

            :rejected
            const/4 v0, 0x0
            return-object v0
        """
    )

    addBridge(
        "patch_restorePanel",
        List(2) { "Ljava/lang/Object;" },
        "V",
        1,
        """
            iget-object v0, p0, $modelField
            if-ne v0, p1, :done
            check-cast p2, $modelType
            const-string v0, ""
            if-eqz p2, :identity_ready
            iget-object v0, p2, $playlistField
    
            :identity_ready
            iput-object v0, p0, $activePlaylistField
            iput-object p2, p0, $modelField
            invoke-virtual/range {p0 .. p0}, ${render.originalMethod}
    
            :done
            return-void
        """
    )

    installPlaylistRowPlayback(context, modelField, panelField)

    // Preserve every native rejection before this assignment. In particular, a stale model
    // must not claim the current controller or overwrite our local insertion state.
    val acceptMethod = accept.method.cloneParameters().apply {
        // Cloning adjusts match indexes.
        val storeIndex = accept.instructionMatches.last().index + numberOfParameterRegistersLogical
        val p0Register = p0Register
        val guardRegister = findFreeRegister(
            storeIndex, p0Register, p0Register + 1
        )
        addInstruction(
            storeIndex + 1,
            "invoke-static/range {p0 .. p0}, $EXTENSION_CLASS->onControllerUpdated($CONTROLLER_INTERFACE)V"
        )

        addInstructionsAtControlFlowLabel(
            storeIndex,
            """
                invoke-static/range {p0 .. p1}, $EXTENSION_CLASS->shouldIgnorePlaylistModel($CONTROLLER_INTERFACE$PLAYLIST_MODEL_INTERFACE)Z
                move-result v$guardRegister
                if-eqz v$guardRegister, :accept_model
                return-void
       
                :accept_model
                nop
            """
        )
    }

    // Observe completed transitions, including native early returns that leave no model.
    // Keep this event distinct from a model bind: Reload can reopen a standalone
    // video without a playlist model, but it still has a native playback descriptor.
    // Do not publish between the native assignments of its video/playlist identity fields.
    // The native transition reuses p0 as a local on some exits. Preserve the
    // original receiver before adding callbacks at those returns.
    transition.method.cloneParameters().apply {
        findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { index ->
            addInstructionsAtControlFlowLabel(
                index,
                "invoke-static/range {p0 .. p0}, $EXTENSION_CLASS->onPlaybackTransition($CONTROLLER_INTERFACE)V"
            )
        }
    }
}
