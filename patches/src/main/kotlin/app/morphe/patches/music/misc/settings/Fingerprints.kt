package app.morphe.patches.music.misc.settings

import app.morphe.patcher.Fingerprint

internal val googleApiActivityFingerprint = Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
    custom = { method, classDef ->
        classDef.endsWith("GoogleApiActivity;") && method.name == "onCreate"
    }
)
