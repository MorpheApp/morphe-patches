/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3387
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.feedrefresh

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/DisableAutoFeedRefreshPatch;"

@Suppress("unused")
val disableAutoFeedRefreshPatch = bytecodePatch(
    name = "Disable auto feed refresh",
    description = "Adds an option to stop feeds from refreshing automatically after they become outdated."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.FEED.addPreferences(
            SwitchPreference("morphe_disable_auto_feed_refresh", summary = true)
        )

        FeedExpirationFingerprint.let {
            it.method.apply {
                // Later field first, so the earlier match index stays valid.
                listOf(5, 3).forEach { matchIndex ->
                    val match = it.instructionMatches[matchIndex]
                    val register = match.getInstruction<OneRegisterInstruction>().registerA

                    addInstructions(
                        match.index,
                        """
                            invoke-static/range { v$register .. v${register + 1} }, $EXTENSION_CLASS->getFeedExpirationTime(J)J
                            move-result-wide v$register
                        """
                    )
                }
            }
        }
    }
}
