package app.morphe.patches.reddit.layout.navigation

import app.morphe.patcher.Fingerprint
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstruction
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal val bottomNavScreenFingerprint = Fingerprint(
    returnType = "L",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Landroid/content/res/Resources;"),
    strings = listOf("answersFeatures"),
    custom = { methodDef, _ ->
        methodDef.definingClass == "Lcom/reddit/launch/bottomnav/BottomNavScreen;" &&
                indexOfListBuilderInstruction(methodDef) >= 0
    }
)

fun indexOfListBuilderInstruction(methodDef: Method) =
    methodDef.indexOfFirstInstruction {
        opcode == Opcode.INVOKE_VIRTUAL &&
                getReference<MethodReference>()?.toString() == "Lkotlin/collections/builders/ListBuilder;->build()Ljava/util/List;"
    }
