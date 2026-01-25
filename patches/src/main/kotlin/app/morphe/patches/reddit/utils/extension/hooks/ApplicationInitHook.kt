package app.morphe.patches.reddit.utils.extension.hooks

import app.morphe.patcher.Fingerprint
import app.morphe.patches.shared.misc.extension.ExtensionHook

internal val redditActivityOnCreateFingerprint = Fingerprint(
    custom = { method, classDef ->
        method.name == "onCreate" && classDef.type.endsWith("/FrontpageApplication;")
    }
)

internal val applicationInitHook = ExtensionHook(
    fingerprint = redditActivityOnCreateFingerprint
)
