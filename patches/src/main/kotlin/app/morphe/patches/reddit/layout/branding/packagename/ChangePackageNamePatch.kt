/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-cli
 */

package app.morphe.patches.reddit.layout.branding.packagename

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstructionOrNull
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.stringOption
import app.morphe.patches.all.misc.transformation.transformInstructionsPatch
import app.morphe.patches.reddit.misc.fix.signature.spoofSignaturePatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.findMutableMethodOf
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstruction
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.w3c.dom.Element

private const val PACKAGE_NAME_REDDIT = "com.reddit.frontpage"
private const val DEFAULT_PACKAGE_NAME_REDDIT = "$PACKAGE_NAME_REDDIT.morphe"

private var redditPackageName = PACKAGE_NAME_REDDIT

private val recaptchaPatch = bytecodePatch {
    execute {
        classDefForEach { classDef ->
            if (!classDef.type.startsWith("Lcom/google/android/recaptcha/internal")) return@classDefForEach

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
                        const-string v$register, "$PACKAGE_NAME_REDDIT"
                    """
                )
            }
        }
    }
}

@Suppress("unused")
val changePackageNamePatch = resourcePatch(
    name = "Change Reddit package name",
    description = "Changes the package name for Reddit to the name specified in patch options.",
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(spoofSignaturePatch, recaptchaPatch)

    val packageNameRedditOption = stringOption(
        key = "packageNameReddit",
        default = DEFAULT_PACKAGE_NAME_REDDIT,
        values = mapOf(
            "Default" to DEFAULT_PACKAGE_NAME_REDDIT
        ),
        title = "Package name of Reddit",
        description = "The name of the package to rename the app to.",
        required = true
    )

    execute {
        redditPackageName = packageNameRedditOption.value!!

        // replace strings
        document("res/values/strings.xml").use { document ->
            val resourcesNode = document.documentElement

            val children = resourcesNode.childNodes
            for (i in 0 until children.length) {
                val node = children.item(i) as? Element ?: continue

                node.textContent = when (node.getAttribute("name")) {
                    "provider_authority_appdata", "provider_authority_file",
                    "provider_authority_userdata", "provider_workmanager_init"
                        -> node.textContent.replace(PACKAGE_NAME_REDDIT, redditPackageName)

                    else -> continue
                }
            }
        }

        // replace manifest permission and provider
        get("AndroidManifest.xml").apply {
            writeText(
                readText()
                    .replace(
                        "android:authorities=\"$PACKAGE_NAME_REDDIT",
                        "android:authorities=\"$redditPackageName"
                    )
            )
        }
    }

    finalize {
        get("AndroidManifest.xml").apply {
            writeText(
                readText()
                    .replace(
                        "package=\"$PACKAGE_NAME_REDDIT",
                        "package=\"$redditPackageName"
                    )
                    .replace(
                        "$PACKAGE_NAME_REDDIT.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
                        "$redditPackageName.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
                    )
            )
        }
    }
}
