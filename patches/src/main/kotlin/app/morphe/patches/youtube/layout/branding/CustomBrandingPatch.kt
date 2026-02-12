package app.morphe.patches.youtube.layout.branding

import app.morphe.patches.shared.layout.branding.baseCustomBrandingPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.gms.Constants.YOUTUBE_MAIN_ACTIVITY_NAME
import app.morphe.patches.youtube.misc.gms.Constants.YOUTUBE_PACKAGE_NAME
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.shared.YouTubeActivityOnCreateFingerprint

@Suppress("unused")
val customBrandingPatch = baseCustomBrandingPatch(
    originalLauncherIconName = "ic_launcher",
    originalAppName = "@string/application_name",
    originalAppPackageName = YOUTUBE_PACKAGE_NAME,
    isYouTubeMusic = false,
    numberOfPresetAppNames = 5,
    mainActivityOnCreateFingerprint = YouTubeActivityOnCreateFingerprint,
    mainActivityName = YOUTUBE_MAIN_ACTIVITY_NAME,
    activityAliasNameWithIntents = "com.google.android.youtube.app.honeycomb.Shell\$HomeActivity",
    preferenceScreen = PreferenceScreen.GENERAL_LAYOUT,

    block = {
        dependsOn(sharedExtensionPatch)

        compatibleWith(COMPATIBILITY_YOUTUBE)
    }
)
