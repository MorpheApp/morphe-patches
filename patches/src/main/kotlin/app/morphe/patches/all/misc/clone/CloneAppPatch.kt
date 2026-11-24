/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original code hard forked from:
 * https://github.com/ReVanced/revanced-patches/blob/724e6d61b2ecd868c1a9a37d465a688e83a74799/patches/src/main/kotlin/app/revanced/patches/all/misc/packagename/ChangePackageNamePatch.kt
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

package app.morphe.patches.all.misc.clone

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.Option
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.packageMetadata
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceXml
import app.morphe.patcher.patch.stringOption
import app.morphe.patcher.util.matchAllMethodIndicesForEach
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import java.util.logging.Logger

private const val PACKAGE_NAME_REDDIT = "com.reddit.frontpage"

/**
 * Android package name rules: at least two segments separated by dots,
 * each segment starts with a letter and contains only letters, digits and underscores.
 */
private val VALID_PACKAGE_NAME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
private val WHITESPACE_REGEX = Regex("\\s+")
private val INVALID_PACKAGE_NAME_CHARACTER_REGEX = Regex("[^a-zA-Z0-9_]")

/**
 * Prefix added to a package name segment that does not start with a letter.
 */
private const val FIXED_SEGMENT_PREFIX = "app"

/**
 * Segment appended to a package name that needed fixes other than replacing whitespace.
 */
private const val FIXED_PACKAGE_NAME_SUFFIX = "fixedname"

private lateinit var packageNameOption: Option<String>

/**
 * Fixes a package name that does not follow the Android package name rules.
 * Whitespace is replaced with underscores and empty segments are removed.
 * Other invalid characters are replaced with underscores, and segments not starting
 * with a letter are given a prefix. If any of those fixes were needed, or the name
 * has only one segment, then ".fixedname" is appended.
 *
 * @return The package name if it is already valid, otherwise the fixed package name.
 */
internal fun fixPackageName(packageName: String): String {
    if (packageName.matches(VALID_PACKAGE_NAME_REGEX)) return packageName

    var appendSuffix = false

    val segments = packageName.split('.').mapNotNull { segment ->
        var fixedSegment = segment.trim().replace(WHITESPACE_REGEX, "_")
        if (fixedSegment.isEmpty()) return@mapNotNull null

        if (fixedSegment.contains(INVALID_PACKAGE_NAME_CHARACTER_REGEX)) {
            fixedSegment = fixedSegment.replace(INVALID_PACKAGE_NAME_CHARACTER_REGEX, "_")
            appendSuffix = true
        }

        if (!fixedSegment.first().isAsciiLetter()) {
            fixedSegment = FIXED_SEGMENT_PREFIX + fixedSegment
            appendSuffix = true
        }

        fixedSegment
    }

    if (appendSuffix || segments.size < 2) {
        return (segments + FIXED_PACKAGE_NAME_SUFFIX).joinToString(".")
    }

    return segments.joinToString(".")
}

private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

/**
 * @return The package name option value. If the user provided package name is not valid,
 *         it is fixed, a warning is logged, and the option is updated to the fixed name.
 */
private fun getPackageNameOptionValue(): String {
    val packageName = packageNameOption.value!!
    if (packageName == packageNameOption.default) return packageName

    val fixedPackageName = fixPackageName(packageName)
    if (fixedPackageName != packageName) {
        Logger.getLogger(cloneAppPatch::class.java.name).warning(
            "Package name \"$packageName\" is not a valid Android package name. " +
                    "Using \"$fixedPackageName\" instead."
        )
        packageNameOption.value = fixedPackageName
    }

    return fixedPackageName
}

/**
 * Set the package name to use.
 * If this is called multiple times, the first call will set the package name.
 *
 * @param fallbackPackageName The package name to use if the user has not already specified a package name.
 * @return The package name that was set.
 */
fun setOrGetFallbackPackageName(fallbackPackageName: String): String {
    val packageName = getPackageNameOptionValue()

    return if (packageName == packageNameOption.default) {
        fallbackPackageName.also { packageNameOption.value = it }
    } else {
        packageName
    }
}

/**
 * Selectively changes usage of Context.getPackageName() to the original package name.
 */
@Suppress("SameParameterValue")
private fun applyGetPackageName(oldPackageName: String, classToChange: String) {
    val filter = methodCall(
        opcode = Opcode.INVOKE_VIRTUAL,
        smali = "Landroid/content/Context;->getPackageName()Ljava/lang/String;"
    )

    // Same code as morphe-patches-library matchAllMethodIndicesForEach()
    // but copied here to make this patch more portable.
    Fingerprint(
        definingClass = classToChange,
        filters = listOf(filter)
    ).matchAllMethodIndicesForEach { index ->
        val moveResultIndex = index + 1

        // Ignore calls to getPackageName() that do not use the return value.
        val returnInstruction = getInstruction(moveResultIndex)
        if (returnInstruction.opcode != Opcode.MOVE_RESULT_OBJECT) {
            return@matchAllMethodIndicesForEach
        }

        val register = (returnInstruction as OneRegisterInstruction).registerA
        replaceInstruction(
            moveResultIndex,
            """
                # Replace return-object with constant string
                const-string v$register, "$oldPackageName"
            """
        )
    }
}

val cloneAppPatch = patch(
    name = "Clone app",
    description = "Changes the app package name to allow installing the same app multiple times. " +
            "By default \".morphe\" is appended to the package name. Each cloned install must " +
            "use a unique package name. Cloning does not work with all apps and using this patch " +
            "may cause app crashes or other unexpected behavior.",
    default = false
) {
    packageNameOption = stringOption(
        key = "packageName",
        default = "Default",
        values = mapOf("Default" to "Default"),
        title = "Package name",
        description = "Package name to use for the cloned app.",
        required = true,
    ) {
        // Invalid package names are fixed during patching. Only reject names with no usable text.
        it!!.split('.').any { segment -> segment.isNotBlank() }
    }

    val updatePermissionsOption = booleanOption(
        key = "updatePermissions",
        default = true,
        title = "Update permissions",
        description = "Update custom permissions declared by the app. " +
            "Fixes installation conflicts, but can break features in certain apps.",
    )

    val updateProvidersOption = booleanOption(
        key = "updateProviders",
        default = true,
        title = "Update providers",
        description = "Update provider names declared by the app. " +
            "Fixes installation conflicts, but can break features in certain apps.",
    )

    fun getReplacementPackageName(originalPackageName: String) : String {
        val replacementPackageName = getPackageNameOptionValue()
        return if (replacementPackageName != packageNameOption.default) {
            replacementPackageName
        } else {
            "$originalPackageName.morphe"
        }
    }

    dependsOn(patch {
        execute {
            when (val originalPackageName = packageMetadata.packageName) {
                PACKAGE_NAME_REDDIT -> {
                    applyGetPackageName(
                        originalPackageName,
                        "Lcom/google/android/recaptcha/internal"
                    )
                }
            }
        }
    })

    finalize {
        /**
         * Apps that are confirmed to not work correctly with this patch.
         * This is not an exhaustive list, and is only the apps with
         * Morphe specific patches and are confirmed incompatible with this patch.
         */
        val incompatibleAppPackages = setOf(
            // Crashes when opening posts
            "com.twitter.android",

            // Video stories only play audios
            "com.instagram.android",

            // Patches and installs but crashes on launch.
            "com.duolingo",
            "tv.twitch.android.app",

            // Resources fail to decode correctly.
            // https://github.com/MorpheApp/morphe-patcher/issues/178
            "cn.wps.moffice_eng",
        )

        val packageName = packageMetadata.packageName
        val newPackageName = getReplacementPackageName(packageName)

        if (incompatibleAppPackages.contains(packageName)) {
            return@finalize Logger.getLogger(this::class.java.name).severe(
                "'$packageName' does not work correctly with \"Clone app\", and no changes were made.",
            )
        }

        val applyUpdatePermissions : Boolean
        val applyUpdateProviders : Boolean

        // Override user options with known working values for specific apps.
        when (packageName) {
            PACKAGE_NAME_REDDIT -> {
                applyUpdatePermissions = true
                applyUpdateProviders = true
            }
            "com.google.android.youtube", "com.google.android.apps.youtube.music",
            "com.google.android.apps.photos", "com.google.android.apps.magazines" -> {
                // Permissions and providers are updated by "GmsCore support" patch
                applyUpdatePermissions = false
                applyUpdateProviders = false
            }
            else -> {
                applyUpdatePermissions = updatePermissionsOption.value!!
                applyUpdateProviders = updateProvidersOption.value!!
            }
        }

        val providerStringResources = mutableSetOf<String>()

        resourceXml("AndroidManifest.xml").use { manifest ->
            manifest.root.setAttribute("package", newPackageName)

            if (applyUpdatePermissions) {
                val usesPermissions = manifest.elements("uses-permission").toList()

                manifest.elements("permission").forEach {
                    val oldName = it.attribute("android:name").orEmpty()
                    val newName = when {
                        oldName.startsWith('.') -> return@forEach
                        oldName.startsWith("$packageName.") -> oldName.replaceFirst(packageName, newPackageName)
                        else -> "${newPackageName}_$oldName"
                    }
                    it.setAttribute("android:name", newName)

                    // Update a corresponding <uses-permission> as well if it exists
                    usesPermissions.firstOrNull { usesPermission -> usesPermission.attribute("android:name") == oldName }
                        ?.setAttribute("android:name", newName)
                }
            }

            if (applyUpdateProviders) {
                for (provider in manifest.elements("provider")) {
                    val authorities = provider.attribute("android:authorities").orEmpty().split(';')
                    val newAuthorities = authorities.map {
                        when {
                            it.startsWith("$packageName.") -> it.replaceFirst(packageName, newPackageName)
                            it.startsWith('@') -> {
                                providerStringResources.add(it.removePrefix("@string/"))
                                it
                            }
                            else -> "${newPackageName}_$it"
                        }
                    }
                    provider.setAttribute("android:authorities", newAuthorities.joinToString(";"))
                }
            }
        }

        if (providerStringResources.isNotEmpty()) {
            resourceXml("res/values/strings.xml").use { strings ->
                providerStringResources.forEach { name ->
                    val string = strings.resourceOrNull(name) ?: return@forEach
                    val authority = string.text
                    if (authority.isEmpty()) return@forEach

                    string.text = if (authority.startsWith("$packageName.")) {
                        authority.replaceFirst(packageName, newPackageName)
                    } else {
                        "${newPackageName}_$authority"
                    }
                }
            }
        }
    }
}
