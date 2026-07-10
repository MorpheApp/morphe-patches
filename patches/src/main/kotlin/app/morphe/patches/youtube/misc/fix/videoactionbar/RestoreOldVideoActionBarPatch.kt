/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.misc.fix.videoactionbar

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.fix.proto.fixProtoLibraryPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.headerhook.addHeaderHook
import app.morphe.patches.youtube.misc.headerhook.cronetHeaderHookPatch
import app.morphe.patches.youtube.misc.request.buildRequestPatch
import app.morphe.patches.youtube.misc.request.hookBuildRequest
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.util.insertLiteralOverride

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/RestoreOldVideoActionBarPatch;"

internal val restoreOldVideoActionBarPatch = bytecodePatch(
    description = "Overrides 'X-Youtube-Cold-Config-Data', fixes 'Hide video action buttons' and 'Return YouTube Dislike', "
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        buildRequestPatch,
        cronetHeaderHookPatch,
        fixProtoLibraryPatch
    )

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_restore_old_video_action_bar", summary = true)
        )

        // If cold config data is overridden, the related video overlay breaks in fullscreen.
        // As a workaround for this, restore the old related video overlay.
        listOf(
            ModernRelateVideoOverlayFingerprint,
            RelateVideoOverlayLayoutParamFingerprint
        ).forEach { fingerprint ->
            fingerprint.let {
                it.method.insertLiteralOverride(
                    it.instructionMatches.first().index,
                    "$EXTENSION_CLASS->fixRelatedVideoOverlay(Z)Z"
                )
            }
        }

        addHeaderHook("$EXTENSION_CLASS->fixVideoActionBar(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;")
        hookBuildRequest("$EXTENSION_CLASS->fetchRequest(Ljava/lang/String;Ljava/util/Map;)V")
    }
}