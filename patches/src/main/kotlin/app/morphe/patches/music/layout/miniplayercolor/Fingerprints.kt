package app.morphe.patches.music.layout.miniplayercolor

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.OpcodesFilter.Companion.opcodesToFilters
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.resourceLiteral
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal val miniPlayerConstructorFingerprint = Fingerprint(
    returnType = "V",
    filters = listOf(
        resourceLiteral(ResourceType.ID, "mpp_player_bottom_sheet")
    ),
    strings = listOf ("sharedToggleMenuItemMutations")
)

/**
 * Matches to the class found in [miniPlayerConstructorFingerprint].
 */
internal val switchToggleColorFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "J"),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT_OBJECT,
        Opcode.CHECK_CAST,
        Opcode.IGET
    )
)
