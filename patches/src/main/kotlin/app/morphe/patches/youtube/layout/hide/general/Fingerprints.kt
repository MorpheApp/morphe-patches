package app.morphe.patches.youtube.layout.hide.general

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.checkCast
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.resourceLiteral
import app.morphe.patches.youtube.layout.buttons.navigation.WideSearchbarLayoutFingerprint
import app.morphe.util.customLiteral
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object HideShowMoreButtonSetViewFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        resourceLiteral(ResourceType.ID, "link_text_start"),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = "Landroid/widget/TextView;"
        ),
        resourceLiteral(ResourceType.ID, "expand_button_container"),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = "Landroid/view/View;"
        )
    )
)

internal object HideShowMoreButtonGetParentViewFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Landroid/view/View;",
    parameters = listOf()
)

internal object HideShowMoreButtonFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L", "Ljava/lang/Object;"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            smali = "Landroid/view/View;->setContentDescription(Ljava/lang/CharSequence;)V"
        )
    )
)

/**
 * 20.21+
 */
internal object HideSubscribedChannelsBarConstructorFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    filters = listOf(
        resourceLiteral(ResourceType.ID, "parent_container"),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterWithin(3)),
        newInstance("Landroid/widget/LinearLayout\$LayoutParams;", location = MatchAfterWithin(5))
    ),
    custom = { _, classDef ->
        classDef.fields.any { field ->
            field.type == "Landroid/support/v7/widget/RecyclerView;"
        }
    }
)

/**
 * ~ 20.21
 */
internal object HideSubscribedChannelsBarConstructorLegacyFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    filters = listOf(
        resourceLiteral(ResourceType.ID, "parent_container"),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterWithin(3)),
        newInstance("Landroid/widget/LinearLayout\$LayoutParams;", location = MatchAfterWithin(5))
    )
)

internal object HideSubscribedChannelsBarLandscapeFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        resourceLiteral(ResourceType.DIMEN, "parent_view_width_in_wide_mode"),
        methodCall(opcode = Opcode.INVOKE_VIRTUAL, name = "getDimensionPixelSize"),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
    )
)

internal object ParseElementFromBufferFingerprint : Fingerprint(
    parameters = listOf("L", "L", "[B", "L", "L"),
    filters = listOf(
        opcode(Opcode.IGET_OBJECT),
        // IGET_BOOLEAN // 20.07+
        opcode(Opcode.INVOKE_INTERFACE, location = MatchAfterWithin(1)),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),

        string("Failed to parse Element", StringComparisonType.STARTS_WITH)
    )
)

internal object PlayerOverlayFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    filters = listOf(
        string("player_overlay_in_video_programming")
    )
)

internal object ShowWatermarkFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "L")
)

/**
 * Matches same method as [WideSearchbarLayoutFingerprint].
 */
internal object YouTubeDoodlesImageViewFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Landroid/view/View;",
    parameters = listOf("L", "L"),
    filters = listOf(
        resourceLiteral(ResourceType.ID, "youtube_logo")
    )
)

internal object CrowdfundingBoxFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT_OBJECT,
        Opcode.IPUT_OBJECT,
    ),
    custom = customLiteral { crowdfundingBoxId } // TODO: Convert this to an instruction filter
)

internal object AlbumCardsFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.MOVE_RESULT_OBJECT,
        Opcode.CONST,
        Opcode.CONST_4,
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT_OBJECT,
        Opcode.CHECK_CAST,
    ),
    custom = customLiteral { albumCardId } // TODO: Convert this to an instruction filter
)

internal object FilterBarHeightFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.CONST,
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT,
        Opcode.IPUT,
    ),
    custom = customLiteral { filterBarHeightId } // TODO: Convert this to an instruction filter
)

internal object RelatedChipCloudFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.CONST,
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT_OBJECT,
    ),
    custom = customLiteral { relatedChipCloudMarginId } // TODO: Convert this to an instruction filter
)

internal object SearchResultsChipBarFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.CONST,
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT,
        Opcode.INVOKE_VIRTUAL,
        Opcode.MOVE_RESULT_OBJECT,
    ),
    custom = customLiteral { barContainerHeightId } // TODO: Convert this to an instruction filter
)

internal object ShowFloatingMicrophoneButtonFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        resourceLiteral(ResourceType.ID, "fab"),
        checkCast("/FloatingActionButton;", location = MatchAfterWithin(10)),
        opcode(Opcode.IGET_BOOLEAN, location = MatchAfterWithin(15))
    )
)

internal object HideViewCountFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Ljava/lang/CharSequence;",

    filters = OpcodesFilter.opcodesToFilters(
        Opcode.RETURN_OBJECT,
        Opcode.CONST_STRING,
        Opcode.RETURN_OBJECT,
    ),
    strings = listOf(
        "Has attachmentRuns but drawableRequester is missing.",
    )
)

internal object SearchBoxTypingMethodFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        resourceLiteral(ResourceType.DIMEN, "suggestion_category_divider_height")
    )
)

internal object SearchBoxTypingStringFingerprint : Fingerprint(
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_OBJECT, type = "Ljava/lang/String;"),
        methodCall(smali = "Ljava/lang/String;->isEmpty()Z", location = MatchAfterWithin(5)),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately()),
        opcode(Opcode.IF_NEZ, location = MatchAfterImmediately())
    )
)

internal object LatestVideosContentPillFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "Z"),
    filters = listOf(
        resourceLiteral(ResourceType.LAYOUT, "content_pill"),
        methodCall(
            smali = "Landroid/view/LayoutInflater;->inflate(ILandroid/view/ViewGroup;Z)Landroid/view/View;"
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately())
    )
)

internal object LatestVideosBarFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "Z"),
    filters = listOf(
        resourceLiteral(ResourceType.LAYOUT, "bar"),
        methodCall(
            smali = "Landroid/view/LayoutInflater;->inflate(ILandroid/view/ViewGroup;Z)Landroid/view/View;"
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately())
    )
)


