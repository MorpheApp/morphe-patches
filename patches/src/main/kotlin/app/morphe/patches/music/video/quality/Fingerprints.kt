/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.video.quality

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchFirst
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * Sets the qualities of the quality menu and the index of the quality playing.
 * Called when the format of the video stream changes, with the 'Auto' quality first.
 */
internal fun getVideoQualityMenuSetQualitiesFingerprint(videoQualityType: String) = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("[$videoQualityType", "I", "Z"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = "[$videoQualityType",
            location = MatchFirst()
        ),
        fieldAccess(
            opcode = Opcode.IPUT,
            definingClass = "this",
            type = "I",
            location = MatchAfterImmediately()
        ),
        fieldAccess(
            opcode = Opcode.IPUT_BOOLEAN,
            definingClass = "this",
            type = "Z",
            location = MatchAfterImmediately()
        )
    )
)

/**
 * The item click listener of the quality menu,
 * which sets the resolution of the selected quality with the listener of the menu.
 */
internal fun getVideoQualityItemOnClickFingerprint(videoQualityType: String) = Fingerprint(
    name = "onItemClick",
    returnType = "V",
    parameters = listOf(
        "Landroid/widget/AdapterView;",
        "Landroid/view/View;",
        "I",
        "J"
    ),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET,
            definingClass = videoQualityType,
            type = "I"
        ),
        methodCall(
            opcode = Opcode.INVOKE_INTERFACE,
            parameters = listOf("I"),
            returnType = "V",
            location = MatchAfterImmediately()
        )
    )
)
