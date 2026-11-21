/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4982
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.interaction.trackswipe

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral
import com.android.tools.smali.dexlib2.Opcode

internal const val VIEW_PAGER_CLASS = "Landroidx/viewpager2/widget/ViewPager2;"

/**
 * Constructor of the controller of the track pagers. Swiping a pager to the left or right
 * plays the next or previous track: one pager shows the artwork of the player, the other one the miniplayer.
 */
internal object TrackPagerControllerConstructorFingerprint : Fingerprint(
    name = "<init>",
    returnType = "V",
    filters = listOf(
        resourceLiteral(ResourceType.ID, "player_view_pager"),
        resourceLiteral(ResourceType.ID, "mini_player_view_pager")
    )
)

/**
 * ViewPager2.setUserInputEnabled(boolean). It is the only boolean setter of the class.
 */
internal object ViewPagerSetUserInputEnabledFingerprint : Fingerprint(
    definingClass = VIEW_PAGER_CLASS,
    returnType = "V",
    parameters = listOf("Z"),
    filters = listOf(
        fieldAccess(opcode = Opcode.IPUT_BOOLEAN, definingClass = "this", type = "Z")
    )
)
