package app.morphe.patches.youtube.misc.gms

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.resourceLiteral
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal val specificNetworkErrorViewControllerFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        resourceLiteral(ResourceType.DRAWABLE, "ic_offline_no_content_upside_down"),
        resourceLiteral(ResourceType.STRING, "offline_no_content_body_text_not_offline_eligible"),
        methodCall(name = "getString", returnType = "Ljava/lang/String;"),
        opcode(Opcode.MOVE_RESULT_OBJECT, location = MatchAfterImmediately())
    )
)

// It's not clear if this second class is ever used and it may be dead code,
// but it the layout image/text is identical to the network error fingerprint above.
internal val loadingFrameLayoutControllerFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        resourceLiteral(ResourceType.DRAWABLE, "ic_offline_no_content_upside_down"),
        resourceLiteral(ResourceType.STRING, "offline_no_content_body_text_not_offline_eligible"),
        methodCall(name = "getString", returnType = "Ljava/lang/String;"),
        opcode(Opcode.MOVE_RESULT_OBJECT, MatchAfterImmediately())
    )
)