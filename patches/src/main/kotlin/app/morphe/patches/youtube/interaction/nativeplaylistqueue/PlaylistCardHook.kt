/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4404
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.nativeplaylistqueue

import app.morphe.patcher.patch.mutableClassDefBy
import com.android.tools.smali.dexlib2.Opcode

private const val CARD_SOURCE = "$EXTENSION_PACKAGE/PlaylistCardSource"

/** Read the mounted card's native tap command without invoking or replacing its listener. */
internal fun installPlaylistCardSource() {
    val nodeType = playlistNodeStoreFingerprint().instructionMatches.single().fieldReference.type
    val click = PlaylistClickListenerFingerprint.originalMethod
    val handler = playlistClickFieldFingerprint(click.definingClass, Opcode.IGET_OBJECT)
        .instructionMatches.single().fieldReference
    val owner = playlistClickFieldFingerprint(handler.type, Opcode.IGET_OBJECT)
        .instructionMatches.single().fieldReference
    val event = playlistClickFieldFingerprint(null, Opcode.IPUT_OBJECT)
        .instructionMatches.single().fieldReference
    val nodeClick = playlistClickMountFingerprint(nodeType, click.definingClass, handler.type)
        .instructionMatches.first().fieldReference
    val ownerType = PlaylistTapOwnerFingerprint.originalClassDef.type
    val dispatch = playlistTapActionsFingerprint(ownerType, event, handler.type).match()
    val actions = dispatch.instructionMatches[1].fieldReference
    val callback = dispatch.instructionMatches[2].methodReference
    val tap = playlistTapCommandFingerprint(
        callback.definingClass,
        callback.parameterTypes.map { it.toString() }
    ).match()
    val decode = tap.instructionMatches.single().methodReference
    val future = playlistTapFutureFingerprint(tap.originalMethod, decode.definingClass, decode)
        .instructionMatches.first().fieldReference
    check(owner.definingClass == nodeClick.type) { "Native click owner must belong to its mounted handler" }

    mutableClassDefBy(nodeType).apply {
        interfaces.add($$"$$CARD_SOURCE$TapActions;")
        addBridgeMethod(
            "patch_getPlaylistTapActions",
            emptyList(),
            "Ljava/util/List;",
            2,
            """
                iget-object v0, p0, $nodeClick
                if-eqz v0, :empty
                iget-object v0, v0, $owner
                instance-of v1, v0, $ownerType
                if-eqz v1, :empty
                check-cast v0, $ownerType
                iget-object v0, v0, $actions
                return-object v0

                :empty
                const/4 v0, 0x0
                return-object v0
            """
        )
    }
    mutableClassDefBy(future.definingClass).apply {
        interfaces.add($$"$$CARD_SOURCE$TapCommand;")
        addBridgeMethod(
            "patch_getPlaylistTapCommand",
            emptyList(),
            "Lcom/google/protobuf/MessageLite;",
            1,
            """
                iget-object v0, p0, $future
                invoke-virtual {v0}, $decode
                move-result-object v0
                return-object v0
            """
        )
    }
}
