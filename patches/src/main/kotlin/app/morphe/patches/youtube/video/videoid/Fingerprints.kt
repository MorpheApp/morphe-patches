/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.video.videoid

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

private object VideoIdParentFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "[L",
    parameters = listOf("L"),
    filters = listOf(
        literal(524288L)
    )
)

internal object VideoIdFingerprint : Fingerprint(
    classFingerprint = VideoIdParentFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        methodCall(opcode = Opcode.INVOKE_INTERFACE, returnType = "Ljava/lang/String;"),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()), // videoId
        anyInstruction(
            methodCall( // 21.35 and older.
                smali = "Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
            ),
            methodCall( // 21.36+
                opcode = Opcode.INVOKE_VIRTUAL,
                returnType = "V",
                parameters = listOf("Ljava/lang/String;", "L")
            ),
            location = MatchAfterWithin(6)
        ),
        opcode(Opcode.RETURN_VOID, location = MatchAfterImmediately())
    )
)

internal object VideoIdBackgroundPlayFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.DECLARED_SYNCHRONIZED, AccessFlags.FINAL, AccessFlags.PUBLIC),
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        methodCall(opcode = Opcode.INVOKE_INTERFACE, returnType = "Ljava/lang/String;"),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            type = "Ljava/lang/String;",
            location = MatchAfterImmediately()
        ),
        opcode(Opcode.MONITOR_EXIT),
        opcode(Opcode.RETURN_VOID, location = MatchAfterImmediately())
    ),
    custom = { method, classDef ->
        method.implementation != null &&
                (classDef.methods.count() == 17 // 20.39 and lower.
                        || classDef.methods.count() == 16) // 20.40+
    }
)

/**
 * The media session sets the title of the video of a video stage event, also when the next video
 * of a playlist plays in the background, when the other video id hooks are not called.
 * The media session has two implementations since 20.51, which the app chooses between.
 *
 * @param videoStageEventType The type of the video stage event.
 */
internal fun mediaSessionVideoStageFingerprint(videoStageEventType: String) = Fingerprint(
    returnType = "V",
    parameters = listOf(videoStageEventType),
    filters = listOf(
        // Title of the video.
        methodCall(opcode = Opcode.INVOKE_INTERFACE, returnType = "Ljava/lang/String;"),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        anyInstruction(
            // Title and artist.
            methodCall(
                returnType = "V",
                parameters = listOf("Ljava/lang/CharSequence;", "Ljava/lang/CharSequence;")
            ),
            // Title only.
            methodCall(
                returnType = "I",
                parameters = listOf("Ljava/lang/CharSequence;")
            ),
            location = MatchAfterImmediately()
        )
    )
)

/**
 * Same method as [mediaSessionVideoStageFingerprint], matched again with its title call,
 * as other patches can change the method or replace it with a copy.
 */
internal fun mediaSessionVideoTitleFingerprint(
    definingClass: String,
    name: String,
    videoStageEventType: String,
    titleMethod: String
) = Fingerprint(
    definingClass = definingClass,
    name = name,
    returnType = "V",
    parameters = listOf(videoStageEventType),
    filters = listOf(
        methodCall(smali = titleMethod)
    )
)
