package app.morphe.patches.youtube.layout.hide.relatedvideooverlay

import app.morphe.patcher.Fingerprint
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.resourceLiteral

internal object RelatedEndScreenResultsParentFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        resourceLiteral(ResourceType.LAYOUT, "app_related_endscreen_results")
    )
)

internal object RelatedEndScreenResultsFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(
        "I",
        "Z",
        "I",
    )
)
