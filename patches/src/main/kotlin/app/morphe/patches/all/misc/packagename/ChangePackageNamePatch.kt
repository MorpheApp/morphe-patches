/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original code hard forked from:
 * https://github.com/ReVanced/revanced-patches/blob/724e6d61b2ecd868c1a9a37d465a688e83a74799/patches/src/main/kotlin/app/revanced/patches/all/misc/packagename/ChangePackageNamePatch.kt
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

package app.morphe.patches.all.misc.packagename

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.Option
import app.morphe.patcher.patch.OptionException
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import app.morphe.util.asSequence
import app.morphe.util.findElementByAttributeValue
import app.morphe.util.findInstructionIndicesReversed
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import org.w3c.dom.Element
import java.util.logging.Logger

private const val PACKAGE_NAME_REDDIT = "com.reddit.frontpage"

private lateinit var packageNameOption: Option<String>

/**
 * Set the package name to use.
 * If this is called multiple times, the first call will set the package name.
 *
 * @param fallbackPackageName The package name to use if the user has not already specified a package name.
 * @return The package name that was set.
 * @throws OptionException.ValueValidationException If the package name is invalid.
 */
fun setOrGetFallbackPackageName(fallbackPackageName: String): String {
    val packageName = packageNameOption.value!!

    return if (packageName == packageNameOption.default) {
        fallbackPackageName.also { packageNameOption.value = it }
    } else {
        packageName
    }
}

/**
 * Selectively changes usage of Context.getPackageName() to the original package name.
 */
context(patchContext: BytecodePatchContext)
private fun applyGetPackageName(oldPackageName: String, vararg classesToChange: String) {
    patchContext.classDefForEach { classDef ->
        if (!classesToChange.any { classToChange ->
                classDef.type.startsWith(classToChange)
            }
        ) return@classDefForEach

        val mutableClass by lazy {
            patchContext.mutableClassDefBy(classDef)
        }

        classDef.methods.forEach { method ->
            if (method.implementation == null) return@forEach

            val mutableMethod by lazy {
                mutableClass.findMutableMethodOf(method)
            }

            method.findInstructionIndicesReversed(
                methodCall(
                    opcode = Opcode.INVOKE_VIRTUAL,
                    smali = "Landroid/content/Context;->getPackageName()Ljava/lang/String;"
                )
            ).forEach { index ->
                val moveResultIndex = index + 1

                // Ignore calls to getPackageName() that do not use the return value.
                val returnInstruction = method.getInstruction(moveResultIndex)
                if (returnInstruction.opcode != Opcode.MOVE_RESULT_OBJECT) {
                    return@forEach
                }

                val register = (returnInstruction as OneRegisterInstruction).registerA
                mutableMethod.replaceInstruction(
                    moveResultIndex,
                    """
                        # Replace return-object with constant string
                        const-string v$register, "$oldPackageName"
                    """
                )
            }
        }
    }
}

@Suppress("unused")
val changePackageNamePatch = resourcePatch(
    name = "Change package name",
    description = "Appends \".morphe\" to the package name by default. " +
            "Changing the package name of the app can lead to unexpected issues.",
    default = false
) {
    packageNameOption = stringOption(
        key = "packageName",
        default = "Default",
        values = mapOf("Default" to "Default"),
        title = "Package name",
        description = "The name of the package to rename the app to.",
        required = true,
    ) {
        it == "Default" || it!!.matches(Regex("^[a-z]\\w*(\\.[a-z]\\w*)+$"))
    }

    val updatePermissionsOption = booleanOption(
        key = "updatePermissions",
        default = false,
        title = "Update permissions",
        description = "Update custom permissions declared by the app. " +
            "Enabling this can fix installation conflicts, but this can also break features in certain apps.",
    )

    val updateProvidersOption = booleanOption(
        key = "updateProviders",
        default = false,
        title = "Update providers",
        description = "Update provider names declared by the app. " +
            "Enabling this can fix installation conflicts, but this can also break features in certain apps.",
    )

    fun getReplacementPackageName(originalPackageName: String) : String {
        val replacementPackageName = packageNameOption.value
        return if (replacementPackageName != packageNameOption.default) {
            replacementPackageName!!
        } else {
            "$originalPackageName.morphe"
        }
    }

    dependsOn(bytecodePatch {
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
        )

        val packageName = packageMetadata.packageName
        val newPackageName = getReplacementPackageName(packageName)

        if (incompatibleAppPackages.contains(packageName)) {
            return@finalize Logger.getLogger(this::class.java.name).severe(
                "'$packageName' does not work correctly with \"Change package name\"",
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

        document("AndroidManifest.xml").use { document ->
            document.documentElement.setAttribute("package", newPackageName)

            if (applyUpdatePermissions) {
                val permissions = document.getElementsByTagName("permission")
                val usesPermissions = document.getElementsByTagName("uses-permission")

                permissions.asSequence().map { it as Element }.forEach {
                    val oldName = it.getAttribute("android:name")
                    val newName = when {
                        oldName.startsWith('.') -> return@forEach
                        oldName.startsWith("$packageName.") -> oldName.replaceFirst(packageName, newPackageName)
                        else -> "${newPackageName}_$oldName"
                    }
                    it.setAttribute("android:name", newName)

                    // Update a corresponding <uses-permission> as well if it exists
                    usesPermissions
                        .findElementByAttributeValue("android:name", oldName)
                        ?.setAttribute("android:name", newName)
                }
            }

            if (applyUpdateProviders) {
                val providers =
                    document.getElementsByTagName("provider").asSequence().map { it as Element }

                for (provider in providers) {
                    val authorities = provider.getAttribute("android:authorities").split(';')
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
            document("res/values/strings.xml").use { document ->
                val children = document.documentElement.childNodes
                for (i in 0 until children.length) {
                    val node = children.item(i) as? Element ?: continue

                    if (node.getAttribute("name") in providerStringResources) {
                        val authority = node.textContent
                        node.textContent = if (authority.startsWith("$packageName.")) {
                            authority.replaceFirst(packageName, newPackageName)
                        } else {
                            "${newPackageName}_$authority"
                        }
                    }
                }
            }
        }
    }
}
