package app.morphe.patches.youtube.misc.announcements

import app.revanced.patcher.extensions.InstructionExtensions.addInstruction
import app.revanced.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.resources.addResources
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.mainActivityOnCreateFingerprint

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/announcements/AnnouncementsPatch;"

val announcementsPatch = bytecodePatch(
    // FIXME: Change this to an "Update is available" patch
    //name = "Announcements",
    description = "Adds an option to show announcements from Morphe on app startup.",
) {
    dependsOn(
        settingsPatch,
        addResourcesPatch,
    )

    compatibleWith(
        "com.google.android.youtube"(
            "19.43.41",
            "20.14.43",
            "20.21.37",
            "20.31.42",
            "20.46.41",
        )
    )

    execute {
        addResources("youtube", "misc.announcements.announcementsPatch")

        PreferenceScreen.MISC.addPreferences(
            SwitchPreference("morphe_announcements"),
        )

        mainActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS_DESCRIPTOR->showAnnouncement(Landroid/app/Activity;)V",
        )
    }
}
