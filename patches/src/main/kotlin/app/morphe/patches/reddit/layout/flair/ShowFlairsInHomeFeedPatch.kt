/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3260
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.flair

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableField
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_24_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.p0Register
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.util.logging.Logger

private const val EXTENSION_CLASS = "Lapp/morphe/extension/reddit/patches/ShowFlairsInHomeFeedPatch;"
private const val CACHED_POST_INTERFACE = $$"Lapp/morphe/extension/reddit/patches/ShowFlairsInHomeFeedPatch$CachedPost;"

@Suppress("unused")
val showFlairsInHomeFeedPatch = bytecodePatch(
    name = "Show flairs in home feed",
    description = "Adds an option to show post flair badges in the home feed. " +
            "This patch works with Reddit 2026.24.0 and newer."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        if (!is_2026_24_0_or_greater) {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "'Show flairs in home feed' requires Reddit 2026.24.0+"
            )
        }

        LinkConstructorFingerprint.method.apply {
            findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { returnIndex ->
                addInstruction(
                    returnIndex,
                    "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->rememberFlair(Lcom/reddit/domain/model/Link;)V"
                )
            }
        }

        //
        // Resolve the app types and methods used to create a flair element.
        //

        val presentationModelType = PostFlairFeedPresentationModelToStringFingerprint.classDef.type
        val flairsElementType = PostFlairsElementToStringFingerprint.classDef.type
        val flairsSectionType = PostFlairsSectionToStringFingerprint.classDef.type
        val titleSectionType = FeedPostTitleSectionToStringFingerprint.classDef.type

        val (presentationModelConstructor, persistentListOfMethod, flairsElementConstructor) =
            postFlairsElementMapperFingerprint(
                presentationModelType,
                flairsElementType
            ).instructionMatches.let { matches ->
                listOf(matches[0], matches[1], matches[3]).map {
                    it.getInstruction<ReferenceInstruction>().reference
                }
            }

        // Smali that creates a flair element with a single flair in register v14.
        // Expects the link id in v11, the unique id in v12 and the identifier in v13.
        // Registers v1 to v10 are overwritten.
        // Parameters p1 to p4 are the flair, community, text color and background color.
        val createFlairsElementSmali = """
            new-instance v1, $presentationModelType
            move-object/from16 v2, p1
            const/4 v3, 0x0
            move-object v4, v11
            move-object/from16 v5, p2
            const-string v6, ""
            move-object/from16 v7, p3
            move-object/from16 v8, p4
            move-object v9, v2
            move-object v10, v2
            invoke-direct/range { v1 .. v10 }, $presentationModelConstructor
            filled-new-array { v1 }, [Ljava/lang/Object;
            move-result-object v1
            invoke-static { v1 }, $persistentListOfMethod
            move-result-object v1
            new-instance v2, $flairsElementType
            move-object v3, v11
            move-object v4, v12
            const/4 v5, 0x0
            move-object v6, v13
            move-object v7, v1
            invoke-direct/range { v2 .. v7 }, $flairsElementConstructor
            move-object v14, v2
        """

        //
        // Add flairs to post preview elements.
        //

        val postPreviewType = PostPreviewFeedElementToStringFingerprint.classDef.type
        val (previewLinkIdField, previewUniqueIdField, previewIdentifierField, previewChildrenField) =
            PostPreviewFeedElementConstructorFingerprint.instructionMatches.let { matches ->
                listOf(matches[0], matches[1], matches[3], matches[6]).map { it.getFieldAccessed() }
            }

        val previewCopyFingerprint = postPreviewFeedElementCopyFingerprint(postPreviewType, flairsElementType)
        val previewCopyMethod = previewCopyFingerprint.method
        val previewFlairsField = previewCopyFingerprint.instructionMatches.last().getFieldAccessed()
        // Copy method bit mask where all values except the flairs element are copied from the original.
        val previewCopyMask = previewCopyFingerprint.instructionMatches.first()
            .getInstruction<NarrowLiteralInstruction>().narrowLiteral.inv()

        // Copy method arguments start at v15.
        var copyArgumentRegister = 15
        val copyArgumentsSmali = previewCopyMethod.parameterTypes.mapIndexed { index, type ->
            val register = copyArgumentRegister
            val isWide = type == "J" || type == "D"
            copyArgumentRegister += if (isWide) 2 else 1

            when {
                index == 0 -> "move-object/from16 v$register, p0"
                index == previewCopyMethod.parameterTypes.lastIndex -> "const v$register, $previewCopyMask"
                type == flairsElementType -> "move-object/from16 v$register, v14"
                isWide -> "const-wide/16 v$register, 0x0"
                else -> "const/16 v$register, 0x0"
            }
        }.joinToString("\n")

        PostPreviewFeedElementToStringFingerprint.classDef.addCachedPostMethods(
            linkIdField = previewLinkIdField,
            registerCount = copyArgumentRegister,
            addHomeFlairSmali = """
                move-object/from16 v0, p0
                iget-object v1, v0, $previewFlairsField
                if-nez v1, :return

                iget-object v11, v0, $previewLinkIdField
                iget-object v12, v0, $previewUniqueIdField
                iget-object v13, v0, $previewIdentifierField
                $createFlairsElementSmali

                # Use the copy method to create the child elements with the flairs element included.
                $copyArgumentsSmali
                invoke-static/range { v15 .. v${copyArgumentRegister - 1} }, $previewCopyMethod
                move-result-object v1
                iget-object v1, v1, $previewChildrenField
                iput-object v14, v0, $previewFlairsField
                iput-object v1, v0, $previewChildrenField
                const/4 v1, 0x1
                return v1

                # The post already has a flairs element.
                :return
                const/4 v1, 0x0
                return v1
            """
        )

        //
        // Add flairs to post sections.
        //

        val feedPostType = FeedPostSectionToStringFingerprint.classDef.type
        val (sectionLinkIdField, sectionSectionsField, sectionUniqueIdField, sectionIdentifierField) =
            FeedPostSectionConstructorFingerprint.instructionMatches.map { it.getFieldAccessed() }

        FeedPostSectionToStringFingerprint.classDef.addCachedPostMethods(
            linkIdField = sectionLinkIdField,
            registerCount = 15,
            addHomeFlairSmali = """
                move-object/from16 v0, p0
                iget-object v11, v0, $sectionLinkIdField
                iget-object v12, v0, $sectionUniqueIdField
                iget-object v13, v0, $sectionIdentifierField
                $createFlairsElementSmali

                new-instance v1, $flairsSectionType
                invoke-direct { v1, v14 }, $flairsSectionType-><init>($flairsElementType)V

                # Copy the sections and insert the flairs section after the title section.
                new-instance v2, Ljava/util/ArrayList;
                invoke-direct { v2 }, Ljava/util/ArrayList;-><init>()V
                iget-object v3, v0, $sectionSectionsField
                invoke-interface { v3 }, Ljava/lang/Iterable;->iterator()Ljava/util/Iterator;
                move-result-object v3
                const/4 v6, -0x1
                :copy_sections
                invoke-interface { v3 }, Ljava/util/Iterator;->hasNext()Z
                move-result v4
                if-eqz v4, :insert_flairs
                invoke-interface { v3 }, Ljava/util/Iterator;->next()Ljava/lang/Object;
                move-result-object v5
                instance-of v4, v5, $flairsSectionType
                if-nez v4, :return
                invoke-virtual { v2, v5 }, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
                instance-of v4, v5, $titleSectionType
                if-eqz v4, :copy_sections
                invoke-virtual { v2 }, Ljava/util/ArrayList;->size()I
                move-result v6
                goto :copy_sections

                :insert_flairs
                if-ltz v6, :append_flairs
                invoke-virtual { v2, v6, v1 }, Ljava/util/ArrayList;->add(ILjava/lang/Object;)V
                goto :set_sections
                :append_flairs
                invoke-virtual { v2, v1 }, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z

                :set_sections
                invoke-virtual { v2 }, Ljava/util/ArrayList;->toArray()[Ljava/lang/Object;
                move-result-object v2
                invoke-static { v2 }, $persistentListOfMethod
                move-result-object v2
                iput-object v2, v0, $sectionSectionsField
                const/4 v1, 0x1
                return v1

                # The post already has a flairs section.
                :return
                const/4 v1, 0x0
                return v1
            """
        )

        feedPostSectionMapperFingerprint(feedPostType).let {
            val returnMatch = it.instructionMatches.last()
            val sectionRegister = returnMatch.getInstruction<OneRegisterInstruction>().registerA

            it.method.addInstructions(
                returnMatch.index,
                """
                    invoke-static/range { v$sectionRegister .. v$sectionRegister }, $EXTENSION_CLASS->showHomeFlair(Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v$sectionRegister
                    check-cast v$sectionRegister, $feedPostType
                """
            )
        }

        FeedElementProcessorFingerprint.method.apply {
            val elementsRegister = p0Register + 2

            addInstructions(
                0,
                """
                    invoke-static/range { v$elementsRegister .. v$elementsRegister }, $EXTENSION_CLASS->showHomeFlairs(Ljava/util/List;)Ljava/util/List;
                    move-result-object v$elementsRegister
                """
            )
        }

        // After a feed page is shown, Reddit loads the full posts of the page in the background,
        // and the full posts include the flairs. A feature flag removes this secondary fetch
        // for post previews. Keep the fetch, so their flairs are available.
        postsToLoadFingerprint(postPreviewType).let {
            val flagMatch = it.instructionMatches.last()
            val flagRegister = flagMatch.getInstruction<OneRegisterInstruction>().registerA

            it.method.addInstructionsAtControlFlowLabel(
                flagMatch.index,
                """
                    invoke-static { v$flagRegister }, $EXTENSION_CLASS->isPostPreviewSecondaryFetchRemoved(Z)Z
                    move-result v$flagRegister
                """
            )
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}

/**
 * Adds [CACHED_POST_INTERFACE] and its methods to a post class.
 *
 * @param registerCount Number of local registers used by [addHomeFlairSmali], not including parameters.
 */
private fun MutableClass.addCachedPostMethods(
    linkIdField: MutableField,
    registerCount: Int,
    addHomeFlairSmali: String
) {
    interfaces.add(CACHED_POST_INTERFACE)

    methods.add(
        ImmutableMethod(
            type,
            "patch_getLinkId",
            emptyList(),
            "Ljava/lang/String;",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            emptySet(),
            null,
            MutableMethodImplementation(2)
        ).toMutable().apply {
            addInstructions(
                0,
                """
                    iget-object v0, p0, $linkIdField
                    return-object v0
                """
            )
        }
    )

    methods.add(
        ImmutableMethod(
            type,
            "patch_addHomeFlair",
            listOf(
                ImmutableMethodParameter("Ljava/lang/String;", emptySet(), "flair"),
                ImmutableMethodParameter("Ljava/lang/String;", emptySet(), "community"),
                ImmutableMethodParameter("Ljava/lang/String;", emptySet(), "textColor"),
                ImmutableMethodParameter("Ljava/lang/String;", emptySet(), "backgroundColor")
            ),
            "Z",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            emptySet(),
            null,
            MutableMethodImplementation(registerCount + 5)
        ).toMutable().apply {
            addInstructions(0, addHomeFlairSmali)
        }
    )
}
