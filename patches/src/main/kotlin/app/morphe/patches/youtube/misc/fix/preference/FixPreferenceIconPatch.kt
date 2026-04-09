/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.fix.preference

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_21_14_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findFreeRegister
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/patches/FixPreferenceIconPatch;"

/**
 * Fixes https://github.com/MorpheApp/morphe-patches/issues/1117.
 */
internal val fixPreferenceIconPatch = bytecodePatch{
    dependsOn(
        sharedExtensionPatch,
        versionCheckPatch,
    )

    execute {
        if (!is_21_14_or_greater) {
            return@execute
        }

        val setPreferenceIconMethodCall = SetPreferenceIconFingerprint.method
        val setPreferenceIconSpaceReservedMethodCall = SetPreferenceIconSpaceReservedFingerprint.method

        val helperMethod: MutableMethod

        FindPreferenceByIndexFingerprint.let {
            val getAllPreferenceField = it.instructionMatches.last()
                .instruction.getReference<FieldReference>()!!

            it.classDef.apply {
                helperMethod = ImmutableMethod(
                    type,
                    "patch_removePreferenceIcon",
                    listOf(),
                    "V",
                    AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                    annotations,
                    null,
                    MutableMethodImplementation(5),
                ).toMutable().apply {
                    addInstructionsWithLabels(
                        0,
                        """
                            invoke-static { }, $EXTENSION_CLASS_DESCRIPTOR->removePreferenceIcon()Z
                            move-result v0

                            if-eqz v0, :exit
                            iget-object v0, p0, $getAllPreferenceField
                            invoke-interface { v0 }, Ljava/util/List;->iterator()Ljava/util/Iterator;
                            move-result-object v1
                            
                            :loop
                            invoke-interface { v1 }, Ljava/util/Iterator;->hasNext()Z
                            move-result v2

                            if-eqz v2, :exit
                            invoke-interface { v1 }, Ljava/util/Iterator;->next()Ljava/lang/Object;
                            move-result-object v2
                            instance-of v3, v2, Landroidx/preference/Preference;

                            if-eqz v3, :loop
                            check-cast v2, Landroidx/preference/Preference;

                            # setIconSpaceReserved(false).
                            const/4 v3, 0x0
                            invoke-virtual { v2, v3 }, $setPreferenceIconSpaceReservedMethodCall

                            # setIcon(null).
                            const/4 v3, 0x0
                            invoke-virtual { v2, v3 }, $setPreferenceIconMethodCall

                            goto :loop

                            :exit
                            return-void
                        """
                    )
                }

                methods.add(helperMethod)
            }
        }

        PreferenceScreenSyntheticFingerprint.let {
            it.method.apply {
                val getPreferenceScreenIndex = it.instructionMatches[1].index
                val getPreferenceScreenRegister =
                    getInstruction<FiveRegisterInstruction>(getPreferenceScreenIndex).registerC
                val getPreferenceScreenReference =
                    getInstruction<ReferenceInstruction>(getPreferenceScreenIndex).reference

                val insertIndex = it.instructionMatches.last().index
                val preferenceScreenRegister =
                    findFreeRegister(insertIndex, getPreferenceScreenRegister)

                addInstructionsAtControlFlowLabel(
                    insertIndex,
                    """
                        invoke-virtual { v$getPreferenceScreenRegister }, $getPreferenceScreenReference
                        move-result-object v$preferenceScreenRegister
                        
                        if-eqz v$preferenceScreenRegister, :ignore
                        
                        invoke-virtual { v$preferenceScreenRegister }, $helperMethod
                        
                        :ignore
                        nop
                    """
                )
            }
        }
    }
}