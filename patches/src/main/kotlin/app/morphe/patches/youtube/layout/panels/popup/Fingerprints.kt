package app.morphe.patches.youtube.layout.panels.popup

import app.morphe.patcher.Fingerprint

internal object EngagementPanelControllerFingerprint : Fingerprint(
    returnType = "L",
    strings = listOf(
        "EngagementPanelController: cannot show EngagementPanel before EngagementPanelController.init() has been called.",
        "[EngagementPanel] Cannot show EngagementPanel before EngagementPanelController.init() has been called.",
    )
)
