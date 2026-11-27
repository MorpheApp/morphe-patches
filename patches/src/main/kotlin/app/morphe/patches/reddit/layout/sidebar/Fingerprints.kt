/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.sidebar

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.checkCast
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.parametersMatch
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal const val HEADER_ITEM_CLASS = "Lcom/reddit/screens/drawer/community/HeaderItem;"

private const val COMMUNITY_DRAWER_SCREEN_CLASS =
    "Lcom/reddit/screens/drawer/community/CommunityDrawerScreen;"

private const val BIND_LIST_ON_VIEW_CLASS =
    $$"Lcom/reddit/screens/drawer/community/CommunityDrawerPresenter$bindListOnView$2;"

private object CommunityDrawerBuilderParentFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = $$"Lcom/reddit/navdrawer/analytics/CommunityDrawerAnalytics$Section;",
    parameters = listOf(HEADER_ITEM_CLASS),
    filters = listOf(
        methodCall("Ljava/lang/Enum;->ordinal()I"),
        fieldAccess($$"Lcom/reddit/navdrawer/analytics/CommunityDrawerAnalytics$Section;->" +
                $$"ABOUT:Lcom/reddit/navdrawer/analytics/CommunityDrawerAnalytics$Section;")
    )
)

internal object CommunityDrawerBuilderFingerprint : Fingerprint(
    classFingerprint = CommunityDrawerBuilderParentFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "V",
    filters = listOf(
        methodCall("Ljava/util/Collection;->isEmpty()Z"),
    ),
    custom = { method, _ ->
        parametersMatch(
            method.parameters,
            listOf(
                "L",
                "Ljava/util/List;",
                "Ljava/util/Collection;",
                "L",
                "L",
                "Z",
                "I"
            )
        ) || parametersMatch( // 2026.12.0+
            method.parameters,
            listOf(
                "Ljava/util/List;",
                "Ljava/util/Collection;",
                "L",
                "L",
                "Z",
                "I"
            )
        )
    }
)

internal object CtaItemUiModelToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    filters = listOf(
        string("CTAItemUiModel(uniqueId="),
        string(", titleResId="),
        fieldAccess(opcode = Opcode.IGET, type = "I", definingClass = "this", location = MatchAfterWithin(5))
    )
)

internal object GenericItemUiModelToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    filters = listOf(
        string("GenericItemUiModel(uniqueId="),
        string(", titleResId="),
        fieldAccess(opcode = Opcode.IGET, type = "I", definingClass = "this", location = MatchAfterWithin(5))
    )
)

internal object DividerItemUiModelToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    filters = listOf(
        string("DividerItemUiModel(uniqueId=", StringComparisonType.STARTS_WITH)
    )
)

/**
 * Presenter method that builds the sidebar list.
 */
internal object CommunityDrawerBuildListFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        // Field of the finished list.
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = "Ljava/util/List;"
        ),
        newInstance(BIND_LIST_ON_VIEW_CLASS)
    )
)

/**
 * Presenter method that handles the click of a sidebar item.
 * It casts the item to the row type of [CtaItemUiModelToStringFingerprint].
 */
internal object CommunityDrawerItemClickFingerprint : Fingerprint(
    classFingerprint = CommunityDrawerBuildListFingerprint,
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = COMMUNITY_DRAWER_SCREEN_CLASS
        ),
        checkCast({ CtaItemUiModelToStringFingerprint.originalClassDef.type }),
        // Closes the sidebar.
        methodCall(
            definingClass = COMMUNITY_DRAWER_SCREEN_CLASS,
            returnType = "V",
            parameters = listOf()
        ),
        fieldAccess($$"Lcom/reddit/navdrawer/analytics/CommunityDrawerAnalytics$Noun;->" +
                $$"REDDIT_RULES:Lcom/reddit/navdrawer/analytics/CommunityDrawerAnalytics$Noun;")
    )
)

/**
 * Passes the finished sidebar list to the adapter.
 */
internal object CommunityDrawerBindListFingerprint : Fingerprint(
    definingClass = BIND_LIST_ON_VIEW_CLASS,
    name = "invokeSuspend",
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_OBJECT, type = "Ljava/util/List;")
    )
)

internal object HeaderItemUiModelToStringFingerprint : Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    filters = listOf(
        string("HeaderItemUiModel(uniqueId=")
    )
)
