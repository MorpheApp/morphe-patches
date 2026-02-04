package app.morphe.patches.youtube.misc.contexthook

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstruction
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val CLIENT_INFO_CLASS_DESCRIPTOR =
    "Lcom/google/protos/youtube/api/innertube/InnertubeContext\$ClientInfo;"

internal object AuthenticationChangeListenerFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    returnType = "V",
    strings = listOf("Authentication changed while request was being made"),
    custom = { method, _ ->
        // TODO: Change this to an instruction filter.
        indexOfMessageLiteBuilderReference(method) >= 0
    }
)

internal fun indexOfMessageLiteBuilderReference(method: Method, type: String = "L") =
    method.indexOfFirstInstruction {
        val reference = getReference<MethodReference>()
        opcode == Opcode.INVOKE_VIRTUAL &&
                reference?.parameterTypes?.isEmpty() == true &&
                reference.returnType.startsWith(type)
    }

internal object BuildClientContextBodyConstructorFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    returnType = "V",
    filters = listOf(
        string("Android Wear"),
        opcode(Opcode.IF_EQZ),
        string("Android Automotive", location = MatchAfterImmediately()),
        string("Android"),
        fieldAccess(opcode = Opcode.IPUT_OBJECT, location = MatchAfterImmediately())
    )
)

internal object BuildClientContextBodyFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf(),
    filters = listOf(
        fieldAccess(opcode = Opcode.SGET, name = "SDK_INT"),
        fieldAccess(opcode = Opcode.IPUT_OBJECT, definingClass = CLIENT_INFO_CLASS_DESCRIPTOR, type = "Ljava/lang/String;"),
        opcode(Opcode.OR_INT_LIT16),
    )
)

internal object BuildDummyClientContextBodyFingerprint : Fingerprint(
    filters = listOf(
        fieldAccess(opcode = Opcode.IGET_OBJECT, name = "instance"),
        string("10.29", location = MatchAfterWithin(10)),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = CLIENT_INFO_CLASS_DESCRIPTOR,
            type = "Ljava/lang/String;",
            location = MatchAfterImmediately()
        ),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            type = CLIENT_INFO_CLASS_DESCRIPTOR,
        ),
    )
)

internal object BrowseEndpointConstructorFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    returnType = "V",
    filters = listOf(
        string(""),
        fieldAccess(
            opcode = Opcode.IPUT_OBJECT,
            definingClass = "this",
            type = "Ljava/lang/String;",
            location = MatchAfterImmediately()
        ),
    )
)

internal object BrowseEndpointParentFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    strings = listOf("browseId"),
)

internal object GuideEndpointConstructorFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    returnType = "V",
    strings = listOf("guide"),
)

internal object ReelCreateItemsEndpointConstructorFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    returnType = "V",
    strings = listOf("reel/create_reel_items"),
)

internal object ReelItemWatchEndpointConstructorFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    returnType = "V",
    strings = listOf("reel/reel_item_watch"),
)

internal object ReelWatchSequenceEndpointConstructorFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    returnType = "V",
    strings = listOf("reel/reel_watch_sequence"),
)

internal object TranscriptEndpointConstructorFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    returnType = "V",
    strings = listOf("get_transcript"),
)