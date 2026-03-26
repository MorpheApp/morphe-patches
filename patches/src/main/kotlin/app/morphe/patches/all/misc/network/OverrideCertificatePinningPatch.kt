/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original code hard forked from:
 * https://github.com/inotia00/revanced-patches/blob/54ce1d4808b12903602a1a0d9a721ee835093c38/patches/src/main/kotlin/app/revanced/patches/all/misc/network/OverrideCertificatePinningPatch.kt#L4
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

package app.morphe.patches.all.misc.network

import app.morphe.patcher.patch.resourcePatch
import app.morphe.util.adoptChild
import app.morphe.util.getNode
import app.morphe.util.trimIndentMultiline
import org.w3c.dom.Element
import java.io.File

private const val NETWORK_SECURITY_CONFIG_ATTRIBUTE_NAME = "android:networkSecurityConfig"

@Suppress("unused")
val overrideCertificatePinningPatch = resourcePatch(
    name = "Override certificate pinning",
    description = "Overrides certificate pinning, allowing to inspect traffic via a proxy.",
    default = false
) {
    execute {
        val resXmlDirectory = get("res/xml")
        var networkSecurityFileName = "network_security_config.xml"

        // Add android:networkSecurityConfig="@xml/network_security_config"
        // and the "networkSecurityConfig" attribute if it does not exist.
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
                    val configElement = document.getNode(tagName) as? Element ?: tagName.let {
                        document.getNode("network-security-config").adoptChild(tagName) {
                            if (tagName == "base-config") {
                                setAttribute("cleartextTrafficPermitted", "true")
                            }
                        }
                        document.getNode(tagName)
                    }
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
