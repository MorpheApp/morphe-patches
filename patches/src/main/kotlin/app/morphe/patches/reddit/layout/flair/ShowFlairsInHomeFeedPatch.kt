/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.flair

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/ShowFlairsInHomeFeedPatch;"
private const val CACHED_POST_CLASS = "Lw230;"
private const val FEED_POST_SECTION_CLASS = "Llsi;"
private const val FEED_SECTION_MAPPER_CLASS = "Lcex;"
private const val LINK_CLASS = "Lcom/reddit/domain/model/Link;"
private const val CACHED_POST_INTERFACE =
    "Lapp/morphe/extension/reddit/patches/ShowFlairsInHomeFeedPatch\u0024CachedPost;"

private val COMPATIBILITY_REDDIT_2026_37 = Compatibility(
    name = "Reddit",
    packageName = "com.reddit.frontpage",
    apkFileType = ApkFileType.APKM,
    appIconColor = 0xFF4500,
    signatures = setOf("970b91143813b4c9d5f3634f672c9fcaa5621b4efaaedafd6c235cbbb869736f"),
    targets = listOf(AppTarget(version = "2026.37.0", minSdk = 28, isExperimental = true))
)

@Suppress("unused")
val showFlairsInHomeFeedPatch = bytecodePatch(
    name = "Show flairs in home feed",
    description = "Adds an option to show post flair badges in the home feed."
) {
    compatibleWith(COMPATIBILITY_REDDIT_2026_37)
    dependsOn(settingsPatch)

    execute {
        val linkClass = mutableClassDefBy(LINK_CLASS)
        linkClass.methods.add(
            ImmutableMethod(
                LINK_CLASS,
                "morpheRememberHomeFlair",
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
                        invoke-virtual { p0 }, $LINK_CLASS->getId()Ljava/lang/String;
                        move-result-object v0
                        invoke-virtual { p0 }, $LINK_CLASS->getKindWithId()Ljava/lang/String;
                        move-result-object v1
                        invoke-virtual { p0 }, $LINK_CLASS->getSubreddit()Ljava/lang/String;
                        move-result-object v2
                        invoke-virtual { p0 }, $LINK_CLASS->getLinkFlairText()Ljava/lang/String;
                        move-result-object v3
                        invoke-virtual { p0 }, $LINK_CLASS->getLinkFlairBackgroundColor()Ljava/lang/String;
                        move-result-object v4
                        invoke-virtual { p0 }, $LINK_CLASS->getLinkFlairTextColor()Ljava/lang/String;
                        move-result-object v5
                        invoke-static/range { v0 .. v5 }, $EXTENSION_CLASS->rememberFlair(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V
                        return-void
                    """
                )
            }
        )

        val linkConstructor = linkClass.methods.singleOrNull { method ->
            method.name == "<init>" && method.implementation?.instructions?.any { instruction ->
                if (instruction.opcode != Opcode.IPUT_BOOLEAN || instruction !is ReferenceInstruction) {
                    false
                } else {
                    val field = instruction.reference as? FieldReference
                    field?.definingClass == LINK_CLASS && field.name == "isBlankAd"
                }
            } == true
        } ?: throw PatchException("Link constructor assigning isBlankAd was not found")

        val linkParameterWords = linkConstructor.parameterTypes.sumOf { type ->
            if (type == "J" || type == "D") 2 else 1
        }
        val linkInstanceRegister = linkConstructor.implementation!!.registerCount - linkParameterWords - 1
        linkConstructor.implementation!!.instructions.withIndex()
            .filter { it.value.opcode == Opcode.RETURN_VOID }
            .map { it.index }
            .toList()
            .asReversed()
            .forEach { returnIndex ->
                linkConstructor.addInstructions(
                    returnIndex,
                    "invoke-virtual/range { v$linkInstanceRegister .. v$linkInstanceRegister }, $LINK_CLASS->morpheRememberHomeFlair()V"
                )
            }

        val cachedPostClass = mutableClassDefBy(CACHED_POST_CLASS)
        cachedPostClass.interfaces.add(CACHED_POST_INTERFACE)
        cachedPostClass.methods.add(
            ImmutableMethod(
                CACHED_POST_CLASS,
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
                        iget-object v0, p0, Lcki;->a:Ljava/lang/String;
                        return-object v0
                    """
                )
            }
        )
        cachedPostClass.methods.add(
            ImmutableMethod(
                CACHED_POST_CLASS,
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
                addInstructions(0, HOME_FLAIR_METHOD)
            }
        )

        val feedPostSectionClass = mutableClassDefBy(FEED_POST_SECTION_CLASS)
        feedPostSectionClass.interfaces.add(CACHED_POST_INTERFACE)
        feedPostSectionClass.methods.add(
            ImmutableMethod(
                FEED_POST_SECTION_CLASS,
                "patch_getLinkId",
                emptyList(),
                "Ljava/lang/String;",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                emptySet(), null, MutableMethodImplementation(1)
            ).toMutable().apply {
                addInstructions(0, "iget-object v0, p0, $FEED_POST_SECTION_CLASS->a:Ljava/lang/String;\nreturn-object v0")
            }
        )
        feedPostSectionClass.methods.add(
            ImmutableMethod(
                FEED_POST_SECTION_CLASS,
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
                addInstructions(0, HOME_SECTION_FLAIR_METHOD)
            }
        )

        val sectionMapper = mutableClassDefBy(FEED_SECTION_MAPPER_CLASS).methods.singleOrNull { method ->
            method.implementation?.instructions?.any { instruction ->
                instruction.opcode == Opcode.NEW_INSTANCE &&
                    (instruction as? ReferenceInstruction)?.reference?.toString() == FEED_POST_SECTION_CLASS
            } == true
        } ?: throw PatchException("Home-feed section mapper was not found")
        val sectionInstructions = sectionMapper.implementation!!.instructions
        val sectionCreationIndex = sectionInstructions.indexOfFirst { instruction ->
            instruction.opcode == Opcode.NEW_INSTANCE &&
                (instruction as? ReferenceInstruction)?.reference?.toString() == FEED_POST_SECTION_CLASS
        }
        if (sectionCreationIndex < 0) throw PatchException("Home-feed section creation was not found")
        val sectionRegister = (sectionInstructions[sectionCreationIndex] as
            com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction).registerA
        val sectionReturnIndex = sectionInstructions.withIndex().firstOrNull { indexed ->
            indexed.index > sectionCreationIndex && indexed.value.opcode == Opcode.RETURN_OBJECT &&
                (indexed.value as? com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction)
                    ?.registerA == sectionRegister
        }?.index ?: throw PatchException("Home-feed section return was not found")
        sectionMapper.addInstructions(
            sectionReturnIndex,
            """
                invoke-static/range { v$sectionRegister .. v$sectionRegister }, $EXTENSION_CLASS->showHomeFlair(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v$sectionRegister
                check-cast v$sectionRegister, $FEED_POST_SECTION_CLASS
            """
        )

        FeedElementProcessorFingerprint.method.apply {
            val parameterWords = parameterTypes.sumOf { type ->
                if (type == "J" || type == "D") 2 else 1
            }
            val firstExplicitParameter = implementation!!.registerCount - parameterWords
            val elementsRegister = firstExplicitParameter + 1
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

private val HOME_SECTION_FLAIR_METHOD = """
    move-object/from16 v0, p0
    new-instance v1, Lje20;
    move-object/from16 v2, p1
    const/4 v3, 0x0
    iget-object v4, v0, $FEED_POST_SECTION_CLASS->a:Ljava/lang/String;
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
    iget-object v11, v0, $FEED_POST_SECTION_CLASS->c:Ljava/lang/String;
    move-object/from16 v15, v11
    const/16 v16, 0x0
    iget-object v11, v0, $FEED_POST_SECTION_CLASS->d:Lni20;
    move-object/from16 v17, v11
    move-object/from16 v18, v12
    invoke-direct/range { v13 .. v18 }, Llg20;-><init>(Ljava/lang/String;Ljava/lang/String;ZLni20;Lv4p;)V
    new-instance v1, Lmg20;
    invoke-direct { v1, v13 }, Lmg20;-><init>(Llg20;)V
    move-object/from16 v10, p0
    new-instance v0, Ljava/util/ArrayList;
    invoke-direct { v0 }, Ljava/util/ArrayList;-><init>()V
    iget-object v2, v10, $FEED_POST_SECTION_CLASS->b:Lv4p;
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
    iput-object v0, v10, $FEED_POST_SECTION_CLASS->b:Lv4p;
    :return_section
    return-void
"""

private val HOME_FLAIR_METHOD = """
    move-object/from16 v0, p0
    iget-object v11, v0, $CACHED_POST_CLASS->n:Llg20;
    if-nez v11, :return

    new-instance v1, Lje20;
    move-object/from16 v2, p1
    const/4 v3, 0x0
    iget-object v4, v0, Lcki;->a:Ljava/lang/String;
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
    iget-object v11, v0, $CACHED_POST_CLASS->h:Lni20;
    move-object/from16 v17, v11
    move-object/from16 v18, v12
    invoke-direct/range { v13 .. v18 }, Llg20;-><init>(Ljava/lang/String;Ljava/lang/String;ZLni20;Lv4p;)V
    iput-object v13, v0, $CACHED_POST_CLASS->n:Llg20;

    move-object/from16 v10, p0
    new-instance v0, Ljava/util/ArrayList;
    const/16 v1, 0xd
    invoke-direct { v0, v1 }, Ljava/util/ArrayList;-><init>(I)V
    iget-object v2, v10, $CACHED_POST_CLASS->i:Ljiu;
    if-eqz v2, :child_j
    invoke-virtual { v0, v2 }, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
    :child_j
    iget-object v2, v10, $CACHED_POST_CLASS->j:Lwmp;
    if-eqz v2, :child_k
    invoke-virtual { v0, v2 }, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
    :child_k
    iget-object v2, v10, $CACHED_POST_CLASS->k:Lwi30;
    if-eqz v2, :child_l
    invoke-virtual { v0, v2 }, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
    :child_l
    iget-object v2, v10, $CACHED_POST_CLASS->l:Ljf40;
    if-eqz v2, :child_m
    invoke-virtual { v0, v2 }, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
    :child_m
    iget-object v2, v10, $CACHED_POST_CLASS->m:Loxi0;
    if-eqz v2, :child_n
    invoke-virtual { v0, v2 }, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
    :child_n
    iget-object v2, v10, $CACHED_POST_CLASS->n:Llg20;
    invoke-virtual { v0, v2 }, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
    iget-object v2, v10, $CACHED_POST_CLASS->o:Lpt8;
    if-eqz v2, :child_p
    invoke-virtual { v0, v2 }, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
    :child_p
    iget-object v2, v10, $CACHED_POST_CLASS->p:Lef10;
    if-eqz v2, :child_q
    invoke-virtual { v0, v2 }, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
    :child_q
    iget-object v2, v10, $CACHED_POST_CLASS->q:Lcki;
    if-eqz v2, :child_r
    invoke-virtual { v0, v2 }, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
    :child_r
    iget-object v2, v10, $CACHED_POST_CLASS->r:Lcki;
    if-eqz v2, :child_s
    invoke-virtual { v0, v2 }, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
    :child_s
    iget-object v2, v10, $CACHED_POST_CLASS->s:Lcki;
    if-eqz v2, :child_t
    invoke-virtual { v0, v2 }, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
    :child_t
    iget-object v2, v10, $CACHED_POST_CLASS->t:Lcki;
    if-eqz v2, :child_u
    invoke-virtual { v0, v2 }, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
    :child_u
    iget-object v2, v10, $CACHED_POST_CLASS->u:Lcki;
    if-eqz v2, :compact
    invoke-virtual { v0, v2 }, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
    :compact
    invoke-static { v0 }, Lfs80;->X(Ljava/lang/Iterable;)Lv4p;
    move-result-object v0
    iput-object v0, v10, $CACHED_POST_CLASS->B:Lv4p;
    :return
    return-void
"""
