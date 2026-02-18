package app.morphe.patches.all.misc.versioncode

import app.morphe.patcher.patch.intOption
import app.morphe.patcher.patch.resourcePatch
import app.morphe.util.getNode
import org.w3c.dom.Element

internal val changeVersionCodePatch = resourcePatch(
    name = "Change version code",
    description = "Changes the version code of the app. This can be useful to prevent Play Store updates, but can also lead to unexpected issues.",
    use = false
) {
    val versionCodeOption by intOption(
        key = "versionCode",
        default = 2100000000,
        title = "Version code",
        description = "The new version code to set for the app.",
        required = true
    )

    finalize {
        document("AndroidManifest.xml").use { document ->
            val manifest = document.getNode("manifest") as Element

            // set version code to max allowed by Play Store
            manifest.setAttribute("android:versionCode", "$versionCodeOption")
        }
    }
}
