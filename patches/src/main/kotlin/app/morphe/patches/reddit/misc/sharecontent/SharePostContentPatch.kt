/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4916
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.sharecontent

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.registersUsed
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_44_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/SharePostContentPatch;"
private const val LINK_CLASS =
    "Lcom/reddit/domain/model/Link;"
private const val LINK_MEDIA_CLASS =
    "Lcom/reddit/domain/model/LinkMedia;"
private const val REDDIT_VIDEO_CLASS =
    "Lcom/reddit/domain/model/RedditVideo;"
private const val POST_GALLERY_CLASS =
    "Lcom/reddit/domain/model/PostGallery;"
private const val POST_GALLERY_ITEM_CLASS =
    "Lcom/reddit/domain/model/PostGalleryItem;"

private const val LINK_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/SharePostContentPatch$LinkInterface;"
private const val LINK_MEDIA_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/SharePostContentPatch$LinkMediaInterface;"
private const val REDDIT_VIDEO_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/SharePostContentPatch$RedditVideoInterface;"
private const val POST_GALLERY_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/SharePostContentPatch$PostGalleryInterface;"
private const val POST_GALLERY_ITEM_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/SharePostContentPatch$PostGalleryItemInterface;"

val sharePostContentPatch = patch(
    name = "Share post content",
    description = "Adds an option to share the image, video or text of a post instead of its link " +
        "when using app shortcuts or \"Share via…\" in the share sheet."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        val appTarget = if (is_2026_44_0_or_greater) {
            SharePostAppTargetFingerprint
        } else {
            SharePostAppTargetLegacyFingerprint
        }

        appTarget.let {
            val launch = it.instructionMatches[2]
            val registers = launch.instruction.registersUsed
            val context = if (is_2026_44_0_or_greater) registers[1] else registers[0]
            val intent = if (is_2026_44_0_or_greater) registers[2] else registers[1]
            if (context >= 16 || intent >= 16) {
                throw PatchException("Register out of range for non-range invoke")
            }
            val free = it.method.findFreeRegister(launch.index, context, intent)
            val handledInstruction = it.method.getInstruction(it.instructionMatches[3].index)

            it.method.addInstructionsWithLabels(
                launch.index,
                """
                    invoke-static { v$context, v$intent }, $EXTENSION_CLASS->onShare(Landroid/content/Context;Landroid/content/Intent;)Z
                    move-result v$free
                    if-nez v$free, :handled
                """,
                ExternalLabel("handled", handledInstruction)
            )
        }

        SharePostSubjectFingerprint.match(appTarget.classDef).let {
            val match = it.instructionMatches.first()
            val reg = match.getInstruction<OneRegisterInstruction>().registerA
            it.method.addInstruction(
                match.index + 1,
                "invoke-static { v$reg }, $EXTENSION_CLASS->setLink(Ljava/lang/Object;)V"
            )
        }

        addInterface(
            LINK_CLASS,
            LINK_INTERFACE,
            listOf(
                Triple("patch_isSelf", "isSelf", "Z"),
                Triple("patch_getTitle", "getTitle", "Ljava/lang/String;"),
                Triple("patch_getSelftext", "getSelftext", "Ljava/lang/String;"),
                Triple("patch_getUrl", "getUrl", "Ljava/lang/String;"),
                Triple("patch_getPostHint", "getPostHint", "Ljava/lang/String;"),
                Triple("patch_getMedia", "getMedia", "Lcom/reddit/domain/model/LinkMedia;"),
                Triple("patch_getGallery", "getGallery", "Lcom/reddit/domain/model/PostGallery;"),
                Triple("patch_getCrossPostParentList", "getCrossPostParentList", "Ljava/util/List;")
            )
        )

        addInterface(
            LINK_MEDIA_CLASS,
            LINK_MEDIA_INTERFACE,
            listOf(
                Triple("patch_getRedditVideo", "getRedditVideo", "Lcom/reddit/domain/model/RedditVideo;")
            )
        )

        addInterface(
            REDDIT_VIDEO_CLASS,
            REDDIT_VIDEO_INTERFACE,
            listOf(
                Triple("patch_getDownloadUrl", "getDownloadUrl", "Ljava/lang/String;")
            )
        )

        addInterface(
            POST_GALLERY_CLASS,
            POST_GALLERY_INTERFACE,
            listOf(
                Triple("patch_getItems", "getItems", "Ljava/util/List;")
            )
        )

        addInterface(
            POST_GALLERY_ITEM_CLASS,
            POST_GALLERY_ITEM_INTERFACE,
            listOf(
                Triple("patch_getUrl", "getUrl", "Ljava/lang/String;")
            )
        )

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}

private fun addInterface(
    classType: String,
    interfaceType: String,
    delegates: List<Triple<String, String, String>>
) {
    mutableClassDefBy(classType).apply {
        for ((patchMethod, realGetter, returnType) in delegates) {
            val isObject = returnType.startsWith("L") || returnType.startsWith("[")
            val moveResult = if (isObject) "move-result-object v0" else "move-result v0"
            val returnOp = if (isObject) "return-object v0" else "return v0"
            addPatchInterface(
                interfaceType,
                patchMethod,
                """
                    invoke-virtual { p0 }, $classType->$realGetter()$returnType
                    $moveResult
                    $returnOp
                """
            )
        }
    }
}
