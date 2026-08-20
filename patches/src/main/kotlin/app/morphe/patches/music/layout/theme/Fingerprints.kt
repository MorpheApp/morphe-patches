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
