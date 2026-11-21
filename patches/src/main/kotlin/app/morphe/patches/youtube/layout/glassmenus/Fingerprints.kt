/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.glassmenus

import app.morphe.patcher.Fingerprint
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
 * onCreateView of the sheet to save a video to a playlist, a dialog of its own.
 */
internal object AddToPlaylistCreateViewFingerprint : Fingerprint(
    returnType = "Landroid/view/View;",
    filters = listOf(
        resourceLiteral(ResourceType.LAYOUT, "playlist_add_to_fragment"),
    )
)

/**
 * onStart of the dialog fragment of AndroidX, which shows the dialog of every dialog fragment of the app.
 */
internal object DialogFragmentOnStartFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        methodCall(definingClass = "Landroid/app/Dialog;", name = "show"),
        methodCall(definingClass = "Landroid/view/Window;", name = "getDecorView"),
        resourceLiteral(ResourceType.ID, "view_tree_view_model_store_owner"),
    )
)

/**
 * The bottom sheets of Morphe (playback speed, voice over translation, …), in the shared extension.
 */
internal object SlideDialogCreateFingerprint : Fingerprint(
    definingClass = "Lapp/morphe/extension/shared/ui/SheetBottomDialog;",
    name = "createSlideDialog",
)
