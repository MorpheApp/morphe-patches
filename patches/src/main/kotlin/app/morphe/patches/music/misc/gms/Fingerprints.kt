package app.morphe.patches.music.misc.gms

import app.morphe.patcher.Fingerprint

internal val musicActivityOnCreateFingerprint = Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
    custom = { method, classDef ->
        method.name == "onCreate" && classDef.endsWith("/MusicActivity;")
    }
)
