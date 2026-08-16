/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2470
 *
 * Original code hard forked from:
 * https://github.com/ReVanced/revanced-patches/blob/724e6d61b2ecd868c1a9a37d465a688e83a74799/patches/src/main/kotlin/app/revanced/patches/all/misc/versioncode/ChangeVersionCodePatch.kt
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

package app.morphe.patches.all.misc.updates

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructionsOutsideTryBlock
import app.morphe.util.getNode
import app.morphe.util.matchAllMethodIndicesForEach
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import org.w3c.dom.Element

private const val EXTENSION_CLASS = "Lapp/morphe/extension/all/versioncode/DisablePlayStoreUpdatesPatch;"

private var originalVersionCode: Int = 0

@Suppress("unused")
private val disablePlayStoreUpdatesResourcePatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { document ->
            val manifest = document.getNode("manifest") as Element

            originalVersionCode = manifest.getAttribute("android:versionCode").toInt()
        }
    }

    finalize {
        document("AndroidManifest.xml").use { document ->
            val manifest = document.getNode("manifest") as Element

            //  Max allowed by Play Store is 2100000000, but Android allows max int value.
            manifest.setAttribute("android:versionCode", Int.MAX_VALUE.toString())
        }
    }
}

@Suppress("unused")
internal val disablePlayStoreUpdatesPatch = bytecodePatch(
    name = "Disable Play Store updates",
    description = "Disables Play Store updates by setting the version code to the maximum allowed. " +
            "This patch may cause unexpected issues with some apps and does not work if the " +
            "app is installed by root mounting",
    default = false
) {

    dependsOn(disablePlayStoreUpdatesResourcePatch)

    extendWith("extensions/all/versioncode/change-version-code.mpe")

    finalize {
        Fingerprint(
            definingClass = EXTENSION_CLASS,
            name = "originalVersionCode"
        ).method.returnEarly(originalVersionCode)

        fun MutableMethod.addExtensionCall(index: Int, isLong: Boolean) {
            val instruction = getInstruction<OneRegisterInstruction>(index)
            val register = instruction.registerA
            val smali = if (isLong) {
                """
                    invoke-static/range { v$register .. v${register + 1} }, $EXTENSION_CLASS->getVersionCode(J)J
                    move-result-wide v$register
                """
            } else {
                """
                    invoke-static/range { v$register .. v$register }, $EXTENSION_CLASS->getVersionCode(I)I
                    move-result v$register
                """
            }

            // Must add outside try block.
            addInstructionsOutsideTryBlock(
                index + 1,
                smali
            )
        }

        fieldAccess(
            opcode = Opcode.IGET,
            smali = "Landroid/content/pm/PackageInfo;->versionCode:I"
        ).matchAllMethodIndicesForEach(requireMatches = false) { index ->
            addExtensionCall(index, isLong = false)
        }

        // Check long version code, which is a combination of
        // regular version code and versionCodeMajor.
        methodCall(
            opcode = Opcode.INVOKE_VIRTUAL,
            smali = "Landroid/content/pm/PackageInfo;->getLongVersionCode()J"
        ).matchAllMethodIndicesForEach(requireMatches = false) { index ->
            val moveResultIndex = index + 1
            val instruction = getInstruction(moveResultIndex)
            if (instruction.opcode != Opcode.MOVE_RESULT_WIDE) {
                return@matchAllMethodIndicesForEach
            }

            addExtensionCall(moveResultIndex, isLong = true)
        }
    }
}
