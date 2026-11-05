/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.search

import app.morphe.patcher.patch.resourceXml
import app.morphe.patcher.patch.patch
import app.morphe.patches.reddit.misc.fix.signature.spoofSignaturePatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT

@Suppress("unused")
val hideRedditSearchPatch = patch(
    name = "Hide Reddit search",
    description = "Permanently hides the Reddit search in the contextual menu. " +
            "This patch does not work with root mounting",
    default = false
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(spoofSignaturePatch)

    execute {

        resourceXml("AndroidManifest.xml").use { manifest ->
            val contextualElement = manifest.elementByAttribute(
                "android:name",
                "com.reddit.answers.sharing.AnswersTextSelectionActivity",
            )
            val mimeType = contextualElement.elementByAttribute("android:mimeType", "text/plain")

            mimeType.setAttribute("android:mimeType", "text/calendar")
        }
    }
}
