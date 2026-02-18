/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */

package app.morphe.patches.all.misc.updates

import app.morphe.patcher.patch.resourcePatch
import app.morphe.util.getNode
import org.w3c.dom.Element

@Suppress("unused")
internal val disablePlayStoreUpdatesPatch = resourcePatch(
    name = "Disable Play Store updates",
    description = "Disables Play Store updates by setting the version code to the maximum allowed. " +
            "This can have unexpected issues in some apps.",
    use = false
) {
    finalize {
        document("AndroidManifest.xml").use { document ->
            val manifest = document.getNode("manifest") as Element

            // set version code to max allowed by Play Store
            manifest.setAttribute("android:versionCode", "2100000000")
        }
    }
}
