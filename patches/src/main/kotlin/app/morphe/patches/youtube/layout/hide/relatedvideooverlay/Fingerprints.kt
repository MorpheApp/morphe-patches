package app.morphe.patches.youtube.layout.hide.relatedvideooverlay

import app.morphe.patcher.Fingerprint
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.resourceLiteral

internal val relatedEndScreenResultsParentFingerprint = Fingerprint(
    returnType = "V",
    filters = listOf(
        resourceLiteral(ResourceType.LAYOUT, "app_related_endscreen_results")
    )
)

internal val relatedEndScreenResultsFingerprint = Fingerprint(
    returnType = "V",
    parameters = listOf(
        "I",
        "Z",
        "I",
    )
)
