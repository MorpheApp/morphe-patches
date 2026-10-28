package app.morphe.patches.youtube.misc.fix.accessibility

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral
import com.android.tools.smali.dexlib2.Opcode

private const val BOTTOM_UI_CONTAINER =
    "Lcom/google/android/apps/youtube/app/common/ui/bottomui/BottomUiContainer;"

internal object CaptionTextViewConstructorFingerprint : Fingerprint(
    superclass = "Landroid/view/View;",
    name = "<init>",
    parameters = listOf("Landroid/content/Context;"),
    filters = listOf(
        resourceLiteral(ResourceType.ID, "subtitle_window_identifier"),
        opcode(Opcode.RETURN_VOID)
    )
)

internal object TouchDelegateConstructorFingerprint : Fingerprint(
    superclass = "Landroid/view/TouchDelegate;",
    name = "<init>",
    parameters = listOf("Lcom/facebook/litho/ComponentHost;")
)

/**
 * 21.30+
 */
internal object TouchExplorationHoverEventFingerprint : Fingerprint(
    classFingerprint = TouchDelegateConstructorFingerprint,
    name = "onTouchExplorationHoverEvent",
    returnType = "Z",
    parameters = listOf("Landroid/view/MotionEvent;")
)

internal object BottomUiKeyboardVisibilityFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        literal(45731499L),
        opcode(Opcode.IGET_BOOLEAN, MatchAfterWithin(10)),
        opcode(Opcode.IF_EQ, MatchAfterWithin(2)),
        opcode(Opcode.MOVE, MatchAfterImmediately()),
        opcode(Opcode.GOTO, MatchAfterImmediately()),
        literal(8, opcodes = listOf(Opcode.CONST_16), MatchAfterImmediately()),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            smali = "$BOTTOM_UI_CONTAINER->setVisibility(I)V",
            location = MatchAfterImmediately()
        )
    )
)

internal object BottomUiVisibilityGateFingerprint : Fingerprint(
    definingClass = BOTTOM_UI_CONTAINER,
    returnType = "V",
    parameters = listOf("Z"),
    filters = listOf(
        methodCall(smali = "$BOTTOM_UI_CONTAINER->setVisibility(I)V")
    )
)
