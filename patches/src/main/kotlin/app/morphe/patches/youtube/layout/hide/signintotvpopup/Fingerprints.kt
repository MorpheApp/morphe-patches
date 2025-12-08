package app.morphe.patches.youtube.layout.hide.signintotvpopup

import app.morphe.patcher.Fingerprint
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.resourceLiteral

internal object SignInToTvPopupFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Ljava/lang/String;", "Z", "L"),
    filters = listOf(
        resourceLiteral(
            ResourceType.STRING,
            "mdx_seamless_tv_sign_in_drawer_fragment_title"
        )
    )
)