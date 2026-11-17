/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4923
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.tabletgrid

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

/**
 * Composable that shows a feed as a list.
 */
internal object ListFeedFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/feeds/ui/composables/feed/",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    custom = { method, _ ->
        val parameters = method.parameterTypes
        parameters.size > 6
                && parameters[3].startsWith("Landroidx/compose/foundation/lazy/")
                && parameters[5] == "Lcom/reddit/feeds/data/FeedType;"
                && parameters[6] == "F"
    }
)

/**
 * Search results, which call the composable that shows a feed as a staggered grid.
 */
internal object SearchGridFeedFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    filters = listOf(
        string("search_screen_surface"),
        methodCall(
            opcode = Opcode.INVOKE_STATIC_RANGE,
            definingClass = "Lcom/reddit/feeds/ui/composables/feed/",
            returnType = "V"
        )
    )
)

/**
 * Footer that search results pass to the grid feed.
 */
internal object SearchGridFeedFooterFingerprint : Fingerprint(
    classFingerprint = SearchGridFeedFingerprint,
    filters = listOf(
        string("search_screen_surface"),
        fieldAccess(
            opcode = Opcode.SGET_OBJECT,
            type = {
                SearchGridFeedFingerprint.instructionMatches[1]
                    .methodReference.parameterTypes[7]
            }
        )
    )
)

/**
 * Staggered grid cells with a fixed number of columns.
 */
internal object FixedStaggeredGridCellsFingerprint : Fingerprint(
    name = "<init>",
    parameters = listOf("I"),
    filters = listOf(
        string("grid with no rows/columns")
    )
)

internal object CalculateCrossAxisCellSizesFingerprint : Fingerprint(
    classFingerprint = FixedStaggeredGridCellsFingerprint,
    returnType = "[I",
    parameters = listOf("I", "I"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IGET,
            definingClass = "this",
            type = "I"
        )
    )
)
