/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */

package app.morphe.patches.youtube.layout.hide.relatedvideos

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.misc.fix.proto.fixProtoLibraryPatch
import app.morphe.patches.shared.misc.fix.proto.immutableMethodRef
import app.morphe.patches.shared.misc.fix.proto.mutableCopyMethodRef
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.shared.WatchNextResponseParserFingerprint
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/HideRelatedVideosPatch;"

@Suppress("unused")
val hideRelatedVideosPatch = bytecodePatch(
    name = "Hide related videos",
    description = "Adds options to hide related videos."
) {
    dependsOn(
        settingsPatch,
        sharedExtensionPatch,
        fixProtoLibraryPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_hide_player_related_videos")
        )

        val continuationsField = with (WatchNextResponseParserFingerprint) {
            clearMatch() // Fingerprint is shared and indexes may no longer be correct.
            instructionMatches[2].instruction.getReference<FieldReference>()!!
        }
        val resultsClass = continuationsField.definingClass

        val helperMethodParameter = listOf(
            ImmutableMethodParameter(
                resultsClass,
                null,
                null
            )
        )

        val emptyProtobufListFingerprint = Fingerprint(
            definingClass = resultsClass,
            name = "<init>",
            returnType = "V",
            parameters = listOf(),
            filters = listOf(
                methodCall(
                    opcode = Opcode.INVOKE_STATIC,
                    name = "emptyProtobufList"
                )
            )
        )

        val emptyProtobufListMethod = emptyProtobufListFingerprint
            .instructionMatches.last().instruction.getReference<MethodReference>()!!

        val sectionIdentifierField = RelatedItemSectionFingerprint
            .instructionMatches[1].instruction.getReference<FieldReference>()!!

        val watchNextResponseModelClass = WatchNextResponseModelClassResolverFingerprint
            .instructionMatches.last().instruction.getReference<TypeReference>()!!.type

        val watchNextResultsFingerprint = Fingerprint(
            definingClass = watchNextResponseModelClass,
            accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
            returnType = "V",
            parameters = listOf(resultsClass),
            filters = listOf(
                fieldAccess(
                    opcode = Opcode.IGET_OBJECT,
                    definingClass = resultsClass,
                    type = emptyProtobufListMethod.returnType
                ),
                methodCall(
                    opcode = Opcode.INVOKE_INTERFACE,
                    name = "iterator",
                    location = MatchAfterImmediately()
                ),
                fieldAccess(
                    opcode = Opcode.IGET_OBJECT,
                    type = sectionIdentifierField.definingClass
                )
            )
        )

        watchNextResultsFingerprint.let {
            val contentsField =
                it.instructionMatches.first().instruction.getReference<FieldReference>()!!
            val itemSectionRendererField =
                it.instructionMatches.last().instruction.getReference<FieldReference>()!!
            val itemSectionRendererDefaultInstance =
                "${itemSectionRendererField.type}->a:${itemSectionRendererField.type}"

            val firstHomeThumbnailCrawlerFingerprint = Fingerprint(
                returnType = "Ljava/util/List;",
                parameters = listOf("Ljava/lang/Object;"),
                filters = listOf(
                    string("hint=%s,(%s=%s,cheatsheet=%b,key1=%s,w=%d,h=%d)"),
                    fieldAccess(
                        opcode = Opcode.IGET_OBJECT,
                        definingClass = itemSectionRendererField.definingClass
                    ),
                    fieldAccess(
                        opcode = Opcode.IGET_OBJECT,
                        reference = itemSectionRendererField
                    )
                )
            )

            val shelfRendererField = firstHomeThumbnailCrawlerFingerprint.instructionMatches[1]
                .instruction.getReference<FieldReference>()!!
            val shelfRendererDefaultInstance =
                "${shelfRendererField.type}->a:${shelfRendererField.type}"

            it.method.apply {
                val helperMethod = ImmutableMethod(
                    definingClass,
                    "patch_hideRelatedVideos",
                    helperMethodParameter,
                    "V",
                    AccessFlags.PRIVATE.value or AccessFlags.FINAL.value,
                    annotations,
                    null,
                    MutableMethodImplementation(6),
                ).toMutable().apply {
                    addInstructionsWithLabels(
                        0,
                        """
                            invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->hideRelatedVideos()Z
                            move-result v0

                            if-eqz v0, :ignore
                            iget-object v0, p1, $contentsField
                            invoke-interface { v0 }, ${immutableMethodRef.get()}
                            move-result v1

                            # Check if ProtoList is immutable or not.
                            if-nez v1, :ignore
                            
                            # If mutable, copy the ProtoList.
                            invoke-static { v0 }, ${mutableCopyMethodRef.get()}
                            move-result-object v0
                            
                            invoke-interface { v0 }, Ljava/util/List;->iterator()Ljava/util/Iterator;
                            move-result-object v1
                            
                            :loop
                            invoke-interface { v1 }, Ljava/util/Iterator;->hasNext()Z
                            move-result v2

                            if-eqz v2, :exit
                            invoke-interface { v1 }, Ljava/util/Iterator;->next()Ljava/lang/Object;
                            move-result-object v2
                            check-cast v2, ${itemSectionRendererField.definingClass}

                            # Checks whether ItemSectionRenderer is a related item.
                            invoke-static { v2 }, $EXTENSION_CLASS_DESCRIPTOR->isRelatedItems(Lcom/google/protobuf/MessageLite;)Z
                            move-result v3
                            if-eqz v3, :is_not_related_item
                            
                            # If this ItemSectionRenderer is a related item, it will be replaced with the default instance.
                            sget-object v3, $itemSectionRendererDefaultInstance
                            iput-object v3, v2, $itemSectionRendererField
                            goto :loop

                            :is_not_related_item
                            
                            # Checks whether ShelfRenderer is a related item.
                            invoke-static { v2 }, $EXTENSION_CLASS_DESCRIPTOR->isShelfRenderer(Lcom/google/protobuf/MessageLite;)Z
                            move-result v3
                            if-eqz v3, :is_not_shelf_renderer
                            
                            # If this ShelfRenderer is a related item, it will be replaced with the default instance.
                            sget-object v3, $shelfRendererDefaultInstance
                            iput-object v3, v2, $shelfRendererField
                            goto :loop

                            :is_not_shelf_renderer

                            goto :loop

                            :exit
                            invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->isFiltered()Z
                            move-result v2
                            if-eqz v2, :ignore

                            # Replaces the overridden ProtoList if filtered.
                            iput-object v0, p1, $contentsField

                            # Replaces the continuations field with an empty ProtoList if filtered.
                            # If the continuations field is not replaced, unnecessary spam API requests are made.
                            invoke-static { }, $emptyProtobufListMethod
                            move-result-object v0
                            iput-object v0, p1, $continuationsField

                            :ignore
                            return-void
                        """
                    )
                }

                it.classDef.methods.add(helperMethod)

                addInstruction(
                    0,
                    "invoke-direct { p0, p1 }, $helperMethod"
                )
            }
        }
    }
}
