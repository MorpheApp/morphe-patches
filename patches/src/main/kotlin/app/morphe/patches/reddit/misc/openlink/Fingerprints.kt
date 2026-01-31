package app.morphe.patches.reddit.misc.openlink

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.OpcodesFilter
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import app.morphe.util.indexOfFirstInstruction
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

internal val customReportsFingerprint = Fingerprint(
    definingClass = "/customreports/",
    returnType = "V",
    filters = listOf(
        string("https://www.crisistextline.org/")
    ),
    custom = { methodDef, classDef ->
        indexOfScreenNavigatorInstruction(methodDef) >= 0
    }
)

// TODO: Replace with methodCall() instruction filter usage
fun indexOfScreenNavigatorInstruction(method: Method) =
    method.indexOfFirstInstruction {
        (this as? ReferenceInstruction)?.reference?.toString()
            ?.contains("Landroid/app/Activity;Landroid/net/Uri;") == true
    }

internal val screenNavigatorFingerprint = Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    filters = OpcodesFilter.opcodesToFilters(
        Opcode.CONST_STRING,
        Opcode.INVOKE_STATIC,
        Opcode.CONST_STRING,
        Opcode.INVOKE_STATIC
    ),
    strings = listOf("activity", "uri"),
    custom = { _, classDef -> classDef.sourceFile == "RedditScreenNavigator.kt" }
)

internal val articleConstructorFingerprint = Fingerprint(
    returnType = "V",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.CONSTRUCTOR),
    filters = listOf(
        string("url"),
        methodCall(
            opcode = Opcode.INVOKE_STATIC,
            returnType = "V",
            parameters = listOf(
                "L",
                "Ljava/lang/String;"
            )
        )
    )
)

internal val articleToStringFingerprint = Fingerprint(
    name = "toString",
    returnType = "Ljava/lang/String;",
    filters = listOf(
        string("Article(postId=")
    )
)

internal val fbpActivityOnCreateFingerprint = Fingerprint(
    definingClass = "/FbpActivity;",
    name = "onCreate",
    returnType = "V"
)
