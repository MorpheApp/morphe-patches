package app.morphe.patches.youtube.interaction.doubletap

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.patch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_21_11_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/DisableDoubleTapActionsPatch;"

@Suppress("unused")
val disableDoubleTapActionsPatch = patch(
    name = "Disable double tap actions",
    description = "Adds an option to disable player double tap gestures.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        versionCheckPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_disable_chapter_skip_double_tap", summary = true),
        )

        val doubleTapInfoGetSeekSourceFingerprint = Fingerprint(
            classFingerprint = DoubleTapInfoCtorFingerprint,
            accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
            returnType = SeekTypeEnumFingerprint.originalClassDef.type,
            parameters = listOf("Z")
        )

        // Force isChapterSeek flag to false.
        doubleTapInfoGetSeekSourceFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS->disableDoubleTapChapters(Z)Z
                move-result p1
            """
        )

        DoubleTapInfoCtorFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p3 }, $EXTENSION_CLASS->disableDoubleTapChapters(Z)Z
                move-result p3
            """
        )

        // Some accounts get the chapter skip turned off by the server config.
        DoubleTapChapterSkipConfigFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.first().index
                val register = it.instructionMatches.first().getInstruction<TwoRegisterInstruction>().registerA

                addInstructions(
                    index + 1,
                    """
                        invoke-static { v$register }, $EXTENSION_CLASS->enableDoubleTapChaptersConfig(Z)Z
                        move-result v$register
                    """
                )
            }
        }

        if (is_21_11_or_greater) {
            DoubleTapChapterSkipFeatureFlagFingerprint.let {
                it.method.insertLiteralOverride(
                    it.instructionMatches.first().index,
                    "$EXTENSION_CLASS->disableDoubleTapChaptersFeatureFlag(Z)Z"
                )
            }
        }
    }
}
