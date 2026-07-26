package app.morphe.patches.youtube.layout.hide.signintotvpopup

import app.morphe.patcher.Fingerprint
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.resourceLiteral

internal object SignInToTVPopupFingerprint : Fingerprint(
    returnType = "Landroid/view/View;",
    filters = listOf(
        resourceLiteral(
            ResourceType.ID,
            "express_sign_in_modal"
        )
    )
)
