package app.morphe.patches.reddit.misc.tracking.url

import app.morphe.patcher.Fingerprint
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstruction
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal val shareLinkFormatterFingerprint = Fingerprint(
    returnType = "Ljava/lang/String;",
    parameters = listOf("Ljava/lang/String;", "Ljava/util/Map;"),
    custom = { method, _ ->
        indexOfClearQueryInstruction(method) >= 0
    }
)

fun indexOfClearQueryInstruction(method: Method) =
    method.indexOfFirstInstruction {
        getReference<MethodReference>()?.toString() == "Landroid/net/Uri${'$'}Builder;->clearQuery()Landroid/net/Uri${'$'}Builder;"
    }
