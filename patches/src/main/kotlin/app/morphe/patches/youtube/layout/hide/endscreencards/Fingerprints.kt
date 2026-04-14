package app.morphe.patches.youtube.layout.hide.endscreencards

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.resourceLiteral
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object LayoutCircleFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(),
    returnType = "Landroid/view/View;",
    filters = listOf(
        resourceLiteral(ResourceType.LAYOUT, "endscreen_element_layout_circle"),
        opcode(Opcode.INVOKE_VIRTUAL, location = MatchAfterWithin(10)),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        opcode(Opcode.CHECK_CAST, location = MatchAfterImmediately())
    )
)

internal object LayoutIconFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf(),
    returnType = "Landroid/view/View;",
    filters = listOf(
        resourceLiteral(ResourceType.LAYOUT, "endscreen_element_layout_icon"),
        opcode(Opcode.INVOKE_VIRTUAL, location = MatchAfterWithin(10)),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        opcode(Opcode.CHECK_CAST, location = MatchAfterImmediately())
    )
)

internal object LayoutVideoFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC),
    parameters = listOf(),
    returnType = "Landroid/view/View;",
    filters = listOf(
        resourceLiteral(ResourceType.LAYOUT, "endscreen_element_layout_video"),
        opcode(Opcode.INVOKE_VIRTUAL, location = MatchAfterWithin(10)),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately()),
        opcode(Opcode.CHECK_CAST, location = MatchAfterImmediately())
    )
)

internal object ShowEndscreenCardsFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            type = "Ljava/lang/String;"
        ),
        fieldAccess(
            opcode = Opcode.IGET_OBJECT,
            type = "Ljava/lang/String;",
            location = MatchAfterImmediately()
        ),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            name = "ordinal",
            location = MatchAfterWithin(7)
        ),
        literal(5),
        literal(8),
        literal(9)
    ),
    custom = { method, classDef ->
        classDef.methods.count() == 5
                // 'public final' or 'final'
                && AccessFlags.FINAL.isSet(method.accessFlags)
    }
)