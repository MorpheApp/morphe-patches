/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4869
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.nsfw

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.InstructionLocation.MatchBeforeWithin
import app.morphe.patcher.checkCast
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.opcode
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

internal const val ACCOUNT_PREFERENCES_CLASS = "Lcom/reddit/domain/model/AccountPreferences;"
internal const val NSFW_BOTTOM_SHEET_CLASS = "Lcom/reddit/agegating/impl/nsfw/composables/NsfwBottomSheet;"
internal const val POST_DETAIL_VIEW_MODEL_CLASS = "Lcom/reddit/postdetail/refactor/RedditPostDetailViewModel;"
internal const val PROFILE_DETAILS_VIEW_MODEL_CLASS = "Lcom/reddit/profile/viewmodel/ProfileDetailsViewModel;"

/**
 * Finds a method that uses a string resource.
 */
internal fun stringResourceFingerprint(name: String, returnType: String? = null) = Fingerprint(
    returnType = returnType,
    filters = listOf(
        resourceLiteral(ResourceType.STRING, name)
    )
)

/**
 * Finds the field of a type that a constructor saves.
 */
internal fun constructorFieldFingerprint(definingClass: String, fieldType: String) = Fingerprint(
    definingClass = definingClass,
    name = "<init>",
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = fieldType
        )
    )
)

// region Shared.

/**
 * Preference repository getter of the native NSFW viewing setting.
 * Also reads the saved setting of anonymous browsing, unlike the account model.
 */
internal object NsfwViewingPreferenceFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        string("nsfw_over18_enabled"),
        methodCall(
            definingClass = ACCOUNT_PREFERENCES_CLASS,
            name = "getOver18"
        )
    )
)

/** Reads the effective blur preference for the current account or private session. */
internal object NsfwBlurPreferenceFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        string("nsfw_blur_enabled"),
        methodCall(definingClass = ACCOUNT_PREFERENCES_CLASS, name = "getNoProfanity")
    )
)

/**
 * Opens the home screen when declining the NSFW warning.
 */
internal object NsfwHomeNavigationFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = "Lkotlin/jvm/functions/Function0;"
        ),
        methodCall(smali = "Lkotlin/jvm/functions/Function0;->invoke()Ljava/lang/Object;"),
        newInstance("Landroid/content/Intent;"),
        string("com.reddit.frontpage.main_toast_message")
    )
)

/**
 * Static method that replaces the current screen with another screen.
 */
internal object ReplaceScreenFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/content/Context;", "Lcom/reddit/screen/BaseScreen;"),
    filters = listOf(
        methodCall(
            returnType = "V",
            parameters = listOf("Lcom/reddit/screen/BaseScreen;", "Lcom/reddit/screen/BaseScreen;")
        )
    )
)

// endregion

// region Fix NSFW warning: Prevent NSFW content flashing.

internal object PostLoadedNsfwIncognitoVerifyEventHandlerConstructorFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/postdetail/refactor/events/handlers/PostLoadedNsfwIncognitoVerifyEventHandler;",
    name = "<init>",
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            name = "preferenceRepository"
        )
    )
)

internal object PostDetailPostStateToStringFingerprint : Fingerprint(
    name = "toString",
    parameters = listOf(),
    returnType = "Ljava/lang/String;",
    filters = listOf(
        string("PostDetailPostState(link="),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Lcom/reddit/domain/model/Link;",
            location = MatchAfterWithin(5)
        )
    )
)

internal object PostDetailStateProducerFingerprint : Fingerprint(
    filters = listOf(
        string("PostDetailStateProducer::updatePostViewState"),
        methodCall(returnType = "Lcom/reddit/presentation/listing/model/LinkPresentationModel;"),
        // Field of the link presentation model mapper.
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            location = MatchBeforeWithin(6)
        )
    )
)

/**
 * Creates the post view state. If the post has no link yet, the empty post view state is returned early.
 */
internal object PostUnitViewStateFingerprint : Fingerprint(
    definingClass = POST_DETAIL_VIEW_MODEL_CLASS,
    filters = listOf(
        // The link is also read earlier, so match the read after this effect.
        newInstance($$"Lcom/reddit/postdetail/refactor/RedditPostDetailViewModel$viewState$2$1;"),
        fieldAccess(
            reference = { PostDetailPostStateToStringFingerprint.instructionMatches[1].getFieldAccessed() },
            opcode = Opcode.IGET_OBJECT
        ),
        methodCall(
            definingClass = $$"Lcom/reddit/postdetail/refactor/RedditPostDetailViewModel$viewState$postUnitViewState$1$1;",
            name = "<init>"
        )
    )
)

internal object ProfileToStringFingerprint : Fingerprint(
    name = "toString",
    parameters = listOf(),
    returnType = "Ljava/lang/String;",
    filters = listOf(
        string(", isProfileNsfw="),
        fieldAccess(
            opcode = Opcode.IGET_BOOLEAN,
            definingClass = "this",
            location = MatchAfterWithin(6)
        )
    )
)

/**
 * Publishes the loaded profile to the profile header and feed.
 */
internal object PublishProfileDetailsFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/profile/viewmodel/",
    filters = listOf(
        // The rest of the class name is different with 2026.46.0 and newer.
        newInstance($$"Lcom/reddit/profile/viewmodel/ProfileDetailsViewModel$loadProfileDetails$1$"),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = PROFILE_DETAILS_VIEW_MODEL_CLASS
        ),
        methodCall(
            returnType = "V",
            parameters = listOf("Ljava/lang/Object;"),
            location = MatchAfterImmediately()
        ),
        fieldAccess(
            opcode = Opcode.SGET_OBJECT,
            definingClass = "Lkotlin/Unit;"
        )
    )
)

internal object ReplaceScreenExtensionFingerprint : Fingerprint(
    definingClass = PREVENT_NSFW_FLASHING_EXTENSION_CLASS,
    name = "replaceScreen"
)

// endregion

// region Fix NSFW warning: Navigation.

internal object ShowNsfwBottomSheetFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        newInstance(NSFW_BOTTOM_SHEET_CLASS),
        methodCall(
            returnType = "V",
            parameters = listOf("Landroid/content/Context;", "Lcom/reddit/screen/BaseScreen;", "L")
        )
    )
)

internal object NsfwBottomSheetViewModelConstructorFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/agegating/impl/nsfw/NsfwBottomSheetViewModel;",
    name = "<init>",
    filters = listOf(
        // Navigate back callback.
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            type = "Lkotlin/jvm/functions/Function0;"
        ),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            type = "Ljava/lang/String;"
        ),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            type = "Lcom/reddit/session/mode/common/SessionMode;"
        )
    )
)

internal object NsfwBottomSheetHandleEventFingerprint : Fingerprint(
    definingClass = $$"Lcom/reddit/agegating/impl/nsfw/NsfwBottomSheetViewModel$1;",
    name = $$"access$invokeSuspend$handleEvent",
    returnType = "Ljava/lang/Object;",
    filters = listOf(
        methodCall(
            returnType = "Z",
            parameters = listOf("Lcom/reddit/session/mode/common/SessionMode;")
        ),
        methodCall(reference = { NsfwHomeNavigationFingerprint.method }),
        fieldAccess(
            opcode = Opcode.SGET_OBJECT,
            definingClass = "Lkotlin/Unit;"
        )
    )
)

internal object NsfwBottomSheetBackHandlerFingerprint : Fingerprint(
    definingClass = NSFW_BOTTOM_SHEET_CLASS,
    filters = listOf(
        methodCall(
            returnType = "V",
            parameters = listOf("I", "I", "L", "Lkotlin/jvm/functions/Function0;", "Z")
        )
    )
)

// endregion

// region Permanently disable NSFW content.

internal object SearchNsfwHeaderFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/search/combined/ui/composables/",
    returnType = "V",
    filters = listOf(
        resourceLiteral(ResourceType.STRING, "click_label_search_show_nsfw"),
        resourceLiteral(ResourceType.STRING, "click_label_search_hide_nsfw")
    )
)

internal object FilterValuesConstructorFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/search/domain/model/FilterValues;",
    name = "<init>",
    returnType = "V",
    parameters = listOf(
        "Lcom/reddit/domain/model/search/Query;",
        "Lcom/reddit/search/domain/model/SearchSortType;",
        "Lcom/reddit/search/domain/model/SearchSortTimeFrame;",
        "Z", // Safe search.
        "Ljava/util/List;",
        "Ljava/util/List;",
        "Ljava/lang/String;",
        "Ljava/util/Map;",
        "Ljava/util/List;"
    )
)

internal object LeaveIncognitoModeScreenCreateViewFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/incognito/screens/leave/LeaveIncognitoModeScreen;",
    returnType = "Landroid/view/View;",
    parameters = listOf("Landroid/view/LayoutInflater;", "Landroid/view/ViewGroup;")
)

internal fun feedImageClickFingerprint(eventHandler: String) = Fingerprint(
    definingClass = "Lcom/reddit/feeds/impl/ui/actions/$eventHandler\$handleEvent\$",
    name = "invokeSuspend",
    returnType = "Ljava/lang/Object;",
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            name = "\$link",
            type = "Lcom/reddit/domain/model/Link;"
        ),
        fieldAccess(opcode = Opcode.SGET_OBJECT, type = "Lkotlin/Unit;")
    )
)

internal fun accountPreferencesFingerprint(name: String, returnType: String) = Fingerprint(
    definingClass = ACCOUNT_PREFERENCES_CLASS,
    name = name,
    returnType = returnType,
    parameters = listOf()
)

internal object SafeSearchGetValueFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/domain/SafeSearch;",
    name = "getValue",
    returnType = "Ljava/lang/String;",
    parameters = listOf()
)

internal object AccountPreferencesSetOver18Fingerprint : Fingerprint(
    definingClass = ACCOUNT_PREFERENCES_CLASS,
    name = "setOver18",
    returnType = "V",
    parameters = listOf("Z")
)

/**
 * Calls the preference repository setter of the NSFW viewing setting.
 * Reddit 2026.43.0 and newer use this setter instead of the account model setter.
 */
internal object SetOver18Fingerprint : Fingerprint(
    definingClass = $$"Lcom/reddit/account/repository/RedditPreferenceRepository$setOver18$1;",
    name = "invokeSuspend",
    returnType = "Ljava/lang/Object;",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        methodCall(
            returnType = "Ljava/lang/Object;",
            parameters = listOf("Z", "Z", "Lkotlin/coroutines/jvm/internal/ContinuationImpl;")
        )
    )
)

internal object NsfwBottomSheetContentHeightFingerprint : Fingerprint(
    filters = listOf(
        string("nsfw_bottom_sheet_content"),
        literal(256f),
        methodCall(
            returnType = "L",
            parameters = listOf("L", "F"),
            location = MatchAfterImmediately()
        )
    )
)

/**
 * Buttons of the NSFW bottom sheet. The first button turns on NSFW content.
 */
internal object NsfwBottomSheetButtonsFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    filters = listOf(
        resourceLiteral(ResourceType.STRING, "nsfw_description"),
        methodCall(
            definingClass = "Lcom/reddit/ui/compose/ds/",
            returnType = "V"
        ),
        string("nsfw_button_sheet_cancel")
    )
)

/**
 * Finds a method that uses a boxed string resource.
 */
internal fun boxedStringResourceFingerprint(name: String) = Fingerprint(
    filters = listOf(
        resourceLiteral(ResourceType.STRING, name),
        methodCall(
            smali = "Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;",
            location = MatchAfterImmediately()
        ),
        opcode(
            opcode = Opcode.MOVE_RESULT_OBJECT,
            location = MatchAfterImmediately()
        )
    )
)

/**
 * NSFW dialog, where the positive button turns on NSFW content.
 */
internal object NsfwDialogFingerprint : Fingerprint(
    filters = listOf(
        resourceLiteral(ResourceType.STRING, "nsfw_dialog_title"),
        methodCall(
            name = "setPositiveButton",
            parameters = listOf("I", "Landroid/content/DialogInterface\$OnClickListener;")
        )
    )
)

/**
 * NSFW dialog with a title and message for the type of content.
 * Reddit 2026.46.0 and newer.
 */
internal object NsfwWarningTargetDialogFingerprint : Fingerprint(
    parameters = listOf(
        "Landroid/content/Context;",
        "Landroid/content/DialogInterface\$OnClickListener;",
        "Landroid/content/DialogInterface\$OnClickListener;",
        "Lcom/reddit/nsfw/NsfwWarningTarget;"
    ),
    filters = listOf(
        methodCall(
            name = "setPositiveButton",
            parameters = listOf("I", "Landroid/content/DialogInterface\$OnClickListener;")
        )
    )
)

/**
 * Shows the NSFW bottom sheet or the NSFW dialog, based on a feature flag.
 */
internal object ShowNsfwAlertFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Lkotlin/jvm/functions/Function0;"),
    filters = listOf(
        methodCall(smali = "Ljava/lang/Boolean;->booleanValue()Z"),
        opcode(
            opcode = Opcode.MOVE_RESULT,
            location = MatchAfterImmediately()
        ),
        newInstance("Lcom/reddit/safety/roadblocks/nsfw/NsfwCommunityBottomSheet;")
    )
)

internal object SearchBannerViewStateToStringFingerprint : Fingerprint(
    name = "toString",
    parameters = listOf(),
    returnType = "Ljava/lang/String;",
    filters = listOf(
        string("SearchBannerViewState(id="),
        string(", bannerBehaviors="),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            location = MatchAfterWithin(5)
        ),
        string(", isDismissed="),
        fieldAccess(
            opcode = Opcode.IGET_BOOLEAN,
            definingClass = "this",
            location = MatchAfterWithin(5)
        )
    )
)

internal object SearchBannerViewStateConstructorFingerprint : Fingerprint(
    classFingerprint = SearchBannerViewStateToStringFingerprint,
    name = "<init>",
    returnType = "V",
    filters = listOf(
        opcode(Opcode.RETURN_VOID)
    )
)

internal object SearchBannerBehaviorsToStringFingerprint : Fingerprint(
    name = "toString",
    parameters = listOf(),
    returnType = "Ljava/lang/String;",
    filters = listOf(
        string("SearchBannerBehaviors(cta="),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            location = MatchAfterWithin(5)
        )
    )
)

internal object SearchAdjustNsfwSettingsBehaviorToStringFingerprint : Fingerprint(
    name = "toString",
    parameters = listOf(),
    returnType = "Ljava/lang/String;",
    filters = listOf(
        string("SearchAdjustNsfwSettingsBehavior(id=")
    )
)

internal object SearchAgeVerificationFlowBehaviorToStringFingerprint : Fingerprint(
    name = "toString",
    parameters = listOf(),
    returnType = "Ljava/lang/String;",
    filters = listOf(
        string("SearchAgeVerificationFlowBehavior(id=")
    )
)

internal object SearchDropdownToStringFingerprint : Fingerprint(
    name = "toString",
    parameters = listOf(),
    returnType = "Ljava/lang/String;",
    filters = listOf(
        string("SearchDropdown(id="),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Ljava/util/List;"
        )
    )
)

/**
 * Maps a search filter to its UI model. Parses the options of a safe search dropdown.
 */
internal object SearchFilterUiModelFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/search/combined/ui/CombinedSearchResultsViewModel;",
    filters = listOf(
        methodCall(returnType = "Lcom/reddit/domain/SafeSearch;")
    )
)

/**
 * Saves a community as recently visited.
 */
internal object SetUpSubredditFingerprint : Fingerprint(
    definingClass = $$"Lcom/reddit/screens/pager/v2/SubredditPagerViewModel$setUpSubreddit$3;",
    name = "invokeSuspend",
    returnType = "Ljava/lang/Object;",
    filters = listOf(
        methodCall(
            returnType = "Ljava/lang/Object;",
            parameters = listOf(
                "Lcom/reddit/domain/model/Subreddit;",
                "Lkotlin/coroutines/jvm/internal/ContinuationImpl;"
            )
        )
    )
)

/**
 * Maps recently visited communities to the rows of the community drawer and the recently visited screen.
 */
internal object RecentlyVisitedMapperFingerprint : Fingerprint(
    returnType = "Ljava/util/ArrayList;",
    parameters = listOf("Ljava/util/List;", "Z"),
    filters = listOf(
        checkCast("Lcom/reddit/domain/model/Subreddit;"),
        methodCall(smali = "Lcom/reddit/domain/model/Subreddit;->getSubredditType()Ljava/lang/String;")
    )
)

// endregion
