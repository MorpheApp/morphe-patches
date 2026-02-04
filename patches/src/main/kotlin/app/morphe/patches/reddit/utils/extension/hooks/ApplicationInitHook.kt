package app.morphe.patches.reddit.utils.extension.hooks

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patches.shared.misc.extension.ExtensionHook

internal val redditMainActivityOnCreateFingerprint = Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
    custom = { method, classDef ->
        method.name == "onCreate" && classDef.type == "Lcom/reddit/launch/main/MainActivity;"
    }
)

internal val redditActivityOnCreateFingerprint = Fingerprint(
    definingClass = "/FrontpageApplication;",
    name = "onCreate"
)
