package app.morphe.patches.music.layout.navigationbar

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.checkCast
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.resourceLiteral
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object TabLayoutTextFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        anyInstruction(
            string("FEmusic_search"), // 8.49 and lower.
            string("FEsearch") // 8.50+
        ),

        // Hide navigation label.
        resourceLiteral(
            type = ResourceType.ID,
            name = "text1"
        ),
        methodCall(
            smali = "Landroid/view/View;->findViewById(I)Landroid/view/View;",
            location = MatchAfterWithin(5)
        ),
        checkCast(
            type = "Landroid/widget/TextView;",
            location = MatchAfterWithin(5)
        ),

        // Set navigation enum.
        anyInstruction(
            opcode(Opcode.SGET_OBJECT),
            opcode(Opcode.IGET_OBJECT)
        ),
        fieldAccess(
            opcode = Opcode.IGET,
            type = "I",
            location = MatchAfterWithin(5)
        ),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            returnType = "L",
            parameters = listOf("I"),
            location = MatchAfterWithin(5)
        ),
        opcode(
            opcode = Opcode.MOVE_RESULT_OBJECT,
            location = MatchAfterImmediately()
        ),

        // Hide navigation buttons.
        methodCall(
            name = "getVisibility"
        )
    )
)
