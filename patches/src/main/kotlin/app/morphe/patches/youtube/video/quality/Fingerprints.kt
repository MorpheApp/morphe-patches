package app.morphe.patches.youtube.video.quality

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import app.morphe.util.customLiteral
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object CurrentVideoFormatToStringFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    strings = listOf("currentVideoFormat="),
    custom = { method, _ ->
        method.name == "toString"
    }
)

internal object DefaultOverflowOverlayOnClickFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
    filters = listOf(
        opcode(Opcode.IF_NE),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            location = MatchAfterWithin(2)
        ),
    ),
    custom = { method, classDef ->
        classDef.type == "Lcom/google/android/libraries/youtube/player/features/overlay/overflow/ui/DefaultOverflowOverlay;"
                && method.name == "onClick"
    }
)

internal object HidePremiumVideoQualityGetArrayFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/Object;",
    parameters = listOf("I"),
    custom = { method, classDef ->
        classDef.type.startsWith("Lapp/morphe/extension/youtube/patches/playback/quality/HidePremiumVideoQualityPatch")
                && AccessFlags.SYNTHETIC.isSet(classDef.accessFlags)
                && method.name == "apply"
    }
)

internal object VideoQualityItemOnClickParentFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        string("VIDEO_QUALITIES_MENU_BOTTOM_SHEET_FRAGMENT"),
    )
)

/**
 * Resolves to class found in [VideoQualityItemOnClickFingerprint].
 */
internal object VideoQualityItemOnClickFingerprint : Fingerprint(
    name = "onItemClick",
    returnType = "V",
    parameters = listOf(
        "Landroid/widget/AdapterView;",
        "Landroid/view/View;",
        "I",
        "J"
    )
)

internal object VideoQualityMenuOptionsFingerprint : Fingerprint(
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

internal object VideoQualityMenuViewInflateFingerprint : Fingerprint(
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
