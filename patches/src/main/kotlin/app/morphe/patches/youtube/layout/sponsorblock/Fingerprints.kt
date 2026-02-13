package app.morphe.patches.youtube.layout.sponsorblock

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.checkCast
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.resourceLiteral
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionReversed
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal object AppendTimeFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Ljava/lang/CharSequence;", "Ljava/lang/CharSequence;", "Ljava/lang/CharSequence;"),
    filters = listOf(
        resourceLiteral(ResourceType.STRING, "total_time"),

        methodCall(smali = "Landroid/content/res/Resources;->getString(I[Ljava/lang/Object;)Ljava/lang/String;"),
        opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterImmediately())
    )
)

internal object ControlsOverlayFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        resourceLiteral(ResourceType.ID, "inset_overlay_view_layout"),
        checkCast("Landroid/widget/FrameLayout;", MatchAfterWithin(20))
    )
)

/**
 * Resolves to the class found in [seekbarFingerprint].
 */
internal object RectangleFieldInvalidatorFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        methodCall(name = "invalidate")
    )
)

internal object AdProgressTextViewVisibilityFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Z"),
    custom = { method, _ ->
        // TODO: Convert this to an instruction filter
        indexOfAdProgressTextViewVisibilityInstruction(method) >= 0
    }
)

internal fun indexOfAdProgressTextViewVisibilityInstruction(method: Method) =
    method.indexOfFirstInstructionReversed {
        val reference = getReference<MethodReference>()
        reference?.definingClass ==
                "Lcom/google/android/libraries/youtube/ads/player/ui/AdProgressTextView;"
                && reference.name =="setVisibility"
    }

