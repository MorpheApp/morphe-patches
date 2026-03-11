/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 */
package app.morphe.patches.all.misc.packagename

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.Option
import app.morphe.patcher.patch.OptionException
import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import app.morphe.util.asSequence
import app.morphe.util.findInstructionIndicesReversed
import app.morphe.util.findMutableMethodOf
import app.morphe.util.getNode
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
context(BytecodePatchContext)
private fun applyGetPackageName(oldPackageName: String, vararg classesToChange: String) {
    classDefForEach { classDef ->
        if (!classesToChange.any { classToChange ->
                classDef.type.startsWith(classToChange)
            }
        ) return@classDefForEach

        val mutableClass by lazy {
            mutableClassDefBy(classDef)
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


context(ResourcePatchContext)
private fun applyProvidersStrings(oldPackageName: String, newPackageName: String) {
    document("res/values/strings.xml").use { document ->
        val children = document.documentElement.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i) as? Element ?: continue

            node.textContent = when (node.getAttribute("name")) {
                "provider_authority_appdata", "provider_authority_file",
                "provider_authority_userdata", "provider_workmanager_init"
                    -> node.textContent.replace(oldPackageName, newPackageName)

                else -> continue
            }
        }
    }
}

@Suppress("unused")
val changePackageNamePatch = resourcePatch(
    name = "Change package name",
    description = "Appends \".morphe\" to the package name by default. " +
            "Changing the package name of the app can lead to unexpected issues.",
    use = false
) {
    packageNameOption = stringOption(
        key = "packageName",
        default = "Default",
        values = mapOf("Default" to "Default"),
        title = "Package name",
        description = "The name of the package to rename the app to.",
        required = true,
    ) {
        it == "Default" || it!!.matches(Regex("^[a-z]\\w*(\\.[a-z]\\w*)+\$"))
    }

    val updatePermissions = booleanOption(
        key = "updatePermissions",
        default = false,
        title = "Update permissions",
        description = "Update compatibility receiver permissions. " +
            "Enabling this can fix installation errors, but this can also break features in certain apps.",
    ).value

    val updateProviders = booleanOption(
        key = "updateProviders",
        default = false,
        title = "Update providers",
        description = "Update provider names declared by the app. " +
            "Enabling this can fix installation errors, but this can also break features in certain apps.",
    ).value

    val updateProvidersStrings = booleanOption(
        key = "updateProvidersStrings",
        default = false,
        title = "Update providers strings",
        description = "Update additional provider names declared by the app in the strings.xml file. " +
                "Enabling this can fix installation errors, but this can also break features in certain apps.",
    ).value

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
        val incompatibleAppPackages = setOf<String>()

        val packageName = packageMetadata.packageName
        val newPackageName = getReplacementPackageName(packageName)

        val applyUpdatePermissions : Boolean
        val applyUpdateProviders : Boolean
        val applyUpdateProvidersStrings : Boolean

        // Override user options with known working values for specific apps.
        when (packageName) {
            PACKAGE_NAME_REDDIT -> {
                applyUpdatePermissions = true
                applyUpdateProviders = true
                applyUpdateProvidersStrings = true
            }
            else -> {
                applyUpdatePermissions = updatePermissions!!
                applyUpdateProviders = updateProviders!!
                applyUpdateProvidersStrings = updateProvidersStrings!!
            }
        }

        if (applyUpdateProvidersStrings) {
            applyProvidersStrings(packageName, newPackageName)
        }

        document("AndroidManifest.xml").use { document ->
            val manifest = document.getNode("manifest") as Element

            if (incompatibleAppPackages.contains(packageName)) {
                return@finalize Logger.getLogger(this::class.java.name).severe(
                    "'$packageName' does not work correctly with \"Change package name\"",
                )
            }

            val newPackageName = getReplacementPackageName(packageName)
            manifest.setAttribute("package", newPackageName)

            if (applyUpdatePermissions) {
                val permissions = manifest.getElementsByTagName("permission").asSequence()
                val usesPermissions = manifest.getElementsByTagName("uses-permission").asSequence()

                val receiverNotExported = "DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
                val androidName = "android:name"
                val newName = "$packageName.$receiverNotExported"

                (permissions + usesPermissions)
                    .map { it as Element }
                    .filter {
                        it.getAttribute(androidName) == newName
                    }
                    .forEach {
                        it.setAttribute(androidName, newName)
                    }
            }

            if (applyUpdateProviders) {
                val providers = manifest.getElementsByTagName("provider").asSequence()

                val androidAuthority = "android:authorities"
                val authorityPrefix = "$packageName."

                for (node in providers) {
                    val provider = node as Element

                    val authorities = provider.getAttribute(androidAuthority)
                    if (authorities.startsWith(authorityPrefix)) {
                        provider.setAttribute(
                            androidAuthority,
                            authorities.replace(packageName, newPackageName)
                        )
                    }
                }
            }
        }
    }
}
