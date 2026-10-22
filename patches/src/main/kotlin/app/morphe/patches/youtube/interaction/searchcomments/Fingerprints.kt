/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3942
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.searchcomments

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * Sets up the header of an engagement panel, such as the comments panel.
 */
internal object PanelSubheaderFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "V",
    filters = listOf(
        resourceLiteral(ResourceType.ID, "panel_header"),
        resourceLiteral(ResourceType.ID, "close_button"),
        resourceLiteral(ResourceType.ID, "panel_subheader"),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            name = "removeAllViews"
        )
    )
)
