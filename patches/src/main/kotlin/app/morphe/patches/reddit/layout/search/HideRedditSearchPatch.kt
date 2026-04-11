/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.patches.reddit.layout.search

import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.reddit.misc.fix.signature.spoofSignaturePatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.findElementByAttributeValueOrThrow

@Suppress("unused")
val hideRedditSearchPatch = resourcePatch(
    name = "Hide Reddit search",
    description = "Permanently hides the Reddit search in the contextual menu. " +
            "This patch does not work with root mounting",
    default = false
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(spoofSignaturePatch)

    execute {

        document("AndroidManifest.xml").use { document ->
            val contextualElement = document.childNodes.findElementByAttributeValueOrThrow(
                "android:name",
                "com.reddit.answers.sharing.AnswersTextSelectionActivity",
            )
            val intentFilter = contextualElement.getElementsByTagName("intent-filter")
            val mimeType = intentFilter.findElementByAttributeValueOrThrow(
                "android:mimeType",
                "text/plain",
            )

            mimeType.setAttribute("android:mimeType", "text/calendar")
        }
    }
}
