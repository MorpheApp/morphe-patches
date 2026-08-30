/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.layout.theme

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.resourceLiteral
import com.android.tools.smali.dexlib2.Opcode

/**
 * The top bar creates the view stub of the new content count, which is the number
 * next to the notification icon.
 */
internal object TopBarNewContentCountFingerprint : Fingerprint(
    filters = listOf(
        resourceLiteral(ResourceType.ID, "new_content_count"),
        // The app calls this on a view group and not on a view, so only the name is matched.
        methodCall(
            name = "findViewById",
            location = MatchAfterImmediately()
        ),
        opcode(
            opcode = Opcode.CHECK_CAST,
            location = MatchAfterWithin(3)
        ),
        methodCall(
            smali = "Landroid/view/ViewStub;->inflate()Landroid/view/View;",
            location = MatchAfterWithin(8)
        )
    )
)

/**
 * The dot the same button shows when there is no count, which the layout declares as a view of
 * its own and not as a stub. It is created right before the stub of the count.
 */
internal object TopBarNewContentDotFingerprint : Fingerprint(
    filters = listOf(
        resourceLiteral(ResourceType.ID, "new_content_dot"),
        methodCall(
            name = "findViewById",
            location = MatchAfterImmediately()
        ),
        opcode(
            opcode = Opcode.MOVE_RESULT_OBJECT,
            location = MatchAfterImmediately()
        ),
        resourceLiteral(
            ResourceType.ID,
            "new_content_count",
            location = MatchAfterWithin(5)
        )
    )
)
