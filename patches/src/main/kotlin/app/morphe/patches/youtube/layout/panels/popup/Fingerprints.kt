package app.morphe.patches.youtube.layout.panels.popup

import app.morphe.patcher.Fingerprint
import app.morphe.patches.shared.layout.branding.NotificationFingerprint.strings

internal object EngagementPanelControllerFingerprint : Fingerprint(
    returnType = "L",
    strings = listOf(
        "EngagementPanelController: cannot show EngagementPanel before EngagementPanelController.init() has been called.",
        "[EngagementPanel] Cannot show EngagementPanel before EngagementPanelController.init() has been called.",
    )
)
