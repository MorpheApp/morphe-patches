package app.morphe.patches.all.misc.debugging

import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceXml

@Suppress("unused")
internal val enableAndroidDebuggingPatch = patch(
    // name = "Enable Android debugging",
    description = "Enables Android developer debugging capabilities. Including this patch can slow down the app.",
    default = true
) {
    execute {
        resourceXml("AndroidManifest.xml").use { manifest ->
            // set application as debuggable
            manifest.elements("application").first().setAttribute("android:debuggable", "true")
        }
    }
}
