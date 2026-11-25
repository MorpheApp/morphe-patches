/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5334
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.sidebar

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.checkCast
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.getFreeRegisterProvider
import app.morphe.patcher.util.p0Register
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_40_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/SettingsInSidebarPatch;"

private const val COMMUNITY_DRAWER_SCREEN_CLASS =
    "Lcom/reddit/screens/drawer/community/CommunityDrawerScreen;"

// Negative uniqueId far from stock AtomicLong.getAndDecrement() row ids.
private const val SETTINGS_ROW_ID = Long.MIN_VALUE + 0x4d6f72L

@Suppress("unused")
val settingsInSidebarPatch = patch(
    name = "Settings in sidebar",
    description = "Adds a Morphe settings row to the top of the sidebar. " +
        "This patch works with Reddit 2026.40.0 and newer.",
    default = true
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        if (!is_2026_40_0_or_greater) {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "'Settings in sidebar' does not work with Reddit 2026.39.0 and earlier"
            )
        }

        val ctaClass = CtaItemUiModelToStringFingerprint.classDef
        val ctaType = ctaClass.type
        val addSettingsRowMethod = ImmutableMethod(
            ctaType,
            "patch_addSettingsRow",
            listOf(ImmutableMethodParameter("Ljava/util/List;", null, null)),
            "Ljava/util/List;",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null,
            null,
            MutableMethodImplementation(7)
        ).toMutable().apply {
            addInstructions(
                0,
                """
                    new-instance v0, $ctaType
                    const/4 v1, 0x0
                    invoke-static { }, $EXTENSION_CLASS->getSettingsRowTitleResId()I
                    move-result v2
                    invoke-static { }, $EXTENSION_CLASS->getSettingsRowIconResId()I
                    move-result v3
                    const-wide v4, ${SETTINGS_ROW_ID}L
                    invoke-direct/range { v0 .. v5 }, $ctaType-><init>(ZIIJ)V
                    invoke-static { p0, v0 }, $EXTENSION_CLASS->addSettingsRow(Ljava/util/List;Ljava/lang/Object;)Ljava/util/List;
                    move-result-object v0
                    return-object v0
                """
            )
        }.also(ctaClass.methods::add)

        // Shared with HideSidebarComponentsPatch; clear cached match in case it already modified the method.
        CommunityDrawerBindListFingerprint.clearMatch()
        val listField = CommunityDrawerBindListFingerprint.let {
            val index = it.instructionMatches.first().index
            val field = it.instructionMatches.first().fieldReference
            it.method.apply {
                val presenterRegister = getInstruction<TwoRegisterInstruction>(index).registerB
                val freeRegister = findFreeRegister(index, presenterRegister)

                addInstructions(
                    index,
                    """
                        iget-object v$freeRegister, v$presenterRegister, $field
                        invoke-static/range { v$freeRegister .. v$freeRegister }, $addSettingsRowMethod
                        move-result-object v$freeRegister
                        iput-object v$freeRegister, v$presenterRegister, $field
                    """
                )
            }
            field
        }

        val presenterType = listField.definingClass
        val screenField = classDefBy(presenterType).fields.single { field ->
            field.type == COMMUNITY_DRAWER_SCREEN_CLASS
        }

        val clickMatch = Fingerprint(
            definingClass = presenterType,
            isStatic = false,
            returnType = "V",
            parameters = listOf("L"),
            filters = listOf(
                checkCast(ctaType),
                fieldAccess(
                    $$"Lcom/reddit/navdrawer/analytics/CommunityDrawerAnalytics$Noun;->" +
                        $$"REDDIT_RULES:Lcom/reddit/navdrawer/analytics/CommunityDrawerAnalytics$Noun;"
                ),
                methodCall(
                    definingClass = COMMUNITY_DRAWER_SCREEN_CLASS,
                    returnType = "V",
                    parameters = listOf()
                )
            )
        ).match()

        clickMatch.method.apply {
            val checkCastMatch = clickMatch.instructionMatches[0]
            val insertIndex = checkCastMatch.index + 1
            val castRegister = checkCastMatch.getInstruction<OneRegisterInstruction>().registerA
            val closeSidebarMethod = clickMatch.instructionMatches.last().methodReference
            val freeRegister = getFreeRegisterProvider(insertIndex, 1, castRegister, p0Register)
                .getFreeRegister4Bit()

            addInstructionsWithLabels(
                insertIndex,
                """
                    invoke-static/range { v$castRegister .. v$castRegister }, $EXTENSION_CLASS->isSettingsRow(Ljava/lang/Object;)Z
                    move-result v$freeRegister
                    if-eqz v$freeRegister, :ignore
                    move-object/from16 v$freeRegister, p0
                    iget-object v$freeRegister, v$freeRegister, $screenField
                    invoke-virtual { v$freeRegister }, $closeSidebarMethod
                    invoke-static { }, $EXTENSION_CLASS->openSettings()V
                    return-void
                    :ignore
                    nop
                """
            )
        }
    }
}
