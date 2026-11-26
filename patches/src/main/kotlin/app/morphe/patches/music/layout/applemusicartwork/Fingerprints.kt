/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5082
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.layout.applemusicartwork

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.checkCast
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

private const val AUTO_CROP_IMAGE_VIEW_CLASS_TYPE =
    "Lcom/google/android/apps/youtube/music/ui/image/AutoCropImageView;"

/**
 * Constructor of the player controls overlay, which stores the player artwork view.
 */
internal object PlayerThumbnailFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    filters = listOf(
        resourceLiteral(ResourceType.ID, "player_thumbnail"),
        checkCast(AUTO_CROP_IMAGE_VIEW_CLASS_TYPE),
        fieldAccess(opcode = Opcode.IPUT_OBJECT, type = AUTO_CROP_IMAGE_VIEW_CLASS_TYPE)
    )
)
