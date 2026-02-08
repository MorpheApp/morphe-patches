package app.morphe.patches.all.misc.network

import app.morphe.patcher.patch.resourcePatch
import app.morphe.util.Utils.trimIndentMultiline
import app.morphe.util.adoptChild
import app.morphe.util.getNode
import org.w3c.dom.Element
import java.io.File

private const val NETWORK_SECURITY_CONFIG_ATTRIBUTE_NAME =
    "android:networkSecurityConfig"

@Suppress("unused")
val overrideCertificatePinningPatch = resourcePatch(
    name = "Override certificate pinning",
    description = "Overrides certificate pinning, allowing to inspect traffic via a proxy.",
    use = false,
) {
    execute {
        val resXmlDirectory = get("res/xml")
        var networkSecurityFileName = "network_security_config.xml"

        // Add android:networkSecurityConfig="@xml/network_security_config" and the "networkSecurityConfig" attribute if it does not exist.
        document("AndroidManifest.xml").use { document ->
            val applicationNode = document.getElementsByTagName("application").item(0) as Element

            if (applicationNode.hasAttribute(NETWORK_SECURITY_CONFIG_ATTRIBUTE_NAME)) {
                networkSecurityFileName =
                    applicationNode.getAttribute(NETWORK_SECURITY_CONFIG_ATTRIBUTE_NAME)
                        .split("/")[1] + ".xml"
            } else {
                document.createAttribute(NETWORK_SECURITY_CONFIG_ATTRIBUTE_NAME)
                    .apply { value = "@xml/network_security_config" }
                    .let(applicationNode.attributes::setNamedItem)
            }
        }

        if (resXmlDirectory.resolve(networkSecurityFileName).exists()) {
            document("res/xml/$networkSecurityFileName").use { document ->
                arrayOf(
                    "base-config",
                    "debug-overrides"
                ).forEach { tagName ->
                    val configElement = document.getNode(tagName) as Element
                    val configChildNodes = configElement.childNodes
                    for (i in 0 until configChildNodes.length) {
                        val anchorNode = configChildNodes.item(i)
                        if (anchorNode is Element && anchorNode.tagName == "trust-anchors") {
                            var injected = false
                            val certificatesChildNodes = anchorNode.childNodes
                            for (i in 0 until certificatesChildNodes.length) {
                                val node = certificatesChildNodes.item(i)
                                if (node is Element && node.tagName == "certificates") {
                                    if (node.hasAttribute("src") && node.getAttribute("src") == "user") {
                                        node.setAttribute("overridePins", "true")
                                        injected = true
                                    }
                                }
                            }
                            if (!injected) {
                                anchorNode.adoptChild("certificates") {
                                    setAttribute("src", "user")
                                    setAttribute("overridePins", "true")
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // In case the file does not exist create the "network_security_config.xml" file.
            File(resXmlDirectory, networkSecurityFileName).apply {
                writeText(
                    """
                    <?xml version="1.0" encoding="utf-8"?>
                    <network-security-config>
                        <base-config cleartextTrafficPermitted="true">
                            <trust-anchors>
                                <certificates src="system" />
                                <certificates
                                    src="user"
                                    overridePins="true" />
                            </trust-anchors>
                        </base-config>
                        <debug-overrides>
                            <trust-anchors>
                                <certificates src="system" />
                                <certificates
                                    src="user"
                                    overridePins="true" />
                            </trust-anchors>
                        </debug-overrides>
                    </network-security-config>
                    """.trimIndentMultiline(),
                )
            }
        }
    }
}
