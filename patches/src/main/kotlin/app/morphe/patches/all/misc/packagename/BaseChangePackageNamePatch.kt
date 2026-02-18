/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-cli
 */

package app.morphe.patches.all.misc.packagename

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstructions
import app.morphe.patcher.patch.Option
import app.morphe.patcher.patch.OptionException
import app.morphe.patcher.patch.ResourcePatchBuilder
import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import app.morphe.util.asSequence
import app.morphe.util.findMutableMethodOf
import app.morphe.util.getNode
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstruction
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.w3c.dom.Element
import java.util.logging.Logger

lateinit var packageNameOption: Option<String>

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

private fun getPackageNamePatch(newPackageName: String) = bytecodePatch {
    execute {
        classDefForEach { classDef ->
            classDef.methods.forEach { method ->
                val index = method.indexOfFirstInstruction {
                    if (opcode != Opcode.INVOKE_VIRTUAL) return@indexOfFirstInstruction false

                    val methodReference = getReference<MethodReference>()!!
                    if (methodReference.definingClass != "Landroid/content/Context;") return@indexOfFirstInstruction false
                    return@indexOfFirstInstruction methodReference.name == "getPackageName"
                }

                if (index == -1) return@forEach

                val register = (method.getInstruction(index + 1) as OneRegisterInstruction).registerA
                mutableClassDefBy(method.definingClass)
                    .findMutableMethodOf(method)
                    .replaceInstructions(
                        index,
                        """
                            nop
                            const-string v$register, "$newPackageName"
                        """
                    )
            }
        }
    }
}

fun baseChangePackageNamePatch(
    name: String,
    description: String,
    shouldUpdatePermissions: Boolean? = null,
    shouldUpdateProviders: Boolean? = null,
    shouldPatchGetPackageName: Boolean? = null,
    block: ResourcePatchBuilder.() -> Unit = {},
    executeBlock: ResourcePatchContext.(newPackageName: String) -> Unit = {},
    finalizeBlock: ResourcePatchContext.() -> Unit = {},
) = resourcePatch(
    name = name,
    description = description,
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

    val updatePermissions = shouldUpdatePermissions ?: booleanOption(
        key = "updatePermissions",
        default = false,
        title = "Update permissions",
        description = "Update compatibility receiver permissions. " +
            "Enabling this can fix installation errors, but this can also break features in certain apps.",
    ).value

    val updateProviders = shouldUpdateProviders ?: booleanOption(
        key = "updateProviders",
        default = false,
        title = "Update providers",
        description = "Update provider names declared by the app. " +
            "Enabling this can fix installation errors, but this can also break features in certain apps.",
    ).value

    /*
    val patchGetPackage = shouldPatchGetPackageName ?: booleanOption(
        key = "patchGetPackageName",
        default = false,
        title = "Patch get package name calls",
        description = "Patch usages of Context.getPackageName(). " +
                "Enabling this can fix runtime errors, but this can also break features in certain apps.",
    ).value
    */

    block()

    execute {
        executeBlock(packageNameOption.value!!)
    }

    finalize {
        /**
         * Apps that are confirmed to not work correctly with this patch.
         * This is not an exhaustive list, and is only the apps with
         * Morphe specific patches and are confirmed incompatible with this patch.
         */
        val incompatibleAppPackages = setOf<String>()

        document("AndroidManifest.xml").use { document ->
            val manifest = document.getNode("manifest") as Element
            val packageName = manifest.getAttribute("package")

            if (incompatibleAppPackages.contains(packageName)) {
                return@finalize Logger.getLogger(this::class.java.name).severe(
                    "'$packageName' does not work correctly with \"Change package name\"",
                )
            }

            val replacementPackageName = packageNameOption.value
            val newPackageName = if (replacementPackageName != packageNameOption.default) {
                replacementPackageName!!
            } else {
                "$packageName.morphe"
            }

            manifest.setAttribute("package", newPackageName)

            if (updatePermissions == true) {
                val permissions = manifest.getElementsByTagName("permission").asSequence()
                val usesPermissions = manifest.getElementsByTagName("uses-permission").asSequence()

                val receiverNotExported = "DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"

                (permissions + usesPermissions)
                    .map { it as Element }
                    .filter { it.getAttribute("android:name") == "$packageName.$receiverNotExported" }
                    .forEach { it.setAttribute("android:name", "$newPackageName.$receiverNotExported") }
            }

            if (updateProviders == true) {
                val providers = manifest.getElementsByTagName("provider").asSequence()

                for (node in providers) {
                    val provider = node as Element

                    val authorities = provider.getAttribute("android:authorities")
                    if (!authorities.startsWith("$packageName.")) continue

                    provider.setAttribute("android:authorities", authorities.replace(packageName, newPackageName))
                }
            }
        }

        finalizeBlock()
    }
}
