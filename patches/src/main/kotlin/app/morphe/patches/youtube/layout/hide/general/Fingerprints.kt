/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.hide.general

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.OpcodesFilter.Companion.opcodesToFilters
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.checkCast
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.resourceLiteral
import app.morphe.patches.youtube.layout.buttons.navigation.WideSearchbarLayoutFingerprint
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
    classFingerprint = HideShowMoreButtonSetViewFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Landroid/view/View;",
    parameters = listOf()
)

internal object HideShowMoreButtonFingerprint : Fingerprint(
    classFingerprint = HideShowMoreButtonSetViewFingerprint,
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
        newInstance($$"Landroid/widget/LinearLayout$LayoutParams;", location = MatchAfterWithin(5))
    ),
    custom = { _, classDef ->
        classDef.fields.any { field ->
            field.type == "Landroid/support/v7/widget/RecyclerView;"
        }
    }
)

/**
 * 20.21
 */
internal object HideSubscribedChannelsBarConstructorLegacyFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    filters = listOf(
        resourceLiteral(ResourceType.ID, "parent_container"),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterWithin(3)),
        newInstance($$"Landroid/widget/LinearLayout$LayoutParams;", location = MatchAfterWithin(5))
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
        string("Failed to parse Element", StringComparisonType.STARTS_WITH),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            parameters = listOf("L"),
            returnType = "L"
        ),
        opcode(Opcode.RETURN_OBJECT, location = MatchAfterWithin(4))
    )
)

private object PlayerOverlayFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    filters = listOf(
        string("player_overlay_in_video_programming")
    )
)

internal object ShowWatermarkFingerprint : Fingerprint(
    classFingerprint = PlayerOverlayFingerprint,
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

internal object AlbumCardsFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    filters = listOf(
        resourceLiteral(ResourceType.LAYOUT, "album_card"),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            name = "inflate",
            returnType = "Landroid/view/View;",
            location = MatchAfterWithin(5)
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately())
    )
)

internal object CrowdfundingBoxFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    filters = listOf(
        resourceLiteral(ResourceType.LAYOUT, "donation_companion"),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            name = "inflate",
            returnType = "Landroid/view/View;",
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately())
    )
)

internal object LiveChatDonatorsBarFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    filters = listOf(
        resourceLiteral(ResourceType.LAYOUT, "live_chat_ticker_item"),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            name = "inflate",
            returnType = "Landroid/view/View;",
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately())
    )
)

internal object FilterBarHeightFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    filters = listOf(
        resourceLiteral(ResourceType.DIMEN, "filter_bar_height"),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            name = "getDimensionPixelSize",
            returnType = "I",
        ),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately())
    )
)

/**
 * 20.10+
 */
internal object RelatedChipCloudFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    filters = listOf(
        resourceLiteral(ResourceType.ID, "related_chip_cloud"),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            name = "findViewById"
        ),
        literal(45682279L),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            name = "getDimensionPixelSize",
            returnType = "I",
        ),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately())
    )
)

internal object SearchResultsChipBarFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    filters = listOf(
        resourceLiteral(ResourceType.DIMEN, "bar_container_height"),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            name = "getDimensionPixelSize",
            returnType = "I",
        ),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately())
    )
)

/**
 * 21.11+
 *
 * Resolves using the method found in [ShowFloatingMicrophoneButtonParentFingerprint]
 */
internal object ShowFloatingMicrophoneButtonFingerprint : Fingerprint(
    classFingerprint = ShowFloatingMicrophoneButtonParentFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Landroid/view/View;", "Lcom/google/android/libraries/quantum/fab/FloatingActionButton;", "Landroid/view/ViewStub;"),
    filters = listOf(
        opcode(Opcode.IGET_BOOLEAN)
    )
)

/**
 * 21.11+
 */
internal object ShowFloatingMicrophoneButtonParentFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Z"),
    strings = listOf("Current FAB View Wrapper does not support this operation. Text: "),
    custom = { _, classDef ->
        !classDef.interfaces.contains($$"Landroid/view/View$OnClickListener;")
    }
)

/**
 * ~ 21.10
 */
internal object ShowFloatingMicrophoneButtonLegacyFingerprint : Fingerprint(
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
    filters = opcodesToFilters(
        Opcode.RETURN_OBJECT,
        Opcode.CONST_STRING,
        Opcode.RETURN_OBJECT,
    ),
    strings = listOf(
        "Has attachmentRuns but drawableRequester is missing.",
    )
)

internal object SearchBoxTypingStringFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_OBJECT, type = "Ljava/util/Collection;"),
        methodCall(smali = "Ljava/util/ArrayList;-><init>(Ljava/util/Collection;)V", location = MatchAfterWithin(5)),
        fieldAccess(opcode = Opcode.IGET_OBJECT, type = "Ljava/lang/String;"),
        methodCall(smali = "Ljava/lang/String;->isEmpty()Z", location = MatchAfterWithin(5)),
        resourceLiteral(ResourceType.DIMEN, "suggestion_category_divider_height")
    )
)

private object SearchSuggestionEndpointConstructorFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    returnType = "V",
    filters = listOf(
        string("\u2026 ")
    )
)

internal object SearchSuggestionEndpointFingerprint : Fingerprint(
    classFingerprint = SearchSuggestionEndpointConstructorFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/String;"
        ),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            smali = "Landroid/text/TextUtils;->isEmpty(Ljava/lang/CharSequence;)Z"
        )
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

internal object BottomSheetMenuItemBuilderFingerprint : Fingerprint(
    returnType = "L",
    parameters = listOf("L"),
    filters = listOf(
        methodCall(opcode = Opcode.INVOKE_STATIC,
            returnType = "Ljava/lang/CharSequence;",
            parameters = listOf("L")
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        string("Text missing for BottomSheetMenuItem.")
    )
)

internal object ContextualMenuItemBuilderFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL, AccessFlags.SYNTHETIC),
    returnType = "V",
    parameters = listOf("L", "L"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            returnType = "Ljava/lang/CharSequence;",
        ),
        checkCast(
            type = "Landroid/widget/TextView;",
            location = MatchAfterWithin(3),
        ),
        methodCall(
            smali = "Landroid/widget/TextView;->setText(Ljava/lang/CharSequence;)V",
            location = MatchAfterWithin(5),
        ),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            returnType = "I",
            location = MatchAfterWithin(7),
        ),
        resourceLiteral(ResourceType.DIMEN, "poster_art_width_default"),
    )
)

internal object ContextualMenuItemBuilderOnClickFingerprint : Fingerprint(
    classFingerprint = ContextualMenuItemBuilderFingerprint,
    name = "onClick",
    parameters = listOf("Landroid/view/View;"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/Object;"
        ),
        opcode(Opcode.CHECK_CAST, location = MatchAfterImmediately()),
        opcode(Opcode.INVOKE_STATIC, location = MatchAfterImmediately()),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately())
    )
)

// 21.25+
internal object ChannelTabBuilderFingerprint : Fingerprint(
    returnType = "Landroid/view/View;",
    parameters = listOf(
        "Ljava/lang/CharSequence;",
        "Ljava/lang/CharSequence;",
        "Z",
        "L",
        "Z"
    ),
    custom = { method, _ ->
        !AccessFlags.STATIC.isSet(method.accessFlags)
    }
)

// 21.24 and older
internal object ChannelTabBuilderLegacyFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Landroid/view/View;",
    parameters = listOf(
        "Ljava/lang/CharSequence;",
        "Ljava/lang/CharSequence;",
        "Z",
        "L"
    )
)

internal object ChannelTabRendererFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(
        "L",
        "Ljava/util/List;",
        "I"
    ),
    strings = listOf(
        "TabRenderer.content contains SectionListRenderer but the tab does not have a section list controller."
    )
)

internal object ChannelTabAddFingerprint : Fingerprint(
    classFingerprint = ChannelTabRendererFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(
        "L",
        "I"
    ),
    filters = listOf(
        methodCall("Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z"),
    )
)

internal object EngagementPanelInformationButtonFingerprint : Fingerprint(
    parameters = listOf("Landroid/content/Context;"),
    filters = listOf(
        resourceLiteral(ResourceType.ID, "information_button"),
        opcode(Opcode.CHECK_CAST)
    )
)

internal object CreateSearchSuggestionsFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "I"),
    filters = listOf(
        methodCall(
            smali = "Ljava/util/Iterator;->next()Ljava/lang/Object;"
        ),
        literal(
            0,
            location = MatchAfterWithin(30)
        ),
        methodCall(
            smali = "Landroid/widget/ImageView;->setVisibility(I)V",
            location = MatchAfterWithin(10)
        ),
        literal(
            8,
            location = MatchAfterWithin(10)
        ),
        methodCall(
            smali = "Landroid/widget/ImageView;->setVisibility(I)V",
            location = MatchAfterWithin(10)
        ),
        methodCall(
            smali = "Landroid/widget/ImageView;->setImageDrawable(Landroid/graphics/drawable/Drawable;)V",
        ),
        methodCall(
            smali = "Landroid/net/Uri;->parse(Ljava/lang/String;)Landroid/net/Uri;",
        ),
        literal(
            0,
            location = MatchAfterWithin(20)
        )
    ),
    strings = listOf("ss_rds")
)

internal object ThumbnailAndEmojiPickerContainerFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Landroid/view/View;",
    parameters = listOf(),
    filters = listOf(
        resourceLiteral(ResourceType.ID, "thumbnail_and_emoji_picker_container"),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            name = "findViewById"
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately())
    )
)

internal object InlineExtraButtonsContainerFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Landroid/view/ViewGroup;",
    parameters = listOf(),
    filters = listOf(
        resourceLiteral(ResourceType.ID, "inline_extra_buttons_container"),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            name = "findViewById"
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately())
    )
)

internal object AccountListParentFingerprint : Fingerprint(
    filters = listOf(
        resourceLiteral(ResourceType.LAYOUT, "compact_list_item")
    )
)

internal object AccountListFingerprint : Fingerprint(
    classFingerprint = AccountListParentFingerprint,
    accessFlags = listOf(AccessFlags.PROTECTED, AccessFlags.FINAL, AccessFlags.SYNTHETIC),
    returnType = "V",
    filters = listOf(
        resourceLiteral(ResourceType.ATTR, "ytCallToAction")
    )
)

internal object AccountMenuParentFingerprint : Fingerprint(
    filters = listOf(
        resourceLiteral(ResourceType.LAYOUT, "account_compact_link"),
        opcode(Opcode.CONST_4, location = MatchAfterWithin(5)),
        opcode(Opcode.INVOKE_VIRTUAL, location = MatchAfterWithin(5)),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately())
    )
)

internal object AccountMenuFingerprint : Fingerprint(
    classFingerprint = AccountMenuParentFingerprint,
    returnType = "V",
    filters = opcodesToFilters(
        Opcode.INVOKE_STATIC,
        Opcode.MOVE_RESULT_OBJECT,
        Opcode.INVOKE_STATIC,
        Opcode.IGET,
        Opcode.AND_INT_LIT16
    )
)

internal object AccountMenuLegacyParentFingerprint : Fingerprint(
    filters = listOf(
        resourceLiteral(ResourceType.LAYOUT, "compact_link"),
        opcode(Opcode.CONST_4, location = MatchAfterWithin(5)),
        opcode(Opcode.INVOKE_VIRTUAL, location = MatchAfterWithin(5)),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately())
    )
)

internal object AccountMenuLegacyFingerprint : Fingerprint(
    classFingerprint = AccountMenuLegacyParentFingerprint,
    returnType = "V",
    filters = opcodesToFilters(
        Opcode.INVOKE_STATIC,
        Opcode.MOVE_RESULT_OBJECT,
        Opcode.INVOKE_STATIC,
        Opcode.IGET,
        Opcode.AND_INT_LIT16
    )
)

internal object BottomUIContainerFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/app/common/ui/bottomui/BottomUiContainer;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Landroid/view/View;", "L"),
    filters = listOf(
        methodCall(
            definingClass = "this",
            name = "removeAllViews"
        )
    )
)

internal object LithoSnackbarLayoutFingerprint : Fingerprint(
    returnType = "Landroid/view/View;",
    parameters = listOf(
        "Landroid/view/LayoutInflater;",
        "Landroid/view/ViewGroup;",
        "Landroid/os/Bundle;"
    ),
    filters = listOf(
        fieldAccess(
            definingClass = "this",
            opcode = Opcode.IPUT_OBJECT,
            type = "Landroid/widget/FrameLayout;"
        )
    ),
    strings = listOf(
        "instance_action_bar_color",
        "instance_status_bar_color",
        "instance_activated_text_color",
        "instance_secondary_text_color"
    )
)

internal object MaterialSnackbarFingerprint : Fingerprint(
    definingClass = $$"Lcom/google/android/material/snackbar/Snackbar$SnackbarLayout;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf("Landroid/content/Context;", "Landroid/util/AttributeSet;"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_DIRECT,
            name = "<init>"
        )
    )
)

internal object AppSnackbarFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/app/common/ui/bottomui/AppSnackbar;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf("Landroid/content/Context;"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_DIRECT,
            name = "<init>"
        )
    )
)

internal object YouTubeSnackbarFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/app/common/ui/bottomui/YouTubeSnackbar;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf("Landroid/content/Context;", "Landroid/util/AttributeSet;", "I"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_DIRECT,
            name = "<init>"
        )
    )
)

internal object MealbarFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/app/common/ui/bottomui/Mealbar;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf("Landroid/content/Context;", "Landroid/util/AttributeSet;", "I"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_DIRECT,
            name = "<init>"
        )
    )
)

internal object QuantumSnackbarFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/libraries/quantum/snackbar/Snackbar;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    parameters = listOf("Landroid/content/Context;", "Landroid/util/AttributeSet;"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_DIRECT,
            name = "<init>"
        )
    )
)

internal object SyncButtonFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PROTECTED, AccessFlags.FINAL),
    filters = listOf(
        resourceLiteral(ResourceType.LAYOUT, "sync_button"),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            name = "inflate",
            returnType = "Landroid/view/View;",
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately())
    )
)

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

internal object JewelsButtonContainerFingerprint : Fingerprint(
    returnType = "Landroid/view/ViewGroup;",
    parameters = listOf(),
    filters = listOf(
        resourceLiteral(ResourceType.ID, "jewels_button_container"),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            name = "findViewById"
        ),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately())
    )
)
