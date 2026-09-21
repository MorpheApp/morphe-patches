package app.morphe.patches.youtube.video.series

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.youtube.video.information.PlayerInitFingerprint
import app.morphe.patches.youtube.video.videoid.VideoIdFingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation

/** Reuse the controller and video-model accessor identified by the shared playback patches. */
internal fun BytecodePatchContext.wirePlaybackSource() {
    val accessor = VideoIdFingerprint.instructionMatches.first().getMethodCalled()
    val original = PlayerInitFingerprint.originalClassDef
    val idGetter =
        original.methods
            .filter { method ->
                method.parameterTypes.isEmpty() &&
                    method.returnType == "Ljava/lang/String;" &&
                    !AccessFlags.STATIC.isSet(method.accessFlags) &&
                    method.implementation?.instructions?.any {
                        (it as? ReferenceInstruction)?.reference == accessor
                    } == true
            }
            .singleOrNull()
            ?: throw PatchException(
                "Series Tracker: active controller video ID is ambiguous or unavailable"
            )
    val controller = mutableClassDefBy(original.type)
    val bridge = "${OUR_PREFIX}PlaybackBridge\$Source;"
    if (bridge in controller.interfaces) return
    controller.interfaces.add(bridge)
    fun delegate(name: String, returnType: String, target: String, direct: Boolean, wide: Boolean) {
        val registers = if (wide) 3 else 2
        val method =
            ImmutableMethod(
                    controller.type,
                    name,
                    emptyList(),
                    returnType,
                    AccessFlags.PUBLIC.value,
                    emptySet(),
                    emptySet(),
                    ImmutableMethodImplementation(registers, emptyList(), emptyList(), emptyList()),
                )
                .toMutable()
        method.addInstructions(
            0,
            """
            invoke-${if (direct) "direct" else "virtual"} {p0}, ${controller.type}->$target
            move-result-${if (wide) "wide" else "object"} v0
            return-${if (wide) "wide" else "object"} v0
        """
                .trimIndent(),
        )
        controller.methods.add(method)
    }
    delegate(
        "seriesTrackerVideoId",
        "Ljava/lang/String;",
        idGetter.signature(),
        AccessFlags.PRIVATE.isSet(idGetter.accessFlags),
        false,
    )
    delegate("seriesTrackerPosition", "J", "patch_getVideoTime()J", false, true)
}

/** Observe the platform session already owned by YouTube so Resume can leave PAUSED. */
internal fun BytecodePatchContext.wirePlaybackSession() {
    var matches = 0
    MediaSessionFingerprint.matchAll(1..1).forEach { match ->
        val clazz = match.originalClassDef
        val original = match.originalMethod
        val instructions = original.implementation?.instructions?.toList().orEmpty()
        val indices =
            instructions.indices.filter { i ->
                val ref = (instructions[i] as? ReferenceInstruction)?.reference as? MethodReference
                ref?.definingClass == "Landroid/media/session/MediaSession;" &&
                    ref.signature() == "setMetadata(Landroid/media/MediaMetadata;)V"
            }
        if (indices.isNotEmpty()) {
            val method =
                mutableClassDefBy(clazz.type).methods.single {
                    it.signature() == original.signature()
                }
            indices.reversed().forEach { index ->
                val register = (instructions[index] as FiveRegisterInstruction).registerC
                method.addInstruction(
                    index + 1,
                    "invoke-static/range {v$register .. v$register}, " +
                        "${OUR_PREFIX}PlaybackSession;->attach(Landroid/media/session/MediaSession;)V",
                )
                matches++
            }
        }
    }
    if (matches != 1)
        throw PatchException(
            "Series Tracker: expected one platform media session metadata call, found $matches"
        )
}
