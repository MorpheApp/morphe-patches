/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5153
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.androidauto

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patcher.util.cloneMutable
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.registersUsed
import app.morphe.util.fieldByName
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference

private const val SETTINGS_TAB_CLASS =
    "Lapp/morphe/extension/music/patches/AndroidAutoSettingsTab;"
private const val SETTINGS_TEMPLATE_SOURCE =
    $$"Lapp/morphe/extension/music/patches/AndroidAutoSettingsTab$SettingsTemplateSource;"
private const val STOCK_TOGGLE =
    $$"Lapp/morphe/extension/music/patches/AndroidAutoSettingsTab$StockToggle;"
private const val STOCK_TOGGLE_DELEGATE =
    $$"Lapp/morphe/extension/music/patches/AndroidAutoSettingsTab$StockToggleDelegate;"
private const val CAR_APP_SERVICE_CLASS =
    "Lcom/google/android/apps/youtube/music/mediabrowser/androidautoprojected/AndroidAutoCarAppServices;"
private const val CAR_MODEL_PACKAGE = "Landroidx/car/app/model/"
private const val CONTEXT_CLASS = "Landroid/content/Context;"

/**
 * Android Auto rejects the car app service of a patched app, and YouTube Music crashes when the
 * stock Settings button opens it. Hide that button and show the same settings in a browse tab.
 */
internal fun patchSettingsTab() {
    AndroidAutoSettingsIntentFingerprint.let {
        // Android Auto shows the stock button only if the browser root extras contain this intent.
        it.method.removeInstruction(it.instructionMatches.last().index)
    }

    val controllerType = addSettingsTemplateSource()
    hookMusicBrowserServiceDependency(
        controllerType,
        SETTINGS_TEMPLATE_SOURCE,
        "$SETTINGS_TAB_CLASS->setSettingsSource($SETTINGS_TEMPLATE_SOURCE)V",
        // The controller type alone matches several providers in 9.44.52 through 9.46.51.
        // Use the provider that the car app service reads in its superclass.
        providerReaderClass = classDefBy(CAR_APP_SERVICE_CLASS).superclass
            ?: throw PatchException("Could not resolve the car app service superclass"),
    )

    // Applying a choice calls the stock toggle listener, which the toggle keeps in its delegate.
    mutableClassDefBy("${CAR_MODEL_PACKAGE}Toggle;").apply {
        addPatchInterface(STOCK_TOGGLE, "patch_getDelegate", fieldByName("mOnCheckedChangeDelegate"))
    }
    mutableClassDefBy("${CAR_MODEL_PACKAGE}OnCheckedChangeDelegateImpl;").apply {
        addPatchInterface(STOCK_TOGGLE_DELEGATE, "patch_getListener", fieldByName("mStub"))
    }
}

/**
 * Copies the method that builds the stock settings screen onto the settings controller,
 * so MusicBrowserService can build the same rows without a car screen.
 * The copy takes a Context in place of the screen's CarContext.
 *
 * @return The settings controller type.
 */
private fun addSettingsTemplateSource(): String {
    val match = StockSettingsTemplateFingerprint
    val contextField = match.instructionMatches[0].fieldReference
    val controllerField = match.instructionMatches[2].fieldReference
    val controllerType = controllerField.type

    val templateMethod = match.originalMethod.cloneMutable(
        name = "patch_getSettingsTemplate",
        accessFlags = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
        parameters = listOf(ImmutableMethodParameter(CONTEXT_CLASS, null, null)),
        returnType = "${CAR_MODEL_PACKAGE}ListTemplate;",
        additionalRegisters = 1,
    ).apply {
        setDefiningClass(controllerType)

        // In the copy, p0 is the controller and p1 is the context.
        fun replaceFieldRead(field: FieldReference, register: String) =
            findInstructionIndicesReversedOrThrow(fieldAccess(field, Opcode.IGET_OBJECT)).forEach { index ->
                val targetRegister = getInstruction<TwoRegisterInstruction>(index).registerA
                replaceInstruction(index, "move-object/from16 v$targetRegister, $register")
            }
        replaceFieldRead(contextField, "p1")
        replaceFieldRead(controllerField, "p0")

        // CarContext extends ContextWrapper, so call the inherited methods on Context.
        findInstructionIndicesReversedOrThrow(methodCall(definingClass = contextField.type)).forEach { index ->
            val instruction = getInstruction(index)
            val call = instruction.getReference<MethodReference>()!!
            check(call.name == "getString" || call.name == "getResources") {
                "Unexpected CarContext call: $call"
            }
            val contextCall = ImmutableMethodReference(
                CONTEXT_CLASS, call.name, call.parameterTypes, call.returnType,
            )
            val registers = instruction.registersUsed.joinToString { "v$it" }
            replaceInstruction(index, "invoke-virtual { $registers }, $contextCall")
        }
    }

    mutableClassDefBy(controllerType).apply {
        interfaces.add(SETTINGS_TEMPLATE_SOURCE)
        methods.add(templateMethod)
    }
    return controllerType
}
