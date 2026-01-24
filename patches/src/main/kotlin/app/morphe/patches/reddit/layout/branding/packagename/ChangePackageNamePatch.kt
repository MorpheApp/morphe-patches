package app.morphe.patches.reddit.layout.branding.packagename

import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import app.morphe.patches.reddit.utils.compatibility.Constants.COMPATIBLE_PACKAGE
import app.morphe.patches.reddit.utils.fix.signature.spoofSignaturePatch
import app.morphe.patches.reddit.utils.patch.PatchList.CHANGE_PACKAGE_NAME
import app.morphe.patches.reddit.utils.settings.updatePatchStatus
import org.w3c.dom.Element

private const val PACKAGE_NAME_REDDIT = "com.reddit.frontpage"
private const val CLONE_PACKAGE_NAME_REDDIT = "$PACKAGE_NAME_REDDIT.rvxmorphed"
private const val DEFAULT_PACKAGE_NAME_REDDIT = "$PACKAGE_NAME_REDDIT.rvx"

private var redditPackageName = PACKAGE_NAME_REDDIT

/*
// FIXME: Cannot use this patch due to apktool being unable to decode resources.
@Suppress("unused")
val changePackageNamePatch = resourcePatch(
    CHANGE_PACKAGE_NAME.title,
    CHANGE_PACKAGE_NAME.summary,
    false,
) {
    compatibleWith(COMPATIBLE_PACKAGE)

    dependsOn(spoofSignaturePatch)

    val packageNameRedditOption = stringOption(
        key = "packageNameReddit",
        default = PACKAGE_NAME_REDDIT,
        values = mapOf(
            "Clone" to CLONE_PACKAGE_NAME_REDDIT,
            "Default" to DEFAULT_PACKAGE_NAME_REDDIT,
            "Original" to PACKAGE_NAME_REDDIT,
        ),
        title = "Package name of Reddit",
        description = "The name of the package to rename the app to.",
        required = true
    )

    execute {
        fun replacePackageName() {
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

        redditPackageName = packageNameRedditOption
            .valueOrThrow()

        if (redditPackageName == PACKAGE_NAME_REDDIT) {
            printInfo("Package name will remain unchanged as it matches the original.")
            return@execute
        }

        // Ensure device runs Android.
        try {
            // RVX Manager
            // ====
            // For some reason, in Android AAPT2, a compilation error occurs when changing the [strings.xml] of the Reddit
            // This only affects RVX Manager, and has not yet found a valid workaround
            Class.forName("android.os.Environment")
        } catch (_: ClassNotFoundException) {
            // CLI
            replacePackageName()
        }

        updatePatchStatus(CHANGE_PACKAGE_NAME)
    }

    finalize {
        if (redditPackageName != PACKAGE_NAME_REDDIT) {
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
}
*/
