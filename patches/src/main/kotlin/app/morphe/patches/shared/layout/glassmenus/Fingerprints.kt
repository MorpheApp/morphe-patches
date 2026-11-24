/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.shared.layout.glassmenus

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.methodCall
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral

/**
 * The bottom sheet dialog of the Material components, which every bottom sheet of the app uses.
 */
internal object BottomSheetDialogClassFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        resourceLiteral(ResourceType.LAYOUT, "design_bottom_sheet_dialog"),
    )
)

internal object BottomSheetDialogOnAttachedToWindowFingerprint : Fingerprint(
    classFingerprint = BottomSheetDialogClassFingerprint,
    name = "onAttachedToWindow",
    returnType = "V",
    parameters = listOf(),
)

/**
 * onStart of the dialog fragment of AndroidX, which shows the dialog of every dialog fragment of the app.
 */
internal object DialogFragmentOnStartFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        methodCall(definingClass = "Landroid/app/Dialog;", name = "show"),
        methodCall(
            definingClass = "Landroid/app/Dialog;",
            name = "getWindow",
            location = MatchAfterWithin(5)
        ),
        methodCall(
            definingClass = "Landroid/view/Window;",
            name = "getDecorView",
            location = MatchAfterWithin(5)
        )
    )
)

/**
 * The bottom sheets of Morphe (playback speed, voice over translation), in the shared extension.
 */
internal object SlideDialogCreateFingerprint : Fingerprint(
    definingClass = "Lapp/morphe/extension/shared/ui/SheetBottomDialog;",
    name = "createSlideDialog",
)
