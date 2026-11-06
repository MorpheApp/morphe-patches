/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4175
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.pictureinpicture

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall

/**
 * Sets the picture-in-picture actions. All app code that updates the actions calls this.
 */
internal object SetPipActionsFingerprint : Fingerprint(
    filters = listOf(
        methodCall(
            smali = "Landroid/app/PictureInPictureParams\$Builder;->setActions(Ljava/util/List;)Landroid/app/PictureInPictureParams\$Builder;"
        )
    )
)
