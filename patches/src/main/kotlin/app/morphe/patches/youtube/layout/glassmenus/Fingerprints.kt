/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.glassmenus

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.methodCall
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral

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
 * The touch feedback drawable of the app, which wraps the background of a Litho component, such as a row of a menu.
 * The first instruction match is the constructor of the superclass.
 */
internal object TouchFeedbackDrawableConstructorFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/libraries/youtube/rendering/ui/spec/motion/TouchFeedbackDrawable;",
    name = "<init>",
    parameters = listOf("Landroid/graphics/drawable/Drawable;"),
    filters = listOf(
        methodCall(definingClass = "Landroid/graphics/drawable/Drawable;", name = "<init>"),
        methodCall(
            smali = $$"Landroid/graphics/drawable/Drawable;->setCallback(Landroid/graphics/drawable/Drawable$Callback;)V",
            location = MatchAfterWithin(10)
        )
    )
)

/**
 * Shows the "Please wait" progress dialog before the download quality menu. Matches 2 methods: the download button of
 * a video and the download option of a menu.
 */
internal object DownloadProgressDialogFingerprint : Fingerprint(
    filters = listOf(
        resourceLiteral(ResourceType.STRING, "offline_stream_selection_waiting"),
        methodCall(smali = "Landroid/app/ProgressDialog;->show()V"),
    )
)
