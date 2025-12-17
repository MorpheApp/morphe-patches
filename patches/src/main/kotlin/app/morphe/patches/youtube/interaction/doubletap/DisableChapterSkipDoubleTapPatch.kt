package app.morphe.patches.youtube.interaction.doubletap

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.OpcodesFilter.Companion.opcodesToFilters
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_20_14_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import java.util.logging.Logger

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/DisableDoubleTapActionsPatch;"

@Suppress("unused")
val disableDoubleTapActionsPatch = bytecodePatch(
    name = "Disable double tap actions",
    description = "Adds an option to disable player double tap gestures.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        versionCheckPatch
    )

    compatibleWith(
        "com.google.android.youtube"(
            "20.14.43",
            "20.21.37",
            "20.31.42",
            "20.46.41",
        )
    )

    execute {
        if (!is_20_14_or_greater) {
            // Show a message if users have version constrain off and are patching the oldest version,
            // just to prevent spamming a cryptic error message the user may not understand
            // and don't add in app settings that won't work.
            return@execute Logger.getLogger(this::class.java.name).warning(
                "Disable double tap actions requires 20.14.43+"
            )
        }

        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_disable_chapter_skip_double_tap"),
        )

        val doubleTapInfoGetSeekSourceFingerprint = Fingerprint(
            accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
            returnType = SeekTypeEnumFingerprint.originalClassDef.type,
            parameters = listOf("Z"),
            filters = opcodesToFilters(
                Opcode.IF_EQZ,
                Opcode.SGET_OBJECT,
                Opcode.RETURN_OBJECT,
                Opcode.SGET_OBJECT,
                Opcode.RETURN_OBJECT,
            ),
            custom = { _, classDef ->
                classDef.fields.count() == 4
            }
        )

        // Force isChapterSeek flag to false.
        doubleTapInfoGetSeekSourceFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p1 }, $EXTENSION_CLASS_DESCRIPTOR->disableDoubleTapChapters(Z)Z
                move-result p1
            """
        )

        DoubleTapInfoCtorFingerprint.match(
            doubleTapInfoGetSeekSourceFingerprint.classDef
        ).method.addInstructions(
            0,
            """
                invoke-static { p3 }, $EXTENSION_CLASS_DESCRIPTOR->disableDoubleTapChapters(Z)Z
                move-result p3
            """
        )
    }
}
