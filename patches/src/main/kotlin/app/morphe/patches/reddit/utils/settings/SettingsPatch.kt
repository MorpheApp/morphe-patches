package app.morphe.patches.reddit.utils.settings

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.stringOption
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.reddit.utils.compatibility.Constants.COMPATIBLE_PACKAGE
import app.morphe.patches.reddit.utils.extension.Constants.EXTENSION_PATH
import app.morphe.patches.reddit.utils.extension.sharedExtensionPatch
import app.morphe.patches.reddit.utils.fix.signature.spoofSignaturePatch
import app.morphe.patches.reddit.utils.patch.PatchList
import app.morphe.patches.reddit.utils.patch.PatchList.SETTINGS_FOR_REDDIT
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstruction
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import app.morphe.util.methodSignature
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val EXTENSION_CLASS_DESCRIPTOR =
    "$EXTENSION_PATH/settings/ActivityHook;"

private lateinit var acknowledgementsLabelBuilderMethod: MutableMethod
private lateinit var settingsStatusLoadMethod: MutableMethod

var is_2025_45_or_greater = false
    private set

var is_2025_52_or_greater = false
    private set

var is_2026_03_or_greater = false
    private set

private const val DEFAULT_LABEL = "RVX"

val settingsPatch = bytecodePatch(
    SETTINGS_FOR_REDDIT.title,
    SETTINGS_FOR_REDDIT.summary,
) {
    compatibleWith(COMPATIBLE_PACKAGE)

    dependsOn(
        sharedExtensionPatch,
        spoofSignaturePatch
    )

    val rvxSettingsLabel = stringOption(
        key = "rvxSettingsLabel",
        default = DEFAULT_LABEL,
        values = mapOf(
            "RVX Morphed" to "RVX Morphed",
            "RVX" to DEFAULT_LABEL,
        ),
        title = "RVX settings menu name",
        description = "The name of the RVX settings menu.",
        required = true
    )

    execute {

        /**
         * Set version info
         */
        redditInternalFeaturesFingerprint.method.apply {
            val versionIndex = indexOfFirstInstructionOrThrow {
                opcode == Opcode.CONST_STRING
                        && (this as? BuilderInstruction21c)?.reference.toString().startsWith("202")
            }

            val versionNumber =
                getInstruction<BuilderInstruction21c>(versionIndex).reference.toString()
                    .replace(".", "").toInt()

            is_2025_45_or_greater = 2025450 <= versionNumber
            is_2025_52_or_greater = 2025520 <= versionNumber
            is_2026_03_or_greater = 2026030 <= versionNumber
        }

        /**
         * Set SharedPrefCategory
         */
        sharedSettingFingerprint.method.apply {
            val stringIndex = indexOfFirstInstructionOrThrow(Opcode.CONST_STRING)
            val stringRegister = getInstruction<OneRegisterInstruction>(stringIndex).registerA

            replaceInstruction(
                stringIndex,
                "const-string v$stringRegister, \"reddit_rvx_morphed\""
            )
        }

        /**
         * Replace settings label
         */

        acknowledgementsLabelBuilderMethod = preferenceManagerFingerprint.match(
            mutableClassDefBy(preferenceManagerParentFingerprint.classDef)
        ).method
        updateSettingsLabel(rvxSettingsLabel.value!!)

        /**
         * Initialize settings activity
         */
        val context = this
        preferenceDestinationFingerprint.match().let {
            it.method.apply {
                val targetIndex = it.instructionMatches.first().index + 2
                val targetRegister =
                    getInstruction<FiveRegisterInstruction>(targetIndex).registerC
                val targetReference =
                    getInstruction<ReferenceInstruction>(targetIndex).reference as MethodReference
                val targetClass = targetReference.definingClass
                val getActivityReference =
                    context.mutableClassDefBy { classDef ->
                        classDef.type == targetClass
                    }.methods.find { methodDef ->
                        methodDef.name == "getActivity"
                    }!!.methodSignature()

                val freeIndex = targetIndex + 1
                val freeRegister =
                    getInstruction<OneRegisterInstruction>(freeIndex).registerA

                addInstructionsWithLabels(
                    targetIndex, """
                        invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS_DESCRIPTOR->isAcknowledgment(Ljava/lang/Enum;)Z
                        move-result v$freeRegister
                        if-eqz v$freeRegister, :ignore
                        invoke-virtual {v$targetRegister}, $getActivityReference
                        move-result-object v$freeRegister
                        invoke-static {v$freeRegister}, $EXTENSION_CLASS_DESCRIPTOR->initializeByIntent(Landroid/content/Context;)Landroid/content/Intent;
                        move-result-object v$freeRegister
                        invoke-virtual {v$targetRegister, v$freeRegister}, $targetClass->startActivity(Landroid/content/Intent;)V
                        return-void
                        :ignore
                        nop
                        """
                )
            }
        }

        webBrowserActivityOnCreateFingerprint.method.let {
            it.apply {
                val stringIndex = indexOfFirstInstructionOrThrow(Opcode.CONST_STRING)
                val freeRegister =
                    getInstruction<OneRegisterInstruction>(stringIndex).registerA

                val insertIndex = indexOfFirstInstructionOrThrow {
                    getReference<MethodReference>()?.toString() == "Landroid/app/Activity;->getIntent()Landroid/content/Intent;"
                }

                addInstructionsWithLabels(
                    insertIndex, """
                        invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS_DESCRIPTOR->hook(Landroid/app/Activity;)Z
                        move-result v$freeRegister
                        if-eqz v$freeRegister, :ignore
                        return-void
                        :ignore
                        nop
                        """
                )
            }
        }

        settingsStatusLoadMethod = settingsStatusLoadFingerprint.method

        updatePatchStatus(SETTINGS_FOR_REDDIT)
    }
}

internal fun updateSettingsLabel(label: String) =
    acknowledgementsLabelBuilderMethod.apply {
        fun indexOfPreferencesPresenterInstruction(methodDef: Method) =
            methodDef.indexOfFirstInstruction {
                opcode == Opcode.NEW_INSTANCE &&
                        getReference<TypeReference>()?.type?.contains("checkIfShouldShowImpressumOption") == true
            }

        val predicate: Instruction.() -> Boolean = {
            opcode == Opcode.INVOKE_VIRTUAL &&
                    getReference<MethodReference>()?.name == "getString"
        }
        var insertIndex: Int

        val preferencesPresenterIndex =
            indexOfPreferencesPresenterInstruction(this)

        val stringIndex =
            indexOfFirstInstructionReversedOrThrow(preferencesPresenterIndex, predicate)
        val iconIndex =
            indexOfFirstInstructionReversedOrThrow(stringIndex - 2, Opcode.CONST)
        val iconRegister =
            getInstruction<OneRegisterInstruction>(iconIndex).registerA

        addInstructions(
            iconIndex + 1, """
                        invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->getIcon()I
                        move-result v$iconRegister
                        """
        )

        insertIndex =
            indexOfFirstInstructionReversedOrThrow(preferencesPresenterIndex, predicate) + 2

        val insertRegister =
            getInstruction<OneRegisterInstruction>(insertIndex - 1).registerA

        addInstruction(
            insertIndex,
            "const-string v$insertRegister, \"$label\""
        )
    }

internal fun updatePatchStatus(description: String) =
    settingsStatusLoadMethod.addInstruction(
        0,
        "invoke-static {}, $EXTENSION_PATH/settings/SettingsStatus;->$description()V"
    )

internal fun updatePatchStatus(patch: PatchList) {
    patch.included = true
}

internal fun updatePatchStatus(
    description: String,
    patch: PatchList
) {
    updatePatchStatus(description)
    updatePatchStatus(patch)
}
