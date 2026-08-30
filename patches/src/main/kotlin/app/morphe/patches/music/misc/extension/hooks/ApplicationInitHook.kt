package app.morphe.patches.music.misc.extension.hooks

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.string
import app.morphe.patches.all.misc.extension.ExtensionHook
import app.morphe.patches.music.shared.MusicActivityOnCreateFingerprint

internal object YouTubeMusicApplicationInitFingerprint : Fingerprint(
    name = "onCreate",
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        string("activity")
    )
)

internal val youTubeMusicApplicationInitHook = ExtensionHook(YouTubeMusicApplicationInitFingerprint)
internal val youTubeMusicApplicationInitOnCreateHook = ExtensionHook(MusicActivityOnCreateFingerprint)
