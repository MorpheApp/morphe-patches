/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4404
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.nativeplaylistqueue

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.mutableClassDefBy
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val PAGING_OWNER = "$EXTENSION_PACKAGE/NativePlaylistQueuePatch\$PagingOwner;"
private const val LOADING_VIEW = "$EXTENSION_PACKAGE/NativePlaylistQueuePatch\$LoadingView;"

// LoadingFrameLayout's native states: 1 is progress and 2 is bound content.
private const val PROGRESS_STATE = 1
private const val CONTENT_STATE = 2

/** Keep the native scroll listener and its near-end predicate; observe only that accepted branch. */
internal fun installPlaylistPaging() {
    val listType = PlaylistListConstructorFingerprint.originalClassDef.type
    val controllerType = PlaylistControllerConstructorFingerprint.originalClassDef.type
    val controller = mutableClassDefBy(controllerType)
    val list = controller.fields.single { it.type == listType }
    controller.interfaces.add(PAGING_OWNER)
    controller.addBridgeMethod(
        "patch_getPlaylistList",
        emptyList(),
        "Ljava/lang/Object;",
        1,
        """
            iget-object v0, p0, $list
            return-object v0
        """
    )

    val frame = classDefBy(listType).fields.single { it.type == PLAYLIST_LOADING_FRAME }
    val state = PlaylistLoadingStateFingerprint.originalMethod
    controller.addBridgeMethod(
        "patch_getPlaylistLoadingView",
        emptyList(),
        LOADING_VIEW,
        1,
        """
            iget-object v0, p0, $list
            if-eqz v0, :done
            iget-object v0, v0, $frame

            :done
            return-object v0
        """
    )
    mutableClassDefBy(PLAYLIST_LOADING_FRAME).apply {
        interfaces.add(LOADING_VIEW)
        addBridgeMethod(
            "patch_setPlaylistLoading",
            listOf("Z"),
            "V",
            1,
            """
                const/4 v0, $PROGRESS_STATE
                if-nez p1, :set
                const/4 v0, $CONTENT_STATE

                :set
                invoke-virtual {p0, v0}, $state
                return-void
            """
        )
    }

    val scroll = playlistNativeScrollFingerprint(listType).match()
    val read = scroll.instructionMatches.last()
    val receiver = read.getInstruction<TwoRegisterInstruction>().registerA
    scroll.method.addInstruction(
        read.index + 1,
        "invoke-static/range {v$receiver .. v$receiver}, " +
                "$EXTENSION_CLASS->onNativePlaylistScroll(Ljava/lang/Object;)V"
    )
}
