package app.morphe.patches.youtube.layout.theme

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.literal
import app.morphe.patches.shared.layout.branding.NotificationFingerprint.method
import app.morphe.patches.youtube.shared.YOUTUBE_MAIN_ACTIVITY_CLASS_TYPE

internal object UseGradientLoadingScreenFingerprint : Fingerprint(
    filters = listOf(
        literal(45412406L)
    )
)

internal object SplashScreenStyleFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
    filters = listOf(
        anyInstruction(
            literal(1074339245), // 20.30+
            literal(269032877L) // 20.29 and lower.
        )
    ),
    custom = { method, classDef ->
        method.name == "onCreate" && classDef.type == YOUTUBE_MAIN_ACTIVITY_CLASS_TYPE
    }
)
