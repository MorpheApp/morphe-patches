package app.morphe.patches.shared.misc.privacy

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchBuilder
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.BasePreference
import app.morphe.patches.shared.misc.settings.preference.BasePreferenceScreen
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.noTitleUnsortedPreferenceCategory
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/shared/patches/SanitizeSharingLinksPatch;"

/**
 * Patch shared by YouTube and YT Music.
 */
internal fun sanitizeSharingLinksPatch(
    block: BytecodePatchBuilder.() -> Unit = {},
    executeBlock: BytecodePatchContext.() -> Unit = {},
    preferenceScreen: BasePreferenceScreen.Screen,
    replaceMusicLinksWithYouTube: Boolean = false,
    replaceLinksWithShortener: Boolean = false
) = bytecodePatch(
    name = "Sanitize sharing links",
    description = "Removes the tracking query parameters from shared links.",
) {
    block()

    execute {
        executeBlock()

        val sanitizePreference = SwitchPreference("morphe_sanitize_sharing_links", summary = true)

        preferenceScreen.addPreferences(
            if (replaceMusicLinksWithYouTube || replaceLinksWithShortener) {
                val preferences = mutableSetOf<BasePreference>(sanitizePreference)
                if (replaceMusicLinksWithYouTube) preferences += SwitchPreference("morphe_replace_music_with_youtube", summary = true)
                if (replaceLinksWithShortener) preferences += SwitchPreference("morphe_replace_links_with_shortener", summary = true)

                noTitleUnsortedPreferenceCategory(preferences)
            } else {
                sanitizePreference
            }
        )

        fun patchLogic(urlStringRegister: Int): String {
            return """
                invoke-static { v$urlStringRegister }, $EXTENSION_CLASS->sanitize(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v$urlStringRegister
            """
        }

        fun Fingerprint.hookUrlString(matchIndex: Int) {
            val index = instructionMatches[matchIndex].index
            val urlRegister = method.getInstruction<OneRegisterInstruction>(index).registerA

            method.addInstructions(
                index + 1,
                patchLogic(urlRegister)
            )
        }

        fun Fingerprint.hookIntentPutExtra(matchIndex: Int) {
            val index = instructionMatches[matchIndex].index
            val urlRegister = method.getInstruction<FiveRegisterInstruction>(index).registerE

            method.addInstructionsAtControlFlowLabel(
                index,
                patchLogic(urlRegister)
            )
        }

        // YouTube share sheet copy link.
        YouTubeCopyTextFingerprint.hookUrlString(0)

        // YouTube share sheet other apps.
        YouTubeShareSheetFingerprint.hookIntentPutExtra(3)

        // Native system share sheet.
        YouTubeSystemShareSheetFingerprint.hookIntentPutExtra(3)
    }
}