package app.morphe.patches.all.misc.debugging

import app.revanced.patcher.patch.resourcePatch
import org.w3c.dom.Element

@Suppress("unused")
internal val enableAndroidDebuggingPatch = resourcePatch(
    // name = "Enable Android debugging",
    description = "Enables Android developer debugging capabilities. Including this patch can slow down the app."
) {
    execute {
        document("AndroidManifest.xml").use { document ->
            val applicationNode =
                document
                    .getElementsByTagName("application")
                    .item(0) as Element

            // set application as debuggable
            applicationNode.setAttribute("android:debuggable", "true")
        }
    }
}
