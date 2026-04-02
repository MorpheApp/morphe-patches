/*
 * Copyright 2025 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original code hard forked from:
 * https://github.com/ReVanced/revanced-patches/blob/724e6d61b2ecd868c1a9a37d465a688e83a74799/patches/src/main/kotlin/app/revanced/util/BytecodeUtils.kt
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

package app.morphe.patches.shared.misc.extension

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.iface.Method
import java.net.URLDecoder
import java.util.jar.JarFile

internal const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/shared/Utils;"

/**
 * A patch to extend with an extension shared with multiple patches.
 *
 * @param extensionName The name of the extension to extend with.
 * @param isYouTubeOrYouTubeMusic Whether the patch targets YouTube and YouTube Music.
 */
fun sharedExtensionPatch(
    extensionName: String,
    isYouTubeOrYouTubeMusic: Boolean,
    vararg hooks: ExtensionHook,
) = bytecodePatch {
    dependsOn(sharedExtensionPatch(isYouTubeOrYouTubeMusic, *hooks))

    extendWith("extensions/$extensionName.mpe")
}

/**
 * A patch to extend with the "shared" extension.
 *
 * @param hooks The hooks to get the application context for use in the extension,
 * commonly for the onCreate method of exported activities.
 */
fun sharedExtensionPatch(
    isYouTubeOrYouTubeMusic: Boolean,
    vararg hooks: ExtensionHook,
) = bytecodePatch {
    val extensionName = if (isYouTubeOrYouTubeMusic) "shared-youtube" else "shared"
    extendWith("extensions/$extensionName.mpe")

    execute {
        // Verify the extension class exists.
        classDefBy(EXTENSION_CLASS_DESCRIPTOR)
    }

    finalize {
        // The hooks are made in finalize to ensure that the context is hooked before any other patches.
        hooks.forEach { hook -> hook(EXTENSION_CLASS_DESCRIPTOR) }

        // Modify Utils method to include the patches release version.
        MorpheUtilsPatchesVersionFingerprint.method.apply {
            /**
             * @return The file path for the jar this classfile is contained inside.
             */
            fun getCurrentJarFilePath(): String {
                val className = object {}::class.java.enclosingClass.name.replace('.', '/') + ".class"
                val classUrl = object {}::class.java.classLoader?.getResource(className)
                if (classUrl != null) {
                    val urlString = classUrl.toString()

                    if (urlString.startsWith("jar:file:")) {
                        val end = urlString.lastIndexOf('!')

                        return URLDecoder.decode(urlString.substring("jar:file:".length, end), "UTF-8")
                    }
                }
                throw IllegalStateException("Not running from inside a JAR file.")
            }

            /**
             * @return The value for the manifest entry,
             *         or "Unknown" if the entry does not exist or is blank.
             */
            @Suppress("SameParameterValue")
            fun getPatchesManifestEntry(attributeKey: String) = JarFile(getCurrentJarFilePath()).use { jarFile ->
                jarFile.manifest.mainAttributes.entries.firstOrNull { it.key.toString() == attributeKey }?.value?.toString()
                    ?: "Unknown"
            }

            val manifestValue = getPatchesManifestEntry("Version")
            returnEarly(manifestValue)
        }
    }
}

/**
 * Handles passing the application context to the extension code. Typically, the main activity
 * onCreate() method is hooked, but sometimes additional hooks are required if extension code
 * can be reached before the main activity is fully created.
 */
open class ExtensionHook(
    internal val fingerprint: Fingerprint,
    private val insertIndexResolver: BytecodePatchContext.(Method) -> Int = { 0 },
    private val contextRegisterResolver: BytecodePatchContext.(Method) -> String = { "p0" },
) {
    context(BytecodePatchContext)
    operator fun invoke(extensionClassDescriptor: String) {
        fingerprint.method.apply {
            val insertIndex = insertIndexResolver(this)
            val contextRegister = contextRegisterResolver(this)

            addInstruction(
                insertIndex,
                "invoke-static/range { $contextRegister .. $contextRegister }, " +
                        "$extensionClassDescriptor->setContext(Landroid/content/Context;)V",
            )
        }
    }
}

/**
 * Creates an extension hook from a non-obfuscated activity, which typically is the main activity
 * defined in the app manifest.xml file.
 *
 * @param activityClassType Either the full activity class type such as `Lcom/company/MainActivity;`
 *                          or the 'ends with' string for the activity such as `/MainActivity;`
 * @param targetBundleMethod If the extension should hook `onCreate(Landroid/os/Bundle;)` or `onCreate()`
 */
fun activityOnCreateExtensionHook(activityClassType: String, targetBundleMethod: Boolean = true): ExtensionHook {
    require(activityClassType.endsWith(';')) {
        "Class type must end with a semicolon: $activityClassType"
    }

    val fingerprint = Fingerprint(
        definingClass = activityClassType,
        name = "onCreate",
        returnType = "V",
        parameters = if (targetBundleMethod) {
            listOf("Landroid/os/Bundle;")
        } else {
            listOf()
        }
    )

    return ExtensionHook(fingerprint)
}
