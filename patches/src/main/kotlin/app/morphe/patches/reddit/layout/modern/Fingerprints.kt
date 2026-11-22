/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.modern

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.checkCast
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.opcode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

// 2026.24.0 and earlier
internal object HomeRevampVariantFingerprint : Fingerprint(
    definingClass = "/HomeRevampVariant;",
    name = "isEnabled",
    returnType = "Z",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf()
)

internal const val FOR_YOU_FOLLOWING_VARIANT_CLASS =
    "Lcom/reddit/feeds/domain/features/ForYouFollowingVariant;"

internal const val HOME_PAGER_SCREEN_TAB_CLASS =
    "Lcom/reddit/feedslegacy/switcher/homepager/model/HomePagerScreenTab;"

/**
 * Options of the For You and Following home variant (isEnabled, isCenterFillLayoutEnabled, ...),
 * which all return false for the control group.
 *
 * 2026.40.0+
 */
internal object ForYouFollowingVariantOptionFingerprint : Fingerprint(
    definingClass = FOR_YOU_FOLLOWING_VARIANT_CLASS,
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        opcode(Opcode.RETURN)
    )
)

/**
 * Variant of the "android_for_you_following" experiment, which shows the modern home
 * with the For You, Following and Popular tabs. Null if the user is not in the experiment.
 *
 * 2026.45.0+
 */
internal object ForYouFollowingVariantFingerprint : Fingerprint(
    returnType = FOR_YOU_FOLLOWING_VARIANT_CLASS,
    parameters = listOf(),
    filters = listOf(
        checkCast(FOR_YOU_FOLLOWING_VARIANT_CLASS),
        // The cast variant is returned directly, so it is hooked after the cast.
        opcode(Opcode.RETURN_OBJECT, MatchAfterImmediately())
    )
)

/**
 * Creates the tab UI models of the home pager from its tabs (Home, Following, Popular).
 *
 * 2026.40.0+
 */
internal object HomePagerTabUiModelsFingerprint : Fingerprint(
    returnType = "Ljava/util/ArrayList;",
    parameters = listOf("Ljava/util/List;"),
    filters = listOf(
        newInstance("Lcom/reddit/feedslegacy/switcher/homepager/model/HomePagerScreenTabUiModel;")
    )
)

/**
 * If the Popular tab is shown on the home feed.
 *
 * 2026.44.0+
 */
internal object PopularHomeTabFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        fieldAccess(definingClass = FOR_YOU_FOLLOWING_VARIANT_CLASS, name = "FOR_YOU_NAME"),
        methodCall(definingClass = "Lcom/reddit/session/Session;", name = "isLoggedOut"),
        methodCall(definingClass = "Lcom/reddit/session/Session;", name = "isIncognito")
    )
)

/**
 * If the home feed shows its tabs. Matched in the class of [PopularHomeTabFingerprint].
 *
 * 2026.44.0+
 */
internal object HomeFeedTabsEnabledFingerprint : Fingerprint(
    classFingerprint = PopularHomeTabFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(
        methodCall(definingClass = FOR_YOU_FOLLOWING_VARIANT_CLASS, name = "isEnabled")
    )
)

/**
 * If the tabs of the home feed can be switched by swiping. False for every variant.
 *
 * 2026.44.0+
 */
internal object PagerSwipeEnabledFingerprint : Fingerprint(
    definingClass = FOR_YOU_FOLLOWING_VARIANT_CLASS,
    name = "isPagerSwipeEnabled",
    returnType = "Z",
    parameters = listOf()
)
