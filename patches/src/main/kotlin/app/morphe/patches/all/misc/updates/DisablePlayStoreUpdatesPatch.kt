/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.patches.all.misc.updates

import app.morphe.patcher.patch.resourcePatch
import app.morphe.util.getNode
import org.w3c.dom.Element

@Suppress("unused")
internal val disablePlayStoreUpdatesPatch = resourcePatch(
    name = "Disable Play Store updates",
    description = "Disables Play Store updates by setting the version code to the maximum allowed. " +
            "This patch does not work if the app is installed by mounting and may cause unexpected " +
            "issues with some apps.",
    use = false
) {
    finalize {
        document("AndroidManifest.xml").use { document ->
            val manifest = document.getNode("manifest") as Element

            //  Max allowed by Play Store is 2100000000, but Android allows max int value.
            manifest.setAttribute("android:versionCode", Int.MAX_VALUE.toString())
        }
    }
}
