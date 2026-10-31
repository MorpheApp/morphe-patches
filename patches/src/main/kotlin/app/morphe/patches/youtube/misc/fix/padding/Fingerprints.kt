/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.fix.padding

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.Opcode

/**
 * Changes how the height of the navigation bar and the miniplayer is given to the pages,
 * which leaves wrong padding in some pages, such as the incognito search.
 * 21.29 and older read it with a single accessor method.
 */
internal object IncognitoSearchPaddingFeatureFlagFingerprint : Fingerprint(
    filters = listOf(
        literal(45724388L)
    )
)

/**
 * Sets the bottom margin of the layout that holds the page and the navigation bar.
 * With the keyboard open and a text field focused, the margin is the keyboard height,
 * otherwise the bottom of the window insets.
 */
internal object PageBottomMarginFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/graphics/Rect;"),
    filters = listOf(
        methodCall(smali = "Landroid/view/View;->findFocus()Landroid/view/View;"),
        methodCall(
            smali = "Landroid/view/View;->onCheckIsTextEditor()Z",
            location = MatchAfterWithin(5)
        ),
        fieldAccess(
            opcode = Opcode.IGET,
            definingClass = "Landroid/graphics/Rect;",
            name = "bottom"
        )
    )
)

/**
 * Sets the padding of a feed list. When the feed is laid out behind the navigation bar,
 * the bottom padding holds the navigation bar height and the bottom window inset.
 */
internal object FeedListPaddingFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        methodCall(
            definingClass = "Landroid/support/v7/widget/RecyclerView;",
            name = "setClipToPadding"
        ),
        methodCall(
            definingClass = "Landroid/support/v7/widget/RecyclerView;",
            name = "setPadding",
            location = MatchAfterImmediately()
        )
    )
)

/**
 * Sets the bottom padding of the list of a browse page laid out behind the navigation bar,
 * to the navigation bar height plus the bottom window inset, once the window insets are known.
 */
internal object BrowseListBottomPaddingFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/lang/Object;"),
    filters = listOf(
        methodCall(
            definingClass = "Landroid/support/v7/widget/RecyclerView;",
            name = "getPaddingRight"
        ),
        fieldAccess(
            opcode = Opcode.IGET,
            definingClass = "Landroid/graphics/Rect;",
            name = "bottom",
            location = MatchAfterWithin(6)
        ),
        methodCall(
            definingClass = "Landroid/support/v7/widget/RecyclerView;",
            name = "setPadding",
            location = MatchAfterWithin(6)
        )
    )
)
