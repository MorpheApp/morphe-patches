package app.morphe.patches.music.layout.branding

import app.morphe.patcher.Fingerprint
import app.morphe.patches.music.shared.YOUTUBE_MUSIC_MAIN_ACTIVITY_CLASS_TYPE
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.resourceLiteral

internal object CairoSplashAnimationConfigFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
    filters = listOf(
        resourceLiteral(ResourceType.LAYOUT, "main_activity_launch_animation")
    ),
    custom = { method, classDef ->
        method.name == "onCreate" && method.definingClass == YOUTUBE_MUSIC_MAIN_ACTIVITY_CLASS_TYPE
    }
)
