package app.morphe.patches.music.misc.settings

import app.morphe.patcher.Fingerprint

internal object GoogleApiActivityFingerprint : Fingerprint(
    definingClass = "/GoogleApiActivity;",
    name = "onCreate",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;")
)
