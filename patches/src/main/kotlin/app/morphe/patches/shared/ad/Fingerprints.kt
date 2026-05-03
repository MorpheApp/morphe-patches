/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.shared.ad

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.resourceLiteral
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object InterstitialsContainerFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        resourceLiteral(ResourceType.ID, "interstitials_container")
    ),
    strings = listOf("overlay_controller_param")
)

internal object LithoDialogBuilderFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("[B", "L"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            name = "show"
        ),
        resourceLiteral(ResourceType.STYLE, "SlidingDialogAnimation"),
    )
)

private object CustomDialogOnBackPressedParentFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        methodCall("Landroid/app/Dialog;->onBackPressed()V")
    ),
    custom = { _, classDef ->
        classDef.superclass == "Landroid/app/Dialog;"
    }
)

internal object CustomDialogOnBackPressedFingerprint : Fingerprint(
    classFingerprint = CustomDialogOnBackPressedParentFingerprint,
    name = "onBackPressed",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(),
)

internal object FullscreenAdsPatchFingerprint : Fingerprint(
    definingClass = EXTENSION_CLASS,
    name = "closeDialog",
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.STATIC)
)