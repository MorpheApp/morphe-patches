/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3516
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

internal object LatestFeedContentFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/feeds/latest/impl/ui/LatestFeedScreen;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", "F", "L", "I", "I"),
    filters = listOf(
        methodCall(
            opcode = Opcode.INVOKE_STATIC_RANGE,
            definingClass = "Lcom/reddit/feeds/ui/composables/feed/",
            returnType = "V"
        )
    )
)

internal object SearchMasonryCallFingerprint : Fingerprint(
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

internal object SearchFooterFieldFingerprint : Fingerprint(
    classFingerprint = SearchMasonryCallFingerprint,
    filters = listOf(
        string("search_screen_surface"),
        fieldAccess(
            opcode = Opcode.SGET_OBJECT,
            type = {
                SearchMasonryCallFingerprint.instructionMatches[1]
                    .methodReference.parameterTypes[7]
            }
        )
    )
)

internal object StaggeredCellsFingerprint : Fingerprint(
    name = "<init>",
    parameters = listOf("I"),
    filters = listOf(
        string("grid with no rows/columns")
    )
)

internal object CalculateCellsFingerprint : Fingerprint(
    classFingerprint = StaggeredCellsFingerprint,
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
