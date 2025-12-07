package app.morphe.patches.youtube.video.quality

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.literal
import app.morphe.patcher.string
import app.morphe.util.customLiteral
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal val videoQualityItemOnClickParentFingerprint = Fingerprint(
    returnType = "V",
    filters = listOf(
        string("VIDEO_QUALITIES_MENU_BOTTOM_SHEET_FRAGMENT"),
    )
)

/**
 * Resolves to class found in [videoQualityItemOnClickFingerprint].
 */
internal val videoQualityItemOnClickFingerprint = Fingerprint(
    returnType = "V",
    parameters = listOf(
        "Landroid/widget/AdapterView;",
        "Landroid/view/View;",
        "I",
        "J"
    ),
    custom = { method, _ ->
        method.name == "onItemClick"
    }
)

internal val videoQualityMenuOptionsFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.STATIC),
    returnType = "[L",
    parameters = listOf("Landroid/content/Context", "L", "L"),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.CONST_4, // First instruction of method.
        Opcode.CONST_4,
        Opcode.IF_EQZ,
        Opcode.IGET_BOOLEAN, // Use the quality menu, that contains the advanced menu.
        Opcode.IF_NEZ,
    ),
    custom = customLiteral { videoQualityQuickMenuAdvancedMenuDescription }
)

internal val videoQualityMenuViewInflateFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf("L", "L", "L"),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.INVOKE_SUPER,
        Opcode.CONST,
        Opcode.CONST_4,
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT_OBJECT,
        Opcode.CONST,
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT_OBJECT,
        Opcode.CONST_16,
        Opcode.INVOKE_VIRTUAL,
        Opcode.CONST,
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT_OBJECT,
        Opcode.CHECK_CAST,
    ),
    custom = customLiteral { videoQualityBottomSheetListFragmentTitle }
)
