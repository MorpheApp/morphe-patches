package app.morphe.patches.reddit.layout.branding.name

import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import app.morphe.patches.reddit.utils.compatibility.Constants.COMPATIBILITY_REDDIT
import app.morphe.patches.reddit.utils.fix.signature.spoofSignaturePatch
import java.io.FileWriter
import java.nio.file.Files
import java.util.logging.Logger

private const val ORIGINAL_APP_NAME = "Reddit"
private const val APP_NAME = "Reddit Morphe"

@Suppress("unused")
val customBrandingNamePatch = resourcePatch(
    // FIXME: Cannot use this patch due to apktool being unable to decode resources.
    name = "Custom branding name for Reddit",
    description = "Changes the Reddit app name to the name specified in patch options.",
    use = false,
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(spoofSignaturePatch)

    val appNameOption = stringOption(
        key = "appName",
        default = ORIGINAL_APP_NAME,
        values = mapOf(
            "Default" to APP_NAME,
            "Original" to ORIGINAL_APP_NAME,
        ),
        title = "App name",
        description = "The name of the app.",
        required = true
    )

    execute {
        val appName = appNameOption.value!!

        if (appName == ORIGINAL_APP_NAME) {
            Logger.getLogger(this::class.java.name).info(
                "App name will remain unchanged as it matches the original."
            )
            return@execute
        }

        val resDirectory = get("resources/package_1/res")

        val valuesV24Directory = resDirectory.resolve("values-v24")
        if (!valuesV24Directory.isDirectory)
            Files.createDirectories(valuesV24Directory.toPath())

        val stringsXml = valuesV24Directory.resolve("strings.xml")

        if (!stringsXml.exists()) {
            FileWriter(stringsXml).use {
                it.write("<?xml version=\"1.0\" encoding=\"utf-8\"?><resources></resources>")
            }
        }

        document("resources/package_1/res/values-v24/strings.xml").use { document ->
            mapOf(
                "app_name" to appName
            ).forEach { (k, v) ->
                val stringElement = document.createElement("string")

                stringElement.setAttribute("name", k)
                stringElement.textContent = v

                document.getElementsByTagName("resources").item(0).appendChild(stringElement)
            }
        }
    }
}
