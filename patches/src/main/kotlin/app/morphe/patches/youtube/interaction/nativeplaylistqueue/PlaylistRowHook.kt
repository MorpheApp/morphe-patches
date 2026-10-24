/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.nativeplaylistqueue

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.matchSingle
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patches.youtube.layout.originaltitles.PlaylistPanelVideoBindFingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField

/** Observe native row commands without replacing their listener or routing. */
internal fun installPlaylistRowPlayback(
    context: BytecodePatchContext,
    modelField: FieldReference,
    panelField: FieldReference
) = with(context) {
    // Original titles also modifies these binders. Inspect fresh matches,
    // including all native layouts, and require their shared video model.
    PlaylistPanelVideoBindFingerprint.clearMatch()
    val videoIdFields = PlaylistPanelVideoBindFingerprint.matchAll().map {
        it.instructionMatches[2].instruction.getReference<FieldReference>()!!
    }.distinct().toList()
    check(videoIdFields.size == 1) {
        "Native playlist queue: row binders disagree on the native video ID field: $videoIdFields"
    }

    val videoIdField = videoIdFields.single()
    val videoType = videoIdField.definingClass

    val rowMenu = playlistRowMenuOpenFingerprint(videoType).matchSingle()

    installPlaylistMenuDispatch(context, rowMenu.originalMethod.parameterTypes[1].toString())

    val contextCreation = rowMenu.instructionMatches.last()
    val contextRegister = contextCreation.getInstruction<OneRegisterInstruction>().registerA
    rowMenu.method.addInstruction(
        contextCreation.index + 1,
        "invoke-static {v$contextRegister}, $EXTENSION_CLASS->onRowMenuOpened(Ljava/util/Map;)V"
    )

    installPlaylistReordering(context, videoType)

    // The native veto predicate runs before logging decorates the bound endpoint.
    // Observe its original identity after the veto, never the decorated copy.
    val click = PlaylistRowClickFingerprint.matchSingle()
    val listenerType = click.originalClassDef.type
    val endpointRead = click.instructionMatches[4]
    val listenerEndpoint = endpointRead.instruction.getReference<FieldReference>()!!
    val endpointType = listenerEndpoint.type
    val rowEndpoint = playlistRowEndpointFingerprint(listenerType, videoType, endpointType).matchSingle()
    val rowListener = rowEndpoint.instructionMatches.first().instruction.getReference<FieldReference>()!!
    val videoEndpoint = rowEndpoint.instructionMatches.last().instruction.getReference<FieldReference>()!!

    val factory = playlistItemFactoryFingerprint(videoType).matchSingle()
    val itemVideo = factory.instructionMatches.single().instruction.getReference<FieldReference>()!!
    val contents = playlistContentsFingerprint(panelField.type, factory.originalMethod).matchSingle()
        .instructionMatches.first().instruction.getReference<FieldReference>()!!

    val controller = mutableClassDefBy(modelField.definingClass)
    controller.addBridgeMethod("patch_findQueueRow", listOf("Ljava/lang/Object;"), "I", 6, """
        iget-object v0, p0, $modelField
        if-eqz v0, :missing
        iget-object v0, v0, $panelField
        if-eqz v0, :missing
        iget-object v0, v0, $contents
        invoke-interface {v0}, Ljava/util/List;->iterator()Ljava/util/Iterator;
        move-result-object v0
        const/4 v1, 0x0

        :next
        invoke-interface {v0}, Ljava/util/Iterator;->hasNext()Z
        move-result v2
        if-eqz v2, :missing
        invoke-interface {v0}, Ljava/util/Iterator;->next()Ljava/lang/Object;
        move-result-object v2
        check-cast v2, ${itemVideo.definingClass}
        iget-object v2, v2, $itemVideo
        if-eqz v2, :skip
        if-eq v2, p1, :found
        iget-object v2, v2, $videoEndpoint
        if-ne v2, p1, :skip

        :found
        return v1

        :skip
        add-int/lit8 v1, v1, 0x1
        goto :next

        :missing
        const/4 v0, -0x1
        return v0
    """)

    installPlaylistNavigation(context, controller.type)

    // A nonserialized bit belongs to the exact native object, survives a stale
    // view callback, and disappears with that object. No native model is retained
    // in an extension cache, and protobuf equality/serialization stay unchanged.
    val video = mutableClassDefBy(videoType)
    video.interfaces.add("Lapp/morphe/extension/youtube/patches/utils/FlyoutUtils\$FlyoutMenuVideoIdInterface;")
    video.addBridgeMethod("patch_getVideoId", emptyList(), "Ljava/lang/String;", 1, """
        iget-object v0, p0, $videoIdField
        return-object v0
    """)

    val ownedField = ImmutableField(
        videoType, "patch_queueOwned", "Z", AccessFlags.PUBLIC.value, null, null, null
    ).toMutable()
    video.fields.add(ownedField)
    video.interfaces.add(QUEUE_ROW_INTERFACE)
    video.addBridgeMethod("patch_isQueueOwned", emptyList(), "Z", 1, """
        iget-boolean v0, p0, $ownedField
        return v0
    """)

    // Retain ownership on old header models too, so a stale Clear action is consumed
    // instead of falling through to YouTube's ordinary Hide command.
    val panel = mutableClassDefBy(panelField.type)
    val panelOwned = ImmutableField(
        panel.type, "patch_queueOwned", "Z", AccessFlags.PUBLIC.value, null, null, null
    ).toMutable()
    panel.fields.add(panelOwned)
    panel.interfaces.add(QUEUE_PANEL_INTERFACE)
    panel.addBridgeMethod("patch_isQueueOwned", emptyList(), "Z", 1, """
        iget-boolean v0, p0, $panelOwned
        return v0
    """)

    controller.addBridgeMethod("patch_markQueueRows", emptyList(), "V", 4, """
        iget-object v0, p0, $modelField
        if-eqz v0, :done
        iget-object v0, v0, $panelField
        if-eqz v0, :done
        const/4 v1, 0x1
        iput-boolean v1, v0, $panelOwned
        iget-object v0, v0, $contents
        invoke-interface {v0}, Ljava/util/List;->iterator()Ljava/util/Iterator;
        move-result-object v0

        :next
        invoke-interface {v0}, Ljava/util/Iterator;->hasNext()Z
        move-result v3
        if-eqz v3, :done
        invoke-interface {v0}, Ljava/util/Iterator;->next()Ljava/lang/Object;
        move-result-object v2
        check-cast v2, ${itemVideo.definingClass}
        iget-object v2, v2, $itemVideo
        if-eqz v2, :next
        iput-boolean v1, v2, $ownedField
        goto :next

        :done
        return-void
    """)

    // Logging can replace the listener's endpoint after a click. Retain the
    // bound identity across a rejected/repeated click, and clear it on unbind.
    val listener = mutableClassDefBy(listenerType)
    val boundEndpoint = ImmutableField(
        listenerType, "patch_queueEndpoint", "Ljava/lang/Object;", AccessFlags.PRIVATE.value, null, null, null
    ).toMutable()
    listener.fields.add(boundEndpoint)
    listener.interfaces.add(ROW_CLICK_INTERFACE)
    listener.addBridgeMethod("patch_getQueueEndpoint", emptyList(), "Ljava/lang/Object;", 1, """
        iget-object v0, p0, $boundEndpoint
        return-object v0
    """)

    listOf(
        playlistEndpointBindFingerprint(listenerEndpoint),
        playlistEndpointUnbindFingerprint(listenerEndpoint)
    ).forEach { fingerprint ->
        val match = fingerprint.matchSingle()
        val store = match.instructionMatches.single()
        val registers = store.getInstruction<TwoRegisterInstruction>()
        match.method.addInstruction(
            store.index + 1,
            "iput-object v${registers.registerA}, v${registers.registerB}, $boundEndpoint"
        )
    }

    val listenerRegister = endpointRead.getInstruction<TwoRegisterInstruction>().registerB
    click.method.addInstruction(
        endpointRead.index + 1,
        "invoke-static/range {v$listenerRegister .. v$listenerRegister}, " +
                "$EXTENSION_CLASS->onRowClicked($ROW_CLICK_INTERFACE)V"
    )

    installPlaylistSwipeBackground(context, rowListener)
}
