/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3862
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.feedviews

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.textcomponent.hookLithoSpannableString
import app.morphe.patches.shared.misc.textcomponent.lithoSpannableStringPatch
import app.morphe.patches.youtube.layout.hide.general.HideViewCountFingerprint
import app.morphe.patches.youtube.layout.hide.general.hideLayoutComponentsPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.feedcards.feedVideoCardsHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstruction
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/FeedViewCountPatch;"

@Suppress("unused")
val feedViewCountPatch = bytecodePatch(
    name = "Feed view count",
    description = "Adds the view count to the bottom left of the thumbnails of feed videos.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        addResourcesPatch,
        // Finds the video cards. The view count is drawn by the extension.
        feedVideoCardsHookPatch,
        // Its Hide view count option removes the view count from below the title.
        // It changes the same method first, so its indexes are not moved by this patch.
        hideLayoutComponentsPatch,
        // The view count is removed from the texts of the cards that show the badge,
        // known by the Litho path of the text.
        lithoSpannableStringPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.FEED.addPreferences(
            SwitchPreference("morphe_feed_view_count", summary = true),
        )

        // The text below the title of a video, such as "Channel · 4.7M views · 2 days ago".
        // Same method the Hide view count option of the Hide layout components patch changes.
        HideViewCountFingerprint.method.apply {
            // Indexes are found again, because the other patch added instructions.
            val stringIndex = indexOfFirstInstructionOrThrow {
                getReference<StringReference>()?.string ==
                        "Has attachmentRuns but drawableRequester is missing."
            }
            val applyDimensionIndex = indexOfFirstInstructionOrThrow(stringIndex) {
                val reference = getReference<MethodReference>()
                opcode == Opcode.INVOKE_STATIC &&
                        reference?.definingClass == "Landroid/util/TypedValue;" &&
                        reference.name == "applyDimension"
            }

            // The string passed to the span method is the returned string.
            // 21.42+ jumps to a shared return, so the register of a return instruction can't be used.
            val setSpanIndex = indexOfFirstInstructionOrThrow(applyDimensionIndex) {
                val reference = getReference<MethodReference>()
                opcode == Opcode.INVOKE_STATIC &&
                        reference?.returnType == "V" &&
                        reference.parameterTypes == listOf(
                            "Landroid/text/SpannableString;", "Ljava/lang/Object;", "I", "Z", "I"
                        )
            }
            val textRegister = getInstruction<FiveRegisterInstruction>(setSpanIndex).registerC

            // Before the Hide view count option of the other patch, which only knows older layouts.
            // The view count is attached to the text, and removed from it if that option is on.
            val hideViewCountIndex = indexOfFirstInstruction {
                getReference<MethodReference>()?.name == "modifyFeedSubtitleSpan"
            }
            val insertIndex = if (hideViewCountIndex >= 0) hideViewCountIndex else applyDimensionIndex

            addInstructions(
                insertIndex,
                """
                    invoke-static { v$textRegister }, $EXTENSION_CLASS->onFeedSubtitleCreated(Landroid/text/SpannableString;)Landroid/text/SpannableString;
                    move-result-object v$textRegister
                """
            )
        }

        hookLithoSpannableString(EXTENSION_CLASS)

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
