package app.morphe.patches.reddit.misc.openlink

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.reddit.utils.compatibility.Constants.COMPATIBLE_PACKAGE
import app.morphe.patches.reddit.utils.extension.Constants.PATCHES_PATH
import app.morphe.patches.reddit.utils.patch.PatchList.OPEN_LINKS_EXTERNALLY
import app.morphe.patches.reddit.utils.settings.is_2025_45_or_greater
import app.morphe.patches.reddit.utils.settings.settingsPatch
import app.morphe.patches.reddit.utils.settings.updatePatchStatus
import app.morphe.util.indexOfFirstStringInstructionOrThrow
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction

private const val EXTENSION_CLASS_DESCRIPTOR =
    "$PATCHES_PATH/OpenLinksExternallyPatch;"

@Suppress("unused")
val openLinksExternallyPatch = bytecodePatch(
    OPEN_LINKS_EXTERNALLY.title,
    OPEN_LINKS_EXTERNALLY.summary,
) {
    compatibleWith(COMPATIBLE_PACKAGE)

    dependsOn(
        settingsPatch,
        screenNavigatorMethodResolverPatch
    )

    execute {
        screenNavigatorMethod.apply {
            val insertIndex = indexOfFirstStringInstructionOrThrow("uri") + 2

            addInstructionsWithLabels(
                insertIndex, """
                    invoke-static {p1, p2}, $EXTENSION_CLASS_DESCRIPTOR->openLinksExternally(Landroid/app/Activity;Landroid/net/Uri;)Z
                    move-result v0
                    if-eqz v0, :dismiss
                    return-void
                    """, ExternalLabel("dismiss", getInstruction(insertIndex))
            )
        }

        if (is_2025_45_or_greater) {
            fbpActivityOnCreateFingerprint.method.addInstruction(
                0,
                "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS_DESCRIPTOR->" +
                        "setActivity(Landroid/app/Activity;)V"
            )

            articleConstructorFingerprint.match(
                this.mutableClassDefBy(articleToStringFingerprint.classDef)
            ).method.apply {
                val stringIndex = indexOfFirstStringInstructionOrThrow("url")
                val nullCheckIndex = indexOfNullCheckInstruction(this, stringIndex)
                val stringRegister = getInstruction<FiveRegisterInstruction>(nullCheckIndex).registerC

                addInstruction(
                    nullCheckIndex + 1,
                    "invoke-static/range { v$stringRegister .. v$stringRegister }, $EXTENSION_CLASS_DESCRIPTOR->" +
                            "openLinksExternally(Ljava/lang/String;)V"
                )
            }
        }

        updatePatchStatus(
            "enableOpenLinksExternally",
            OPEN_LINKS_EXTERNALLY
        )
    }
}
