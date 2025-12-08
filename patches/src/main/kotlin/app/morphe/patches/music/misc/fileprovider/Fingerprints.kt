package app.morphe.patches.music.utils.fix.fileprovider

import app.morphe.patcher.Fingerprint

internal object FileProviderResolverFingerprint : Fingerprint(
    returnType = "L",
    strings = listOf(
        "android.support.FILE_PROVIDER_PATHS",
        "Name must not be empty"
    )
)
