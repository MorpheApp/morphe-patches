/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.fix.preference

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.cloneParameters
import app.morphe.patcher.util.getFreeRegisterProvider
import app.morphe.patcher.util.p0Register
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_21_14_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import java.lang.ref.WeakReference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/FixPreferenceIconPatch;"

private lateinit var preferenceScreenMethodRef: WeakReference<MutableMethod>
private lateinit var fragmentField: FieldReference
private lateinit var getPreferenceScreenMethod: MethodReference

/**
 * Hook of the method that runs after the YouTube settings screen is built.
 */
internal val preferenceScreenHookPatch = patch {
    execute {
        PreferenceScreenSyntheticFingerprint.let {
            getPreferenceScreenMethod = it.instructionMatches[1].methodReference
            fragmentField = it.classDef.fields.single { field ->
                field.type == getPreferenceScreenMethod.definingClass
            }

            // 21.45+ reuses the fragment register and p0 before returning,
            // so p0 is kept unchanged and the fragment is read again from it.
            preferenceScreenMethodRef = WeakReference(it.method.cloneParameters())

            // The hooks are added to the cloned method.
            it.clearMatch()
        }
    }
}

/**
 * Adds [instructions] before the settings screen method returns.
 * [instructions] gets the register of the settings screen (not null),
 * and [freeRegisterCount] free registers.
 */
internal fun addPreferenceScreenHook(
    freeRegisterCount: Int,
    instructions: (screenRegister: Int, freeRegisters: List<Int>) -> String
) {
    preferenceScreenMethodRef.get()!!.apply {
        val insertIndex = indexOfFirstInstructionReversedOrThrow { opcode == Opcode.RETURN_VOID }
        val registerProvider = getFreeRegisterProvider(insertIndex, freeRegisterCount + 1, p0Register)
        val screenRegister = registerProvider.getFreeRegister()
        val freeRegisters = List(freeRegisterCount) { registerProvider.getFreeRegister() }

        addInstructionsAtControlFlowLabel(
            insertIndex,
            """
                # p0 can be above v15 after the parameters are cloned.
                move-object/from16 v$screenRegister, p0
                iget-object v$screenRegister, v$screenRegister, $fragmentField
                invoke-virtual { v$screenRegister }, $getPreferenceScreenMethod
                move-result-object v$screenRegister
                if-eqz v$screenRegister, :morphe_preference_screen_hook_exit

                ${instructions(screenRegister, freeRegisters)}

                :morphe_preference_screen_hook_exit
                nop
            """
        )
    }
}

/**
 * Fixes https://github.com/MorpheApp/morphe-patches/issues/1117.
 */
internal val fixPreferenceIconPatch = patch{
    dependsOn(
        sharedExtensionPatch,
        versionCheckPatch,
        preferenceScreenHookPatch,
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
                .fieldReference

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
                            invoke-static { }, $EXTENSION_CLASS->removePreferenceIcon()Z
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

        addPreferenceScreenHook(0) { screenRegister, _ ->
            "invoke-virtual { v$screenRegister }, $helperMethod"
        }
    }
}
