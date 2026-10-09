package app.morphe.patches.youtube.video.series

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.youtube.video.information.PlayerInitFingerprint
import app.morphe.patches.youtube.video.videoid.VideoIdFingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

/** Reuse the controller and video-model accessor identified by the shared playback patches. */
internal fun BytecodePatchContext.wirePlaybackSource() {
    val accessor = VideoIdFingerprint.instructionMatches.first().getMethodCalled()
    val idGetter = ControllerVideoIdFingerprint(accessor)
        .match(PlayerInitFingerprint.originalClassDef)
        .originalMethod
    val controller = PlayerInitFingerprint.classDef
    controller.interfaces.add("${OUR_PREFIX}PlaybackBridge\$Source;")

    fun addGetter(name: String, returnType: String, target: String, wide: Boolean) {
        controller.methods.add(
            ImmutableMethod(
                controller.type,
                name,
                null,
                returnType,
                AccessFlags.PUBLIC.value,
                null,
                null,
                MutableMethodImplementation(if (wide) 3 else 2),
            ).toMutable().apply {
                val suffix = if (wide) "wide" else "object"
                addInstructions(
                    0,
                    """
                        $target
                        move-result-$suffix v0
                        return-$suffix v0
                    """
                )
            }
        )
    }

    val invoke = if (AccessFlags.PRIVATE.isSet(idGetter.accessFlags)) "invoke-direct" else "invoke-virtual"
    addGetter(
        "patch_seriesTrackerVideoId",
        "Ljava/lang/String;",
        "$invoke { p0 }, ${controller.type}->${idGetter.signature()}",
        false
    )
    addGetter(
        "patch_seriesTrackerPosition",
        "J",
        "invoke-virtual { p0 }, ${controller.type}->patch_getVideoTime()J",
        true
    )
}

/** Observe the platform session already owned by YouTube so Resume can leave PAUSED. */
internal fun BytecodePatchContext.wirePlaybackSession() {
    MediaSessionFingerprint.let {
        it.method.apply {
            val index = it.instructionMatches.first().index
            val register = getInstruction<FiveRegisterInstruction>(index).registerC

            addInstruction(
                index + 1,
                "invoke-static/range { v$register .. v$register }, " +
                        "${OUR_PREFIX}PlaybackSession;->attach(Landroid/media/session/MediaSession;)V"
            )
        }
    }
}
