package app.morphe.patches.youtube.video.videoid

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object VideoIdFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/google/android/libraries/youtube/innertube/model/player/PlayerResponseModel;",
            returnType = "Ljava/lang/String;"
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT),
    )
)

internal object VideoIdBackgroundPlayFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.DECLARED_SYNCHRONIZED, AccessFlags.FINAL, AccessFlags.PUBLIC),
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        methodCall(
            definingClass = "Lcom/google/android/libraries/youtube/innertube/model/player/PlayerResponseModel;",
            returnType = "Ljava/lang/String;"
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT),
        opcode(Opcode.IPUT_OBJECT),
        opcode(Opcode.MONITOR_EXIT),
        opcode(Opcode.RETURN_VOID),
        opcode(Opcode.MONITOR_EXIT),
        opcode(Opcode.RETURN_VOID)
    ),
    custom = { method, classDef ->
        method.implementation != null &&
                (classDef.methods.count() == 17 // 20.39 and lower.
                        || classDef.methods.count() == 16) // 20.40+
    }
)

internal object VideoIdParentFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "[L",
    parameters = listOf("L"),
    filters = listOf(
        literal(524288L)
    )
)
