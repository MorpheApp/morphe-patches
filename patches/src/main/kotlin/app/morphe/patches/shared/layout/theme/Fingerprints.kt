package app.morphe.patches.shared.layout.theme

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object LithoOnBoundsChangeFingerprint : Fingerprint(
    name = "onBoundsChange",
    accessFlags = listOf(AccessFlags.PROTECTED, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Landroid/graphics/Rect;"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = "Landroid/graphics/Path;"
        ),

        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            definingClass = "this",
            type = "Landroid/graphics/Paint",
            location = MatchAfterWithin(10)
        ),
        methodCall(
            smali = "Landroid/graphics/Paint;->setColor(I)V",
            location = MatchAfterImmediately()
        )
    )
)
