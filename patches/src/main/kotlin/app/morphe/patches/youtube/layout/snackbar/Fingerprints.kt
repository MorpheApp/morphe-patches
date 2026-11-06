/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.snackbar

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * Shows a snackbar in the bottom UI container, unless the message asks to skip it.
 */
internal object SnackbarDisplayFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        methodCall(
            parameters = emptyList(),
            returnType = "Z"
        ),
        methodCall(
            definingClass = "Lcom/google/android/apps/youtube/app/common/ui/bottomui/BottomUiContainer;",
            parameters = listOf("L", "L", "L"),
            returnType = "V"
        )
    )
)

internal object LithoSnackbarLayoutFingerprint : Fingerprint(
    returnType = "Landroid/view/View;",
    parameters = listOf(
        "Landroid/view/LayoutInflater;",
        "Landroid/view/ViewGroup;",
        "Landroid/os/Bundle;"
    ),
    filters = listOf(
        fieldAccess(
            definingClass = "this",
            opcode = Opcode.IPUT_OBJECT,
            type = "Landroid/widget/FrameLayout;"
        )
    ),
    strings = listOf(
        "instance_action_bar_color",
        "instance_status_bar_color",
        "instance_activated_text_color",
        "instance_secondary_text_color"
    )
)

/**
 * Creates the view of a Litho snackbar shown in the bottom UI container.
 * Both layouts of the snackbar return the view at the same instruction.
 */
internal object LithoSnackbarFingerprint : Fingerprint(
    returnType = "Landroid/view/View;",
    parameters = listOf("L", "L"),
    filters = listOf(
        resourceLiteral(ResourceType.LAYOUT, "inset_elements_wrapper"),
        methodCall(
            definingClass = "Landroid/widget/FrameLayout;",
            name = "setOutlineProvider"
        ),
        methodCall(
            definingClass = "Landroid/widget/FrameLayout;",
            name = "setBackgroundColor",
            location = MatchAfterWithin(6)
        ),
        opcode(Opcode.RETURN_OBJECT, MatchAfterWithin(6))
    )
)

internal object QuantumSnackbarFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/libraries/quantum/snackbar/Snackbar;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf("Landroid/content/Context;", "Landroid/util/AttributeSet;"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_DIRECT,
            name = "<init>"
        )
    )
)

internal object MaterialSnackbarFingerprint : Fingerprint(
    definingClass = $$"Lcom/google/android/material/snackbar/Snackbar$SnackbarLayout;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf("Landroid/content/Context;", "Landroid/util/AttributeSet;"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_DIRECT,
            name = "<init>"
        )
    )
)

internal object AppSnackbarFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/app/common/ui/bottomui/AppSnackbar;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf("Landroid/content/Context;"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_DIRECT,
            name = "<init>"
        )
    )
)

internal object YouTubeSnackbarFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/app/common/ui/bottomui/YouTubeSnackbar;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf("Landroid/content/Context;", "Landroid/util/AttributeSet;", "I"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_DIRECT,
            name = "<init>"
        )
    )
)

/**
 * A bar with a message and a button that promotes a feature, shown in place of a snackbar.
 */
internal object MealbarFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/app/common/ui/bottomui/Mealbar;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf("Landroid/content/Context;", "Landroid/util/AttributeSet;", "I"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_DIRECT,
            name = "<init>"
        )
    )
)
