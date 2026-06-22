package app.morphe.patches.youtube.misc.fix.pipchatbar

import app.morphe.patcher.Fingerprint
import app.morphe.patches.youtube.shared.YOUTUBE_MAIN_ACTIVITY_CLASS_TYPE

internal object PipModeChangedFingerprint : Fingerprint(
    definingClass = YOUTUBE_MAIN_ACTIVITY_CLASS_TYPE,
    name = "onPictureInPictureModeChanged",
    returnType = "V",
    parameters = listOf("Z", "Landroid/content/res/Configuration;"),
)
