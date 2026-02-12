package app.morphe.patches.reddit.misc.extension.hooks

import app.morphe.patcher.Fingerprint

internal val redditMainActivityOnCreateFingerprint = Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
    custom = { method, classDef ->
        method.name == "onCreate" && classDef.type == "Lcom/reddit/launch/main/MainActivity;"
    }
)

internal val redditActivityOnCreateFingerprint = Fingerprint(
    custom = { method, classDef ->
        method.name == "onCreate" && classDef.type.endsWith("/FrontpageApplication;")
    }
)
