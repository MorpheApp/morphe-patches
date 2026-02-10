package app.morphe.patches.reddit.misc.extension.hooks

import app.morphe.patcher.Fingerprint

internal val redditMainActivityOnCreateFingerprint = Fingerprint(
    definingClass = "Lcom/reddit/launch/main/MainActivity;",
    name = "onCreate",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;")
)

internal val redditActivityOnCreateFingerprint = Fingerprint(
    definingClass = "/FrontpageApplication;",
    name = "onCreate"
)
