package app.morphe.patches.all.misc.display.edgetoedge

import app.morphe.patcher.patch.resourcePatch
import app.morphe.util.getNode
import app.morphe.util.resource.StringResource
import org.w3c.dom.Element
import java.util.logging.Logger

@Suppress("unused")
val edgeToEdgeDisplayPatch = resourcePatch(
//    name = "Disable edge-to-edge display",
    description = "Disable forced edge-to-edge display on Android 15+ by changing the app's target SDK version. " +
            "This patch does not work if the app is installed by mounting.",
    use = false,
) {
    execute {
        document("AndroidManifest.xml").use { document ->
            fun printWarn(message: String) = Logger.getLogger(StringResource::class.java.name)
                .warning(message)

            // Ideally, the patch should only be applied when targetSdkVersion is 35 or greater.
            // Since ApkTool does not add targetSdkVersion to AndroidManifest, there is no way to check targetSdkVersion.
            // Instead, it checks compileSdkVersion and prints a warning.
            try {
                val manifestElement = document.getNode("manifest") as Element
                val compileSdkVersion =
                    Integer.parseInt(manifestElement.getAttribute("android:compileSdkVersion"))
                if (compileSdkVersion < 35) {
                    printWarn("This app may not be forcing edge to edge display (compileSdkVersion: $compileSdkVersion)")
                }
            } catch (_: Exception) {
                printWarn("Failed to check compileSdkVersion")
            }

            // Change targetSdkVersion to 34.
            document.getElementsByTagName("manifest").item(0).also {
                it.appendChild(it.ownerDocument.createElement("uses-sdk").also { element ->
                    element.setAttribute("android:targetSdkVersion", "34")
                })
            }
        }
    }
}
