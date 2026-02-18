package app.morphe.patches.reddit.layout.branding.packagename

import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import app.morphe.patches.reddit.misc.fix.signature.spoofSignaturePatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import org.w3c.dom.Element

private const val PACKAGE_NAME_REDDIT = "com.reddit.frontpage"
private const val DEFAULT_PACKAGE_NAME_REDDIT = "$PACKAGE_NAME_REDDIT.morphe"

private var redditPackageName = PACKAGE_NAME_REDDIT

@Suppress("unused")
val changePackageNamePatch = resourcePatch(
    name = "Change package name",
    description = "Changes the package name for Reddit to the name specified in patch options.",
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(spoofSignaturePatch)

    val packageNameRedditOption = stringOption(
        key = "packageNameReddit",
        default = DEFAULT_PACKAGE_NAME_REDDIT,
        values = mapOf(
            "Default" to DEFAULT_PACKAGE_NAME_REDDIT
        ),
        title = "Package name of Reddit",
        description = "The name of the package to rename the app to.",
        required = true
    )

    execute {
        redditPackageName = packageNameRedditOption.value!!

        // replace strings
        document("res/values/strings.xml").use { document ->
            val resourcesNode = document.getElementsByTagName("resources").item(0) as Element

            val children = resourcesNode.childNodes
            for (i in 0 until children.length) {
                val node = children.item(i) as? Element ?: continue

                node.textContent = when (node.getAttribute("name")) {
                    "provider_authority_appdata", "provider_authority_file",
                    "provider_authority_userdata", "provider_workmanager_init"
                        -> node.textContent.replace(PACKAGE_NAME_REDDIT, redditPackageName)

                    else -> continue
                }
            }
        }

        // replace manifest permission and provider
        get("AndroidManifest.xml").apply {
            writeText(
                readText()
                    .replace(
                        "android:authorities=\"$PACKAGE_NAME_REDDIT",
                        "android:authorities=\"$redditPackageName"
                    )
            )
        }
    }

    finalize {
        get("AndroidManifest.xml").apply {
            writeText(
                readText()
                    .replace(
                        "package=\"$PACKAGE_NAME_REDDIT",
                        "package=\"$redditPackageName"
                    )
                    .replace(
                        "$PACKAGE_NAME_REDDIT.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
                        "$redditPackageName.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
                    )
            )
        }
    }
}
