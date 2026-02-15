package app.morphe.patches.reddit.layout.navigation

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal val ADD_METHOD_CALL = methodCall(
    opcode = Opcode.INVOKE_INTERFACE,
    smali = "Ljava/util/List;->add(Ljava/lang/Object;)Z"
)

internal val GET_STRING_METHOD_CALL = methodCall(
    opcode = Opcode.INVOKE_VIRTUAL,
    smali = "Landroid/content/res/Resources;->getString(I)Ljava/lang/String;"
)

internal object BottomNavScreenFingerprint : Fingerprint(
    definingClass = "Lcom/reddit/launch/bottomnav/BottomNavScreen;",
    returnType = "L",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("Landroid/content/res/Resources;"),
    filters = listOf(
        GET_STRING_METHOD_CALL,
        methodCall(
            opcode = Opcode.INVOKE_DIRECT,
            parameters = listOf("Ljava/lang/String;", "L")
        ),
        ADD_METHOD_CALL,
        string("answersFeatures")
    )
)
