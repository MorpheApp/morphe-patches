package app.morphe.patches.youtube.misc.settings

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.literal
import app.morphe.patcher.opcode
import app.morphe.patches.shared.layout.branding.NotificationFingerprint.method
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.resourceLiteral
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

internal val licenseActivityOnCreateFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
    custom = { method, classDef ->
        method.name == "onCreate" && classDef.endsWith("/LicenseActivity;")
    }
)

internal val setThemeFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "L",
    parameters = listOf(),
    filters = listOf(
        resourceLiteral(ResourceType.STRING, "app_theme_appearance_dark"),
    )
)

internal val cairoFragmentConfigFingerprint = Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    filters = listOf(
        literal(45532100L),
        opcode(Opcode.MOVE_RESULT, location = MatchAfterWithin(10))
    )
)
