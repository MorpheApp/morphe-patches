package app.morphe.patches.reddit.layout.subredditdialog

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.string
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstruction
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal val frequentUpdatesHandlerFingerprint = Fingerprint(
    returnType = "Ljava/lang/Object;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    filters = OpcodesFilter.opcodesToFilters(Opcode.CONST_STRING),
    custom = { method, classDef ->
        classDef.type.startsWith("Lcom/reddit/screens/pager/FrequentUpdatesHandler${'$'}handleFrequentUpdates$") &&
                method.name == "invokeSuspend"
    }
)

internal fun listOfUserIsSubscriberInstruction(method: Method) =
    method.implementation?.instructions
        ?.withIndex()
        ?.filter { (_, instruction) ->
            val reference = (instruction as? ReferenceInstruction)?.reference
            instruction.opcode == Opcode.INVOKE_INTERFACE &&
                    reference is MethodReference &&
                    reference.name == "getUserIsSubscriber" &&
                    reference.returnType == "Ljava/lang/Boolean;"
        }
        ?.map { (index, _) -> index }
        ?.reversed()
        ?: emptyList()

internal val nsfwAlertEmitFingerprint = Fingerprint(
    returnType = "Ljava/lang/Object;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    filters = listOf(
        string("nsfwAlertDelegate")
    ),
    custom = { method, classDef ->
        classDef.type.startsWith("Lcom/reddit/screens/pager/v2/") &&
                method.name == "emit" &&
                indexOfGetOver18Instruction(method) >= 0 &&
                indexOfHasBeenVisitedInstruction(method) >= 0 &&
                indexOfIsIncognitoInstruction(method) >= 0
    }
)

// TODO: Replace with methodCall() instruction filter usage
internal fun indexOfGetOver18Instruction(methodDef: Method) =
    methodDef.indexOfFirstInstruction {
        val reference = getReference<MethodReference>()
        opcode == Opcode.INVOKE_VIRTUAL &&
                reference?.name == "getOver18" &&
                reference.returnType == "Ljava/lang/Boolean;"
    }

// TODO: Replace with methodCall() instruction filter usage
internal fun indexOfIsIncognitoInstruction(methodDef: Method) =
    methodDef.indexOfFirstInstruction {
        val reference = getReference<MethodReference>()
        opcode == Opcode.INVOKE_INTERFACE &&
                reference?.name == "isIncognito" &&
                reference.returnType == "Z"
    }

// TODO: Replace with methodCall() instruction filter usage
internal fun indexOfHasBeenVisitedInstruction(method: Method) =
    method.indexOfFirstInstruction {
        val reference = getReference<MethodReference>()
        opcode == Opcode.INVOKE_VIRTUAL &&
                reference?.name == "getHasBeenVisited" &&
                reference.returnType == "Z"
    }
