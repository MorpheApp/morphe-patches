/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.loadvideo

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.parametersMatch
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object MiniAppOpenYtContentCommandEndpointFingerprint : Fingerprint(
    classFingerprint = Fingerprint(
        // Matches the same method but different app versions have different strings in different ordering.
        filters = listOf(
            anyInstruction(
                string("InvalidProtocolBufferException while decoding MiniAppMetadata for MiniAppOpenYTContentCommand: "),
                // 21.15+
                string("InvalidProtocolBufferException while decoding MiniAppWebToNativeParams for MiniAppOpenYTContentCommand: ")
            )
        )
    ),
    returnType = "V",
    filters = listOf(
        opcode(Opcode.CHECK_CAST),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            returnType = "V",
            parameters = listOf(),
            location = MatchAfterImmediately()
        )
    ),
    strings = listOf("no error message"),
    custom = { method, _ ->
        !AccessFlags.STATIC.isSet(method.accessFlags) &&
                parametersMatch(
                    method.parameters,
                    listOf("L")
                ) || parametersMatch(
            method.parameters,
            listOf("L", "Ljava/util/Map;") // 21.30+
        )
    }
)

internal object OpenNewVideoIntentParcelableFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf("Landroid/content/Intent;"),
    strings = listOf(
        "android.intent.extra.inventory_identifier",
        "http",
        "vnd.youtube",
        "No video id in the Uri: "
    )
)

internal object BackButtonFinishActivityOnNewVideoIntentFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L"),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.IGET_OBJECT,
        Opcode.CHECK_CAST,
        Opcode.IGET_OBJECT,
        Opcode.INVOKE_INTERFACE,
        Opcode.MOVE_RESULT_OBJECT,
        Opcode.CHECK_CAST,
        Opcode.IGET_OBJECT,
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT,
        Opcode.IPUT_BOOLEAN,
        Opcode.INVOKE_INTERFACE,
        Opcode.MOVE_RESULT_OBJECT,
        Opcode.CHECK_CAST,
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT,
        Opcode.IPUT_BOOLEAN
    ),
)

private const val ACCESSIBILITY_STATE_CHANGE_LISTENER =
    $$"Landroid/view/accessibility/AccessibilityManager$AccessibilityStateChangeListener;"

/**
 * Adds a reason that blocks playback in feeds.
 */
internal object BlockInlinePlaybackFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("I"),
    filters = listOf(
        methodCall(smali = "Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;"),
        methodCall(smali = "Ljava/util/Set;->add(Ljava/lang/Object;)Z")
    ),
    custom = { _, classDef ->
        classDef.interfaces.contains(ACCESSIBILITY_STATE_CHANGE_LISTENER)
    }
)

/**
 * Removes a reason that blocks playback in feeds.
 */
internal object UnblockInlinePlaybackFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("I"),
    filters = listOf(
        methodCall(smali = "Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;"),
        methodCall(smali = "Ljava/util/Set;->remove(Ljava/lang/Object;)Z")
    ),
    custom = { _, classDef ->
        classDef.interfaces.contains(ACCESSIBILITY_STATE_CHANGE_LISTENER)
    }
)
