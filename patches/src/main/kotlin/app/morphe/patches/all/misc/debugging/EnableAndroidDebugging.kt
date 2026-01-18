package app.morphe.patches.all.misc.debugging

import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

@Suppress("unused")
internal val enableAndroidDebuggingPatch = resourcePatch(
    // name = "Enable Android debugging"
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
