/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original code hard forked from:
 * https://github.com/ReVanced/revanced-patches/blob/724e6d61b2ecd868c1a9a37d465a688e83a74799/patches/src/main/kotlin/app/revanced/patches/all/misc/versioncode/ChangeVersionCodePatch.kt
 *
 * File-Specific License Notice (GPLv3 Section 7 Terms)
 *
 * This file is part of the Morphe patches project and is licensed under
 * the GNU General Public License version 3 (GPLv3), with the Additional
 * Terms under Section 7 described in the Morphe patches
 * LICENSE file: https://github.com/MorpheApp/morphe-patches/blob/main/NOTICE
 *
 * https://www.gnu.org/licenses/gpl-3.0.html
 *
 * File-Specific Exception to Section 7b:
 * -------------------------------------
 * Section 7b (Attribution Requirement) of the Morphe patches LICENSE
 * does not apply to THIS FILE. Use of this file does NOT require any
 * user-facing, in-application, or UI-visible attribution.
 *
 * For this file only, attribution under Section 7b is satisfied by
 * retaining this comment block in the source code of this file.
 *
 * Distribution and Derivative Works:
 * ----------------------------------
 * This comment block MUST be preserved in all copies, distributions,
 * and derivative works of this file, whether in source or modified
 * form.
 *
 * All other terms of the Morphe Patches LICENSE, including Section 7c
 * (Project Name Restriction) and the GPLv3 itself, remain fully
 * applicable to this file.
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
    default = false
) {
    finalize {
        document("AndroidManifest.xml").use { document ->
            val manifest = document.getNode("manifest") as Element

            //  Max allowed by Play Store is 2100000000, but Android allows max int value.
            manifest.setAttribute("android:versionCode", Int.MAX_VALUE.toString())
        }
    }
}
