/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.hide.player.flyoutmenu

import app.morphe.patcher.Fingerprint
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.resourceLiteral
import com.android.tools.smali.dexlib2.AccessFlags

internal object PlayerFlyoutQualityInflateFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    filters = listOf(
        resourceLiteral(ResourceType.LAYOUT, "video_quality_bottom_sheet_list_fragment_title")
    )
)

internal object PlayerFlyoutCaptionsInflateFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    filters = listOf(
        resourceLiteral(ResourceType.ID, "bottom_sheet_footer_text"),
        resourceLiteral(ResourceType.STRING, "subtitle_menu_settings_footer_info")
    )
)