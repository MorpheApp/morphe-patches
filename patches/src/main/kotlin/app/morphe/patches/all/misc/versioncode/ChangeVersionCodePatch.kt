package app.morphe.patches.all.misc.versioncode

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import app.morphe.util.getNode
import org.w3c.dom.Element

private const val MAX_VALUE = Int.MAX_VALUE.toString()

@Suppress("unused")
val changeVersionCodePatch = resourcePatch(
//    name = "Change version code",
    description = "Changes the version code of the app to the value specified in patch options. " +
            "Except when mounting, this can prevent app stores from updating the app and allow " +
            "the app to be installed over an existing installation that has a higher version code. " +
            "By default, the highest version code is set.",
    use = false,
) {
    // TODO: Change this to always use the highest code because the lowest code will always show app store updates.
    val versionCodeOption = stringOption(
        key = "versionCode",
        default = MAX_VALUE,
        values = mapOf(
            "Lowest" to "1",
            "Highest" to MAX_VALUE,
        ),
        title = "Version code",
        description = "The version code to use. (1 ~ $MAX_VALUE)",
        required = true,
    )

    execute {
        fun throwVersionCodeException(versionCodeString: String): PatchException =
            PatchException(
                "Invalid versionCode: $versionCodeString, " +
                        "Version code should be larger than 1 and smaller than $MAX_VALUE."
            )

        val versionCodeString = versionCodeOption.value!!
        val versionCode: Int

        try {
            versionCode = Integer.parseInt(versionCodeString)
        } catch (e: NumberFormatException) {
            throw throwVersionCodeException(versionCodeString)
        }

        if (versionCode < 1) {
            throw throwVersionCodeException(versionCodeString)
        }

        document("AndroidManifest.xml").use { document ->
            val manifestElement = document.getNode("manifest") as Element
            manifestElement.setAttribute("android:versionCode", "$versionCode")
        }
    }
}