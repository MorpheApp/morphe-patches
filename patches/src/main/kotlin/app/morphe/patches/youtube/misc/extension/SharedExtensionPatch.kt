package app.morphe.patches.youtube.misc.extension

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patches.all.misc.extension.SHARED_UTILS_EXTENSION_CLASS
import app.morphe.patches.all.misc.extension.sharedExtensionPatch as baseSharedExtensionPatch
import app.morphe.patches.youtube.misc.extension.hooks.applicationInitHook
import app.morphe.patches.youtube.misc.extension.hooks.applicationInitOnCreateHook
import com.android.tools.smali.dexlib2.util.MethodUtil

/**
 * The Application class of YouTube, declared in the manifest.
 */
private const val YOUTUBE_APPLICATION_CLASS_TYPE =
    "Lcom/google/android/apps/youtube/app/YouTubeTikTokRoot_Application;"

val sharedExtensionPatch = patch {
    dependsOn(
        baseSharedExtensionPatch(
            listOf("youtube", "shared-youtube"),
            applicationInitHook,
            applicationInitOnCreateHook
        )
    )

    finalize {
        // The onCreate of the Application reads the feature flags before it calls the onCreate of its superclass,
        // where the application init hook sets the context, so the extension code of the feature flags runs
        // without the context. The context is also set at the start of the onCreate of the Application,
        // which is declared by a superclass of the Application class.
        var type: String? = YOUTUBE_APPLICATION_CLASS_TYPE
        while (type != null) {
            val classDef = classDefByOrNull(type)
                ?: throw PatchException("Could not find the Application onCreate method")
            val onCreate = classDef.methods.firstOrNull {
                it.name == "onCreate" && it.parameterTypes.isEmpty() && it.returnType == "V"
            }
            if (onCreate != null) {
                mutableClassDefBy(classDef).methods.first {
                    MethodUtil.methodSignaturesMatch(it, onCreate)
                }.addInstruction(
                    0,
                    "invoke-static/range { p0 .. p0 }, $SHARED_UTILS_EXTENSION_CLASS->setContext(Landroid/content/Context;)V"
                )
                break
            }
            type = classDef.superclass
        }
    }
}
