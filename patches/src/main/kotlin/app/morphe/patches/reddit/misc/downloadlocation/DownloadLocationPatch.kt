/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3870
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.downloadlocation

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.registersUsed
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.patches.reddit.shared.LinkConstructorFingerprint
import app.morphe.util.fiveRegisters
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/DownloadLocationPatch;"
private const val EXTENSION_IMAGE_DOWNLOAD_PARAMS_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/DownloadLocationPatch$ImageDownloadParams;"

@Suppress("unused")
val downloadLocationPatch = bytecodePatch(
    name = "Download location",
    description = "Adds options to choose the folder and file name of downloaded images and videos, " +
            "which can use the subreddit, author and account, and can be set per account."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {
        fun Fingerprint.replaceWithStaticCall(callFilterIndex: Int, extensionMethod: String) {
            val index = instructionMatches[callFilterIndex].index
            val fiveRegisters = method.fiveRegisters(index)
            method.replaceInstruction(
                index,
                "invoke-static { $fiveRegisters }, $EXTENSION_CLASS->$extensionMethod"
            )
        }

        // Video downloads only have the url, so the post is recorded when it's created.
        LinkConstructorFingerprint.method.apply {
            addInstruction(
                indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_VOID),
                "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->onLinkCreated(Lcom/reddit/domain/model/Link;)V"
            )
        }

        // Add an interface and helper methods to read the obfuscated image download parameters.
        LinkDownloadModelToStringFingerprint.let {
            val subredditField = it.instructionMatches[2].getFieldAccessed()
            val authorField = it.instructionMatches[3].getFieldAccessed()

            imageDownloadParamsConstructorFingerprint(it.classDef.type).let { constructor ->
                val urlField = constructor.instructionMatches[0].getFieldAccessed()
                val modelField = constructor.instructionMatches[1].getFieldAccessed()

                constructor.classDef.apply {
                    interfaces.add(EXTENSION_IMAGE_DOWNLOAD_PARAMS_INTERFACE)

                    fun addGetter(name: String, smali: String) = methods.add(
                        ImmutableMethod(
                            type,
                            name,
                            listOf(),
                            "Ljava/lang/String;",
                            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                            null,
                            null,
                            MutableMethodImplementation(2),
                        ).toMutable().apply {
                            addInstructionsWithLabels(0, smali)
                        }
                    )

                    fun addModelGetter(name: String, field: FieldReference) = addGetter(
                        name,
                        """
                            iget-object v0, p0, $modelField
                            if-nez v0, :model
                            const/4 v0, 0x0
                            return-object v0
                            :model
                            iget-object v0, v0, $field
                            return-object v0
                        """
                    )

                    addGetter(
                        "patch_getUrl",
                        """
                            iget-object v0, p0, $urlField
                            return-object v0
                        """
                    )
                    addModelGetter("patch_getSubreddit", subredditField)
                    addModelGetter("patch_getAuthor", authorField)
                }
            }
        }

        DownloadImageFingerprint.let {
            val index = it.instructionMatches[0].index
            val paramsRegister = it.method.getInstruction<TwoRegisterInstruction>(index).registerA
            it.method.addInstruction(
                index + 1,
                "invoke-static/range { v$paramsRegister .. v$paramsRegister }, " +
                        "$EXTENSION_CLASS->onImageSave($EXTENSION_IMAGE_DOWNLOAD_PARAMS_INTERFACE)V"
            )
        }

        DownloadVideoFingerprint.let {
            it.replaceWithStaticCall(
                3,
                $$"enqueue(Landroid/app/DownloadManager;Landroid/app/DownloadManager$Request;)J"
            )
            it.replaceWithStaticCall(
                1,
                $$"setDestinationInExternalPublicDir(Landroid/app/DownloadManager$Request;" +
                        $$"Ljava/lang/String;Ljava/lang/String;)Landroid/app/DownloadManager$Request;"
            )

            val requestMatch = it.instructionMatches[0]
            val uriRegister = requestMatch.instruction.registersUsed[1]
            it.method.addInstruction(
                requestMatch.index,
                "invoke-static/range { v$uriRegister .. v$uriRegister }, " +
                        "$EXTENSION_CLASS->onVideoDownload(Landroid/net/Uri;)V"
            )
        }

        val insertParameters = "(Landroid/content/ContentResolver;Landroid/net/Uri;" +
                "Landroid/content/ContentValues;)Landroid/net/Uri;"
        SaveImageFingerprint.replaceWithStaticCall(2, "insertImage$insertParameters")
        SaveToGalleryFingerprint.replaceWithStaticCall(2, "insertSharedImage$insertParameters")

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
