/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original code hard forked from:
 * https://github.com/inotia00/revanced-patches/blob/54ce1d4808b12903602a1a0d9a721ee835093c38/patches/src/main/kotlin/app/revanced/patches/all/misc/network/OverrideCertificatePinningPatch.kt#L4
 *
 * File-Specific License Notice (GPLv3 Section 7 Terms)
 *
 * This file is part of the Morphe project and is licensed under
 * the GNU General Public License version 3 (GPLv3), with the Additional
 * Terms under Section 7 described in the LICENSE file.
 *
 * https://www.gnu.org/licenses/gpl-3.0.html
 *
 * Section 7b: Notice Preservation
 * -------------------------------
 * This entire comment block must be preserved in all copies,
 * distributions, and derivative works of this file, in both
 * original and modified source forms.
 *
 * Portions of this software are provided "AS IS" by the Morphe software project.
 * Any express or implied warranties, including the implied warranties of
 * merchantability and fitness for a particular purpose, are disclaimed.
 */

package app.morphe.patches.all.misc.network

import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceFile
import app.morphe.patcher.patch.resourceXml
import app.morphe.patcher.patch.resourceXmlOrNull
import app.morphe.util.trimIndentMultiline
import java.io.File

private const val NETWORK_SECURITY_CONFIG_ATTRIBUTE_NAME = "android:networkSecurityConfig"

@Suppress("unused")
val overrideCertificatePinningPatch = patch(
    name = "Override certificate pinning",
    description = "Overrides certificate pinning, allowing to inspect traffic via a proxy.",
    default = false
) {
    execute {
        val resXmlDirectory = resourceFile("res/xml")
        var networkSecurityFileName = "network_security_config.xml"

        // Add android:networkSecurityConfig="@xml/network_security_config"
        // and the "networkSecurityConfig" attribute if it does not exist.
        resourceXml("AndroidManifest.xml").use { manifest ->
            val application = manifest.elements("application").first()
            val config = application.attribute(NETWORK_SECURITY_CONFIG_ATTRIBUTE_NAME)

            if (config != null) {
                networkSecurityFileName = config.split("/")[1] + ".xml"
            } else {
                application.setAttribute(NETWORK_SECURITY_CONFIG_ATTRIBUTE_NAME, "@xml/network_security_config")
            }
        }

        val networkSecurityConfig = resourceXmlOrNull("res/xml/$networkSecurityFileName")
        if (networkSecurityConfig != null) {
            networkSecurityConfig.use { config ->
                arrayOf(
                    "base-config",
                    "debug-overrides"
                ).forEach { tagName ->
                    val configElement = config.elements(tagName).firstOrNull()
                        ?: config.elements("network-security-config").first().appendChild(tagName) {
                            if (tagName == "base-config") {
                                setAttribute("cleartextTrafficPermitted", "true")
                            }
                        }

                    configElement.children("trust-anchors").forEach { anchor ->
                        var injected = false
                        anchor.children("certificates").forEach { certificates ->
                            if (certificates.attribute("src") == "user") {
                                certificates.setAttribute("overridePins", "true")
                                injected = true
                            }
                        }
                        if (!injected) {
                            anchor.appendChild("certificates") {
                                setAttribute("src", "user")
                                setAttribute("overridePins", "true")
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
                    """.trimIndentMultiline() // Must trim indent.
                )
            }
        }
    }
}
