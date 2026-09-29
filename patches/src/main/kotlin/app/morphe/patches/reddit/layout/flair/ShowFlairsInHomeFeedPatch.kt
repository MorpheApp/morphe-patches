/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3260
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.flair

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.newInstance
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resourceLiteral
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.p0Register
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXTENSION_CLASS = "Lapp/morphe/extension/reddit/patches/ShowFlairsInHomeFeedPatch;"
private const val CACHED_POST_INTERFACE = $$"Lapp/morphe/extension/reddit/patches/ShowFlairsInHomeFeedPatch$CachedPost;"

@Suppress("unused")
val showFlairsInHomeFeedPatch = bytecodePatch(
    name = "Show flairs in home feed",
    description = "Adds an option to show post flair badges in the home feed."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch)

    execute {
        LinkConstructorFingerprint.let {
            val rememberMethodName = "patch_rememberHomeFlair"

            it.classDef.apply {
                val definingClass = this.type

                methods.add(
                    ImmutableMethod(
                        definingClass,
                        rememberMethodName,
                        emptyList(),
                        "V",
                        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                        emptySet(),
                        null,
                        MutableMethodImplementation(7)
                    ).toMutable().apply {
                        addInstructions(
                            0,
                            """
                                invoke-virtual { p0 }, $definingClass->getId()Ljava/lang/String;
                                move-result-object v0
                                invoke-virtual { p0 }, $definingClass->getKindWithId()Ljava/lang/String;
                                move-result-object v1
                                invoke-virtual { p0 }, $definingClass->getSubreddit()Ljava/lang/String;
                                move-result-object v2
                                invoke-virtual { p0 }, $definingClass->getLinkFlairText()Ljava/lang/String;
                                move-result-object v3
                                invoke-virtual { p0 }, $definingClass->getLinkFlairBackgroundColor()Ljava/lang/String;
                                move-result-object v4
                                invoke-virtual { p0 }, $definingClass->getLinkFlairTextColor()Ljava/lang/String;
                                move-result-object v5
                                invoke-static/range { v0 .. v5 }, $EXTENSION_CLASS->rememberFlair(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
                                return-void
                            """
                        )
                    }
                )
            }

            it.method.apply {
                findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { returnIndex ->
                    addInstruction(
                        returnIndex,
                        "invoke-virtual/range { p0 .. p0 }, $definingClass->$rememberMethodName()V"
                    )
                }
            }
        }

        PostPreviewFeedElementToStringFingerprint.classDef.apply {
            // Parent class method is not obfuscated.
            val getLinkIdField = Fingerprint(
                definingClass = this.superclass,
                name = "getLinkId",
                returnType = "Ljava/lang/String;",
                filters = listOf(
                    fieldAccess(
                        definingClass = "this",
                        type = "Ljava/lang/String;"
                    )
                )
            ).instructionMatches.first().getFieldAccessed()

            val definingClass = this.type
            interfaces.add(CACHED_POST_INTERFACE)
            methods.add(
                ImmutableMethod(
                    definingClass,
                    "patch_getLinkId",
                    emptyList(),
                    "Ljava/lang/String;",
                    AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                    emptySet(),
                    null,
                    MutableMethodImplementation(1)
                ).toMutable().apply {
                    addInstructions(
                        0,
                        """
                            iget-object v0, p0, $getLinkIdField
                            return-object v0
                        """
                    )
                }
            )

            methods.add(
                ImmutableMethod(
                    definingClass,
                    "patch_addHomeFlair",
                    listOf(
                        ImmutableMethodParameter("Ljava/lang/String;", emptySet(), "flair"),
                        ImmutableMethodParameter("Ljava/lang/String;", emptySet(), "community"),
                        ImmutableMethodParameter("Ljava/lang/String;", emptySet(), "textColor"),
                        ImmutableMethodParameter("Ljava/lang/String;", emptySet(), "backgroundColor")
                    ),
                    "V",
                    AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                    emptySet(),
                    null,
                    MutableMethodImplementation(24)
                ).toMutable().apply {
                    // TODO: remove hard coded class names/methods with fingerprints
                    //       that work on all supported versions of Reddit.
                    addInstructions(
                        0,
                        """
                            move-object/from16 v0, p0
                            iget-object v11, v0, $definingClass->n:Llg20;
                            if-nez v11, :return
                        
                            new-instance v1, Lje20;
                            move-object/from16 v2, p1
                            const/4 v3, 0x0
                            iget-object v4, v0, $getLinkIdField
                            move-object/from16 v5, p2
                            const-string v6, ""
                            move-object/from16 v7, p3
                            move-object/from16 v8, p4
                            move-object v9, v2
                            move-object v10, v2
                            invoke-direct/range { v1 .. v10 }, Lje20;-><init>(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
                        
                            const/4 v11, 0x1
                            new-array v12, v11, [Ljava/lang/Object;
                            const/4 v11, 0x0
                            aput-object v1, v12, v11
                            invoke-static { v12 }, Lfs80;->R([Ljava/lang/Object;)Lgo00;
                            move-result-object v12
                        
                            new-instance v13, Llg20;
                            move-object v14, v4
                            iget-object v11, v0, Lcki;->b:Ljava/lang/String;
                            move-object/from16 v15, v11
                            const/16 v16, 0x0
                            iget-object v11, v0, $definingClass->h:Lni20;
                            move-object/from16 v17, v11
                            move-object/from16 v18, v12
                            invoke-direct/range { v13 .. v18 }, Llg20;-><init>(Ljava/lang/String;Ljava/lang/String;ZLni20;Lv4p;)V
                            iput-object v13, v0, $definingClass->n:Llg20;
                        
                            move-object/from16 v10, p0
                            new-instance v0, Ljava/util/ArrayList;
                            const/16 v1, 0xd
                            invoke-direct { v0, v1 }, Ljava/util/ArrayList;-><init>(I)V
                            iget-object v2, v10, $definingClass->i:Ljiu;
                            if-eqz v2, :child_j
                            invoke-virtual { v0, v2 }, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
                            :child_j
                            iget-object v2, v10, $definingClass->j:Lwmp;
                            if-eqz v2, :child_k
                            invoke-virtual { v0, v2 }, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
                            :child_k
                            iget-object v2, v10, $definingClass->k:Lwi30;
                            if-eqz v2, :child_l
                            invoke-virtual { v0, v2 }, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
                            :child_l
                            iget-object v2, v10, $definingClass->l:Ljf40;
                            if-eqz v2, :child_m
                            invoke-virtual { v0, v2 }, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
                            :child_m
                            iget-object v2, v10, $definingClass->m:Loxi0;
                            if-eqz v2, :child_n
                            invoke-virtual { v0, v2 }, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
                            :child_n
                            iget-object v2, v10, $definingClass->n:Llg20;
                            invoke-virtual { v0, v2 }, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
                            iget-object v2, v10, $definingClass->o:Lpt8;
                            if-eqz v2, :child_p
                            invoke-virtual { v0, v2 }, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
                            :child_p
                            iget-object v2, v10, $definingClass->p:Lef10;
                            if-eqz v2, :child_q
                            invoke-virtual { v0, v2 }, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
                            :child_q
                            iget-object v2, v10, $definingClass->q:Lcki;
                            if-eqz v2, :child_r
                            invoke-virtual { v0, v2 }, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
                            :child_r
                            iget-object v2, v10, $definingClass->r:Lcki;
                            if-eqz v2, :child_s
                            invoke-virtual { v0, v2 }, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
                            :child_s
                            iget-object v2, v10, $definingClass->s:Lcki;
                            if-eqz v2, :child_t
                            invoke-virtual { v0, v2 }, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
                            :child_t
                            iget-object v2, v10, $definingClass->t:Lcki;
                            if-eqz v2, :child_u
                            invoke-virtual { v0, v2 }, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
                            :child_u
                            iget-object v2, v10, $definingClass->u:Lcki;
                            if-eqz v2, :compact
                            invoke-virtual { v0, v2 }, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
                            :compact
                            invoke-static { v0 }, Lfs80;->X(Ljava/lang/Iterable;)Lv4p;
                            move-result-object v0
                            iput-object v0, v10, $definingClass->B:Lv4p;
                            :return
                            return-void
                        """
                    )
                }
            )
        }

        FeedPostSectionToStringFingerprint.let {
            val linkIdField = it.instructionMatches.first().getFieldAccessed()

            it.classDef.apply {
                val definingClass = this.type
                interfaces.add(CACHED_POST_INTERFACE)

                methods.add(
                    ImmutableMethod(
                        definingClass,
                        "patch_getLinkId",
                        emptyList(),
                        "Ljava/lang/String;",
                        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                        emptySet(), null, MutableMethodImplementation(1)
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
                        definingClass,
                        "patch_addHomeFlair",
                        listOf(
                            ImmutableMethodParameter("Ljava/lang/String;", emptySet(), "flair"),
                            ImmutableMethodParameter("Ljava/lang/String;", emptySet(), "community"),
                            ImmutableMethodParameter("Ljava/lang/String;", emptySet(), "textColor"),
                            ImmutableMethodParameter("Ljava/lang/String;", emptySet(), "backgroundColor")
                        ),
                        "V",
                        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                        emptySet(), null, MutableMethodImplementation(24)
                    ).toMutable().apply {
                        // TODO: remove hard coded class names/methods with fingerprints
                        //       that work on all supported versions of Reddit.
                        addInstructions(
                            0,
                            """
                                move-object/from16 v0, p0
                                new-instance v1, Lje20;
                                move-object/from16 v2, p1
                                const/4 v3, 0x0
                                iget-object v4, v0, $linkIdField
                                move-object/from16 v5, p2
                                const-string v6, ""
                                move-object/from16 v7, p3
                                move-object/from16 v8, p4
                                move-object v9, v2
                                move-object v10, v2
                                invoke-direct/range { v1 .. v10 }, Lje20;-><init>(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
                                const/4 v11, 0x1
                                new-array v12, v11, [Ljava/lang/Object;
                                const/4 v11, 0x0
                                aput-object v1, v12, v11
                                invoke-static { v12 }, Lfs80;->R([Ljava/lang/Object;)Lgo00;
                                move-result-object v12
                                new-instance v13, Llg20;
                                move-object v14, v4
                                iget-object v11, v0, $definingClass->c:Ljava/lang/String;
                                move-object/from16 v15, v11
                                const/16 v16, 0x0
                                iget-object v11, v0, $definingClass->d:Lni20;
                                move-object/from16 v17, v11
                                move-object/from16 v18, v12
                                invoke-direct/range { v13 .. v18 }, Llg20;-><init>(Ljava/lang/String;Ljava/lang/String;ZLni20;Lv4p;)V
                                new-instance v1, Lmg20;
                                invoke-direct { v1, v13 }, Lmg20;-><init>(Llg20;)V
                                move-object/from16 v10, p0
                                new-instance v0, Ljava/util/ArrayList;
                                invoke-direct { v0 }, Ljava/util/ArrayList;-><init>()V
                                iget-object v2, v10, $definingClass->b:Lv4p;
                                invoke-interface { v2 }, Ljava/lang/Iterable;->iterator()Ljava/util/Iterator;
                                move-result-object v3
                                const/4 v6, -0x1
                                :copy_children
                                invoke-interface { v3 }, Ljava/util/Iterator;->hasNext()Z
                                move-result v4
                                if-eqz v4, :insert_flair
                                invoke-interface { v3 }, Ljava/util/Iterator;->next()Ljava/lang/Object;
                                move-result-object v5
                                instance-of v4, v5, Lmg20;
                                if-nez v4, :return_section
                                invoke-virtual { v0, v5 }, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
                                instance-of v4, v5, Lusi;
                                if-eqz v4, :copy_children
                                invoke-virtual { v0 }, Ljava/util/ArrayList;->size()I
                                move-result v6
                                goto :copy_children
                                :insert_flair
                                if-ltz v6, :append_flair
                                invoke-virtual { v0, v6, v1 }, Ljava/util/ArrayList;->add(ILjava/lang/Object;)V
                                goto :compact_section
                                :append_flair
                                invoke-virtual { v0, v1 }, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
                                :compact_section
                                invoke-static { v0 }, Lfs80;->X(Ljava/lang/Iterable;)Lv4p;
                                move-result-object v0
                                iput-object v0, v10, $definingClass->b:Lv4p;
                                :return_section
                                return-void
                            """
                        )
                    }
                )
            }
        }

        val feedPostType = FeedPostSectionToStringFingerprint.classDef.type
        Fingerprint(
            filters = listOf(
                resourceLiteral(ResourceType.DIMEN, "followable_search_result_image_size"),
                newInstance(feedPostType),
                opcode(Opcode.RETURN_OBJECT)
            )
        ).let {
            val sectionRegister = it.instructionMatches[1].getInstruction<OneRegisterInstruction>().registerA
            val sectionReturnIndex = it.instructionMatches.last().index

            it.method.addInstructions(
                sectionReturnIndex,
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

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
