/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.notifications

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_21_42_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/ShortsNotificationsPatch;"

private val shortsNotificationsCompatibility = Compatibility(
    packageName = "com.google.android.youtube",
    name = "YouTube",
    apkFileType = ApkFileType.APK_REQUIRED,
    appIconColor = 0xFF0033,
    signatures = COMPATIBILITY_YOUTUBE.signatures,
    targets = COMPATIBILITY_YOUTUBE.targets.filter { it.version in setOf("21.43.523", "21.42.241", "21.30.216") },
)

internal object FcmMessageListenerFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/app/common/notification/FcmMessageListenerService;",
    filters = listOf(
        string("GCM_DATA_RECEIVED"),
        methodCall(
            name = "h",
            parameters = listOf("Landroid/content/Context;", "L"),
            returnType = "V"
        )
    )
)

internal object LegacyFcmMessageListenerFingerprint : Fingerprint(
    definingClass = "Lcom/google/android/apps/youtube/app/common/notification/FcmMessageListenerService;",
    filters = listOf(
        string("GCM_DATA_RECEIVED"),
        methodCall(
            name = "e",
            parameters = listOf("Landroid/content/Context;", "L"),
            returnType = "V"
        )
    )
)

@Suppress("unused")
val shortsNotificationsPatch = bytecodePatch(
    name = "Disable Shorts notifications",
    description = "Adds an option to filter Shorts from YouTube push notifications."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        versionCheckPatch,
    )

    compatibleWith(shortsNotificationsCompatibility)

    execute {
        PreferenceScreen.SHORTS.addPreferences(
            SwitchPreference("morphe_disable_shorts_notifications")
        )

        val fingerprint = if (is_21_42_or_greater) {
            FcmMessageListenerFingerprint
        } else {
            LegacyFcmMessageListenerFingerprint
        }
        val notificationHandler = fingerprint.instructionMatches[1].getMethodCalled()
        val register = notificationHandler.findFreeRegister(0)
        notificationHandler.addInstructionsWithLabels(
            0,
            """
                invoke-static { p2 }, $EXTENSION_CLASS->shouldSuppressShortsNotification(Ljava/lang/Object;)Z
                move-result v$register
                if-eqz v$register, :morphe_shorts_notifications_continue
                return-void
                :morphe_shorts_notifications_continue
                nop
            """
        )
    }
}
