/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.interaction.shuffleloop

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/**
 * If the method only returns the constant [value].
 * The media session actions return their action name from a trivial getter.
 */
private fun Method.returnsConstantString(value: String): Boolean {
    val instructions = implementation?.instructions ?: return false
    if (instructions.count() != 2) return false

    val stringInstruction = instructions.first()
    if (stringInstruction.opcode != Opcode.CONST_STRING &&
        stringInstruction.opcode != Opcode.CONST_STRING_JUMBO
    ) {
        return false
    }

    val string = (stringInstruction as ReferenceInstruction).reference as? StringReference
    return string?.string == value && instructions.last().opcode == Opcode.RETURN_OBJECT
}

/**
 * If the method reads the action name and the showed state of the action it belongs to.
 * The media session actions all tell if they are shown as a media control button this way.
 */
private fun Method.readsActionFields(classType: String): Boolean {
    val instructions = implementation?.instructions ?: return false

    var readsName = false
    var readsShownState = false

    instructions.forEach { instruction ->
        val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
            ?: return@forEach
        if (field.definingClass != classType) return@forEach

        when (field.type) {
            "Ljava/lang/String;" -> readsName = true
            "Z" -> readsShownState = true
        }
    }

    return readsName && readsShownState
}

/**
 * The media session action that reports its name as `shuffle_action`.
 * The system UI builds the shuffle button of the media controls from it.
 */
internal object ShuffleActionNameFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    strings = listOf("shuffle_action"),
    custom = { method, _ -> method.returnsConstantString("shuffle_action") }
)

/**
 * Tells if the shuffle action is shown as a media control button.
 */
internal object ShuffleActionShownFingerprint : Fingerprint(
    classFingerprint = ShuffleActionNameFingerprint,
    returnType = "Z",
    parameters = listOf(),
    custom = { method, classDef -> method.readsActionFields(classDef.type) }
)

/**
 * The media session action that reports its name as `loop_mode_action`,
 * which is the repeat action of the player.
 */
internal object LoopActionNameFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    strings = listOf("loop_mode_action"),
    custom = { method, _ -> method.returnsConstantString("loop_mode_action") }
)

/**
 * Tells if the loop action is shown as a media control button.
 */
internal object LoopActionShownFingerprint : Fingerprint(
    classFingerprint = LoopActionNameFingerprint,
    returnType = "Z",
    parameters = listOf(),
    custom = { method, classDef -> method.readsActionFields(classDef.type) }
)
