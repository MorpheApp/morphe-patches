package app.morphe.patches.reddit.misc.openlink

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.OpcodesFilter
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstruction
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal val customReportsFingerprint = Fingerprint(
    returnType = "V",
    strings = listOf("https://www.crisistextline.org/"),
    custom = { methodDef, classDef ->
        classDef.type.contains("/customreports/") &&
                indexOfScreenNavigatorInstruction(methodDef) >= 0
    }
)

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
    strings = listOf("url"),
    custom = { methodDef, _ ->
        indexOfNullCheckInstruction(methodDef) >= 0
    }
)

internal val articleToStringFingerprint = Fingerprint(
    returnType = "Ljava/lang/String;",
    strings = listOf("Article(postId="),
    custom = { methodDef, _ ->
        methodDef.name == "toString"
    }
)

internal val fbpActivityOnCreateFingerprint = Fingerprint(
    returnType = "V",
    custom = { methodDef, _ ->
        methodDef.definingClass.endsWith("/FbpActivity;") &&
                methodDef.name == "onCreate"
    }
)

internal fun indexOfNullCheckInstruction(methodDef: Method, startIndex: Int = 0) =
    methodDef.indexOfFirstInstruction(startIndex) {
        val reference = getReference<MethodReference>()
        opcode == Opcode.INVOKE_STATIC &&
                reference?.returnType == "V" &&
                reference.parameterTypes.size == 2 &&
                reference.parameterTypes[1] == "Ljava/lang/String;"
    }
