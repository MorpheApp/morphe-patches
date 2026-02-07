package app.morphe.patches.youtube.layout.searchfilter

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.resourceLiteral
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal object SearchFilterDialogFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Landroid/view/View;",
    filters = listOf(
        opcode(Opcode.MOVE_RESULT_OBJECT),
        opcode(Opcode.CHECK_CAST, location = MatchAfterImmediately()),
        resourceLiteral(ResourceType.LAYOUT, "search_results_innertube_filter_dialog"),
    )
)

internal object SearchResponseParserFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PROTECTED, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;", "L"),
    filters = listOf(
        string("search_entity_mid"),
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            name = "containsKey",
            location = MatchAfterWithin(10)
        ),
        opcode(Opcode.SGET_OBJECT, location = MatchAfterWithin(6)),
    )
)
