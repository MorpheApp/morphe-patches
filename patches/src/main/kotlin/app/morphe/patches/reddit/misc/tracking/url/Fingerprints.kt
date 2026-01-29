package app.morphe.patches.reddit.misc.tracking.url

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall

internal val shareLinkFormatterFingerprint = Fingerprint(
    returnType = "Ljava/lang/String;",
    parameters = listOf("Ljava/lang/String;", "Ljava/util/Map;"),
    filters = listOf(
        methodCall(smali = "Landroid/net/Uri${'$'}Builder;->clearQuery()Landroid/net/Uri${'$'}Builder;")
    )
)
