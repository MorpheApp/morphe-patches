/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
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

package app.morphe.patches.util.resource

import app.morphe.patcher.patch.resourceFile
import app.morphe.patcher.patch.resourceXml

/**
 * Copies an XML resource file of the app to a new path, without decoding the original.
 *
 * @param fromPath The path of the file to copy, such as `res/drawable/icon.xml`.
 * @param toPath The path of the copy, which must not exist yet.
 */
internal fun copyXmlResource(fromPath: String, toPath: String) {
    resourceXml(fromPath).use { source ->
        val target = resourceFile(toPath, false)
        target.parentFile.mkdirs()
        target.writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<${source.root.tag}/>\n")

        resourceXml(toPath).use { copy ->
            copy.root.copyAttributesFrom(source.root)
            source.root.children.forEach { copy.root.appendCopy(it) }
        }
    }
}
