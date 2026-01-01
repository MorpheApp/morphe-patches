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
 */
fun sharedExtensionPatch(
    extensionName: String,
    vararg hooks: ExtensionHook,
) = bytecodePatch {
    dependsOn(sharedExtensionPatch(*hooks))

    extendWith("extensions/$extensionName.mpe")
}

/**
 * A patch to extend with the "shared" extension.
 *
 * @param hooks The hooks to get the application context for use in the extension,
 * commonly for the onCreate method of exported activities.
 */
fun sharedExtensionPatch(
    vararg hooks: ExtensionHook,
) = bytecodePatch {
    extendWith("extensions/shared.mpe")

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
 * Handles passing the application context to the extension code. Typically the main activity
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

    val fullClassType = activityClassType.startsWith('L')

    val fingerprint = Fingerprint(
        returnType = "V",
        parameters = if (targetBundleMethod) {
            listOf("Landroid/os/Bundle;")
        } else {
            listOf()
        },
        custom = { method, classDef ->
            method.name == "onCreate" &&
                    if (fullClassType) classDef.type == activityClassType
                    else classDef.type.endsWith(activityClassType)
        }
    )

    return ExtensionHook(fingerprint)
}
