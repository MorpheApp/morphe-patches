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
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/DownloadLocationPatch;"

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
            method.apply {
                val instruction = getInstruction(index)
                val registers = if (instruction is RegisterRangeInstruction) {
                    val start = instruction.startRegister
                    "invoke-static/range { v$start .. v${start + instruction.registerCount - 1} }"
                } else {
                    instruction as FiveRegisterInstruction
                    val argumentRegisters = listOf(
                        instruction.registerC, instruction.registerD, instruction.registerE,
                        instruction.registerF, instruction.registerG
                    ).take(instruction.registerCount)
                    "invoke-static { ${argumentRegisters.joinToString { "v$it" }} }"
                }

                replaceInstruction(index, "$registers, $EXTENSION_CLASS->$extensionMethod")
            }
        }

        // Video downloads only have the url, so the post is recorded when it's created.
        LinkConstructorFingerprint.method.apply {
            addInstruction(
                indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_VOID),
                "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->onLinkCreated(Lcom/reddit/domain/model/Link;)V"
            )
        }

        LinkDownloadModelToStringFingerprint.let {
            val modelType = it.classDef.type
            val subredditField = it.instructionMatches[2].getFieldAccessed()
            val authorField = it.instructionMatches[3].getFieldAccessed()

            ImageDownloadParamsConstructorFingerprint.method.apply {
                val registerCount = implementation!!.registerCount
                // The p registers are the last registers, and long and double parameters take two.
                fun parameterRegister(type: String): Int {
                    val index = parameterTypes.indexOf(type)
                    check(index >= 0) { "Missing parameter: $type" }
                    val parameterRegisters = 1 + parameterTypes.sumOf { if (it == "J" || it == "D") 2 else 1 }
                    return registerCount - parameterRegisters + 1 +
                            parameterTypes.take(index).sumOf { if (it == "J" || it == "D") 2 else 1 }
                }
                val urlRegister = parameterRegister("Ljava/lang/String;")
                val modelRegister = parameterRegister(modelType)
                // v1 is written before the model is read.
                check(modelRegister != 1) { "Model parameter is in v1" }

                // All registers are unused at the final return.
                // Without a model, v3 is cleared, so its merged type is a string and the class verifies.
                addInstructionsWithLabels(
                    indexOfFirstInstructionReversedOrThrow(Opcode.RETURN_VOID),
                    """
                        move-object/from16 v0, p0
                        move-object/from16 v1, v$urlRegister
                        move-object/from16 v3, v$modelRegister
                        const/4 v2, 0x0
                        if-eqz v3, :no_model
                        iget-object v2, v3, $subredditField
                        iget-object v3, v3, $authorField
                        goto :call
                        :no_model
                        const/4 v3, 0x0
                        :call
                        invoke-static/range { v0 .. v3 }, $EXTENSION_CLASS->onImageDownload(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
                    """
                )
            }
        }

        DownloadImageFingerprint.let {
            val index = it.instructionMatches[0].index
            val paramsRegister = it.method.getInstruction<TwoRegisterInstruction>(index).registerA
            it.method.addInstruction(
                index + 1,
                "invoke-static/range { v$paramsRegister .. v$paramsRegister }, " +
                        "$EXTENSION_CLASS->onImageSave(Ljava/lang/Object;)V"
            )
        }

        DownloadVideoFingerprint.let {
            it.replaceWithStaticCall(
                3,
                "enqueue(Landroid/app/DownloadManager;Landroid/app/DownloadManager\$Request;)J"
            )
            it.replaceWithStaticCall(
                1,
                "setDestinationInExternalPublicDir(Landroid/app/DownloadManager\$Request;" +
                        "Ljava/lang/String;Ljava/lang/String;)Landroid/app/DownloadManager\$Request;"
            )

            // Runs before Redgifs audio, which replaces the uri at the same call.
            val requestIndex = it.instructionMatches[0].index
            val uriRegister = it.method.getInstruction<FiveRegisterInstruction>(requestIndex).registerD
            it.method.addInstruction(
                requestIndex,
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
