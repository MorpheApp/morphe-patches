package app.morphe.util.resource

import app.morphe.patches.all.misc.resources.locales
import app.morphe.util.inputStreamFromBundledResource
import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Checks resource strings for invalid strings that will fail resource compilation.
 */
internal fun main(args: Array<String>) {
    var stringsChecked = 0

    arrayOf(
        "music",
        "shared",
        "shared-youtube",
        "youtube"
    ).forEach { appId ->
        locales.forEach { locale ->
            val srcFolderName = locale.getSrcLocaleFolderName()
            val srcSubPath = "$srcFolderName/$appId/strings.xml"

            inputStreamFromBundledResource(
                "addresources", srcSubPath
            ).use { stream ->
                val document = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder()
                    .parse(stream)

                val nodeList = document.getElementsByTagName("string")
                for (i in 0 until nodeList.length) {
                    val node = nodeList.item(i)
                    if (node.nodeType == Node.ELEMENT_NODE) {
                        val element = node as Element
                        val name = element.getAttribute("name")
                        val value = element.textContent
                        StringResource.sanitizeAndroidResourceString(name, value, true)
                        stringsChecked++
                    }
                }
            }
        }
    }

    println("Checked $stringsChecked strings")
}
