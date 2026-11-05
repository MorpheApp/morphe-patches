/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.branding.name

import app.morphe.patcher.patch.resourceXml
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.stringOption
import app.morphe.patches.reddit.misc.fix.signature.spoofSignaturePatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT

private const val APP_NAME = "Reddit Morphe"

@Suppress("unused")
val customBrandingNamePatch = patch(
    name = "Custom branding name for Reddit",
    description = "Changes the Reddit app name to the name specified in patch options.",
    default = false
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(spoofSignaturePatch)

    val appNameOption = stringOption(
        key = "appName",
        default = APP_NAME,
        values = mapOf(
            "Default" to APP_NAME
        ),
        title = "App name",
        description = "The name of the app.",
        required = true
    )

    execute {
        val appName = appNameOption.value!!

        // The configuration is added if the app does not have it.
        resourceXml("res/values-v24/strings.xml", copy = false).use { strings ->
            mapOf(
                "app_name" to appName
            ).forEach { (k, v) ->
                strings.root.appendChild("string") {
                    setAttribute("name", k)
                    text = v
                }
            }
        }
    }
}
