package app.morphe.patches.youtube.misc.settings

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.literal
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.resourceLiteral
import com.android.tools.smali.dexlib2.AccessFlags

internal object SetThemeFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf(),
    filters = listOf(
        resourceLiteral(ResourceType.STRING, "app_theme_appearance_dark"),
    )
)

internal object CairoFragmentConfigFingerprint : Fingerprint(
    filters = listOf(
        literal(45532100L)
    )
)
