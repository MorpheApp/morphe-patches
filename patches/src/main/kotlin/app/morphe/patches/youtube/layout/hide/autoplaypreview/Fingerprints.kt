package app.morphe.patches.youtube.layout.hide.autoplaypreview

import app.morphe.patcher.Fingerprint
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.resourceLiteral

internal object AutoNavPreviewFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        resourceLiteral(ResourceType.ID, "autonav_preview_stub")
    )
)
internal object AutoplayPreviewFingerprint : Fingerprint(
    returnType = "V",
    parameters = emptyList()
)