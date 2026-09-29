/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3384
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.originaltitles

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.shared.misc.litho.relayout.lithoRelayoutPatch
import app.morphe.patches.shared.misc.proto.hookElement
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.textcomponent.hookLithoSpannableString
import app.morphe.patches.shared.misc.textcomponent.lithoSpannableStringPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.proto.elementProtoParserHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.videoid.hookVideoId
import app.morphe.patches.youtube.video.videoid.videoIdPatch
import app.morphe.util.findFreeRegister
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/originaltitles/RestoreOriginalTitlesPatch;"

@Suppress("unused")
val restoreOriginalTitlesPatch = bytecodePatch(
    name = "Restore original titles",
    description = "Adds an option to show the original video titles and descriptions instead of the auto-translated ones.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        addResourcesPatch,
        elementProtoParserHookPatch,
        lithoSpannableStringPatch,
        lithoRelayoutPatch,
        videoIdPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.FEED.addPreferences(
            SwitchPreference("morphe_restore_original_titles", summary = true)
        )

        // Elements are parsed on the main thread, where the original title cannot be fetched.
        // The element hook finds the translated title and starts fetching the original title,
        // and the text hook replaces the title text when the element is laid out. Titles not yet
        // fetched are shown as loading, and the Litho views are laid out again when fetched.
        hookElement("$EXTENSION_CLASS->restoreOriginalTitle")
        hookLithoSpannableString(EXTENSION_CLASS)

        // The original description is fetched when the video is opened,
        // and replaced when the description panel is opened.
        hookVideoId("$EXTENSION_CLASS->newVideoLoaded(Ljava/lang/String;)V")

        // The playlist panel on the watch page does not use Litho.
        PlaylistPanelVideoBindFingerprint.matchAll().forEach {
            it.method.apply {
                val titleViewField = it.instructionMatches.first().instruction.getReference<FieldReference>()!!
                // Video id is stored in a field of the view holder.
                val videoIdIndex = it.instructionMatches.last().index
                val videoIdInstruction = getInstruction<TwoRegisterInstruction>(videoIdIndex)
                val videoIdRegister = videoIdInstruction.registerA
                val viewHolderRegister = videoIdInstruction.registerB
                val titleViewRegister = findFreeRegister(videoIdIndex + 1, videoIdRegister, viewHolderRegister)

                addInstructions(
                    videoIdIndex + 1,
                    """
                        iget-object v$titleViewRegister, v$viewHolderRegister, $titleViewField
                        invoke-static { v$titleViewRegister, v$videoIdRegister }, $EXTENSION_CLASS->restoreOriginalTitle(Landroid/widget/TextView;Ljava/lang/String;)V
                    """
                )
            }
        }

        // The next video of the collapsed playlist panel does not include the video id.
        NextVideoTitleViewFingerprint.matchAll().forEach {
            it.method.apply {
                val index = it.instructionMatches.last().index
                val register = getInstruction<TwoRegisterInstruction>(index).registerA

                addInstruction(
                    index + 1,
                    "invoke-static { v$register }, $EXTENSION_CLASS->restoreKnownTitles(Landroid/widget/TextView;)V"
                )
            }
        }
    }
}
