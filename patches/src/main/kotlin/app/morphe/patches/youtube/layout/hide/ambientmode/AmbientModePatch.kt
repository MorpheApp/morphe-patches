/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.hide.ambientmode

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.matchAllMethodIndicesForEach
import app.morphe.patcher.util.matchSingle
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.misc.fix.proto.fixProtoLibraryPatch
import app.morphe.patches.shared.misc.fix.proto.parseByteArrayMethodRef
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_21_02_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_03_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_43_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.findFieldFromToString
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.util.MethodUtil

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/AmbientModePatch;"

/**
 * `disableAmbientMode(true)` returns true if ambient mode is not disabled in the Morphe settings.
 */
private const val EXTENSION_IS_AMBIENT_MODE_ENABLED =
    "$EXTENSION_CLASS->disableAmbientMode(Z)Z"

/**
 * CinematicContainerRenderer field 3 (presentation style) DYNAMIC_BLURRED:
 * the background takes the colors of the video.
 */
private const val PRESENTATION_STYLE_DYNAMIC_BLURRED = 5

/**
 * CinematicContainerRenderer used when the /next response has none.
 * Serialized from a server response, with field 3 (presentation style) = 5 (DYNAMIC_BLURRED)
 * and field 4.11 (ambient mode on by default) = true.
 */
private const val DEFAULT_CINEMATIC_CONTAINER_RENDERER =
    "EgsQ94CqmwMdAAAAABIGEPeAqpsDEgsQgICAmAMdAACAPxgFIpEBCICAgPgPEICAgPgPGg0IkE4QiCcdAACAPyAUJQAAIEAoATUA" +
            "AIA/PQAAgkNFmpmZP00AAMA/VQAAgD9YAWpSDQAAIEAVAAAgQB0AACBAJQAAIEAyPBIREPeAqpsDHQAAAAAI94CqmwMSERCA" +
            "gICYAx0AAIA/CICAgJgDCAIdAAAAPyUAAAA/LQAAgD81AACAPw=="

@Suppress("unused")
val ambientModePatch = bytecodePatch(
    name = "Ambient mode",
    description = "Forces Ambient mode on and adds options to bypass power saving restrictions for Ambient mode and disable it entirely or in fullscreen.",
) {
    dependsOn(
        settingsPatch,
        sharedExtensionPatch,
        versionCheckPatch,
        fixProtoLibraryPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_ambient_mode_screen",
                sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("morphe_bypass_ambient_mode_restrictions", summary = true),
                    SwitchPreference("morphe_disable_ambient_mode"),
                    SwitchPreference("morphe_disable_fullscreen_ambient_mode"),
                )
            )
        )

        //
        // Bypass ambient mode restrictions.
        //
        fun MutableMethod.hook() {
            findInstructionIndicesReversedOrThrow(
                methodCall(IS_POWER_SAVE_MODE_METHOD_CALL)
            ).forEach { index ->
                val register = getInstruction<FiveRegisterInstruction>(index).registerC

                replaceInstruction(
                    index,
                    "invoke-static/range { v$register .. v$register }, $EXTENSION_CLASS->" +
                            "bypassAmbientModeRestrictions(Landroid/os/PowerManager;)Z",
                )
            }
        }

        val intentActionFingerprints = mutableListOf<Fingerprint>(
            IntentActionBroadcastReceiverFingerprint
        )
        if (!is_21_43_or_greater) {
            intentActionFingerprints += IntentActionSyntheticFingerprint
        }
        if (is_21_02_or_greater) {
            intentActionFingerprints += IntentActionBroadcastReceiverAlternativeFingerprint
        }
        intentActionFingerprints.forEach { fingerprint ->
            fingerprint.let {
                it.method.apply {
                    val index = it.instructionMatches[2].index
                    val reference =
                        getInstruction<ReferenceInstruction>(index).reference as MethodReference

                    // This fingerprint is used multiple times.
                    PowerSaveModeSyntheticFingerprint.clearMatch()

                    // Match may be null, as it may have already been replaced by another fingerprint.
                    PowerSaveModeSyntheticFingerprint.matchOrNull(
                                        mutableClassDefBy(reference.definingClass)
                                    )?.method?.hook()
                }
            }
        }

        if (is_21_43_or_greater) {
            IntentActionSyntheticAlternativeFingerprint.method.hook()
        } else if (is_21_03_or_greater) {
            IntentActionSyntheticAlternativeLegacyFingerprint.method.hook()
        }

        //
        // Force ambient mode, unless it is disabled in the Morphe settings.
        // The flyout menu toggle of the player still turns it on and off.
        //

        // Feature flags. 21.30+ inlines the flag lookups and must patch 4 places for each flag.
        // Each flag is on if ambient mode is not disabled.
        if (!is_21_43_or_greater) AmbientModeFeatureFlagFingerprint.matchAllMethodIndicesForEach { index ->
            val moveResultIndex = indexOfFirstInstructionOrThrow(index, Opcode.MOVE_RESULT)
            val register = getInstruction<OneRegisterInstruction>(moveResultIndex).registerA

            addInstructions(
                moveResultIndex + 1,
                """
                    const v$register, 0x1
                    invoke-static/range { v$register .. v$register }, $EXTENSION_IS_AMBIENT_MODE_ENABLED
                    move-result v$register
                """
            )
        }

        // The /next response can have no CinematicContainerRenderer, a static color style
        // (the background does not change with the video, issue #3783)
        // or ambient mode off by default. Override what the server decides.
        val rendererClass = CinematicContainerRendererFingerprint.classDef
        val rendererType = rendererClass.type

        val styleMatches = cinematicContainerPresentationStyleFingerprint(rendererType).instructionMatches
        val hasBitsField = styleMatches[0].getFieldAccessed()
        val styleHasBitMask = styleMatches[1].getInstruction<NarrowLiteralInstruction>().narrowLiteral
        val styleField = styleMatches[3].getFieldAccessed()

        val configField = cinematicContainerConfigFingerprint(rendererType).matchSingle()
            .instructionMatches.last().getFieldAccessed()
        val configType = configField.type
        val enabledByDefaultField = cinematicContainerEnabledByDefaultFingerprint(configType).matchSingle()
            .instructionMatches[3].getFieldAccessed()

        val defaultRendererField = rendererClass.fields.first { field ->
            field.type == rendererType && AccessFlags.STATIC.isSet(field.accessFlags)
        }

        val stateType = CinematicContainerRendererWithStateFingerprint.method
            .findFieldFromToString("rendererLoadedState=").type
        val stateMatch = cinematicContainerRendererLoadedStateFingerprint(stateType).matchSingle()
        val waitingStateField = stateMatch.instructionMatches[1].getFieldAccessed()
        val loadedStateField = stateMatch.instructionMatches[3].getFieldAccessed()

        CinematicContainerRendererWithStateFingerprint.classDef.apply {
            val constructor = methods.first { method ->
                MethodUtil.isConstructor(method) && method.parameterTypes.size == 2 &&
                        method.parameterTypes.first().toString() == stateType
            }
            val optionalType = constructor.parameterTypes[1].toString()
            val parameters = listOf(
                ImmutableMethodParameter(stateType, null, null),
                ImmutableMethodParameter(optionalType, null, null),
            )

            val forceRendererMethod = ImmutableMethod(
                type,
                "patch_forceAmbientModeRenderer",
                parameters,
                optionalType,
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                null,
                null,
                MutableMethodImplementation(5),
            ).toMutable().apply {
                addInstructionsWithLabels(
                    0,
                    """
                        const/4 v0, 0x1
                        invoke-static { v0 }, $EXTENSION_IS_AMBIENT_MODE_ENABLED
                        move-result v0
                        if-nez v0, :enabled

                        # Ambient mode is disabled, so the renderer of the server is not used either.
                        # 21.43+ has no feature flags that turn ambient mode off.
                        invoke-static { }, $optionalType->empty()$optionalType
                        move-result-object p1
                        return-object p1

                        :enabled
                        # No /next response yet.
                        sget-object v0, $waitingStateField
                        if-eq p0, v0, :keep

                        invoke-virtual { p1 }, $optionalType->isPresent()Z
                        move-result v0
                        if-eqz v0, :create
                        invoke-virtual { p1 }, $optionalType->get()Ljava/lang/Object;
                        move-result-object v0
                        check-cast v0, $rendererType
                        goto :force

                        :create
                        sget-object v0, $defaultRendererField
                        const-string v1, "$DEFAULT_CINEMATIC_CONTAINER_RENDERER"
                        const/4 v2, 0x0
                        invoke-static { v1, v2 }, Landroid/util/Base64;->decode(Ljava/lang/String;I)[B
                        move-result-object v1
                        invoke-static { v0, v1 }, ${parseByteArrayMethodRef.get()!!}
                        move-result-object v0
                        check-cast v0, $rendererType

                        :force
                        iget v1, v0, $hasBitsField
                        or-int/lit8 v1, v1, $styleHasBitMask
                        iput v1, v0, $hasBitsField
                        const/4 v1, $PRESENTATION_STYLE_DYNAMIC_BLURRED
                        iput v1, v0, $styleField

                        # Only the default: the flyout menu toggle is saved separately and still applies.
                        iget-object v1, v0, $configField
                        if-eqz v1, :wrap
                        const/4 v2, 0x1
                        iput-boolean v2, v1, $enabledByDefaultField

                        :wrap
                        invoke-static { v0 }, $optionalType->of(Ljava/lang/Object;)$optionalType
                        move-result-object p1

                        :keep
                        return-object p1
                    """
                )
            }

            val rendererStateMethod = ImmutableMethod(
                type,
                "patch_getAmbientModeRendererState",
                parameters,
                stateType,
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                null,
                null,
                MutableMethodImplementation(3),
            ).toMutable().apply {
                addInstructionsWithLabels(
                    0,
                    """
                        invoke-virtual { p1 }, $optionalType->isPresent()Z
                        move-result v0
                        if-eqz v0, :keep
                        sget-object p0, $loadedStateField
                        :keep
                        return-object p0
                    """
                )
            }

            methods.add(forceRendererMethod)
            methods.add(rendererStateMethod)

            // Index 0: p1 and p2 are not written yet, so no free register is needed.
            constructor.addInstructions(
                0,
                """
                    invoke-static { p1, p2 }, $forceRendererMethod
                    move-result-object p2
                    invoke-static { p1, p2 }, $rendererStateMethod
                    move-result-object p1
                """
            )
        }

        //
        // Disable fullscreen ambient mode.
        //
        SetFullScreenBackgroundColorFingerprint.let {
            it.method.apply {
                val insertIndex = it.instructionMatches.first().index
                val register = getInstruction<FiveRegisterInstruction>(insertIndex).registerD

                addInstructions(
                    insertIndex,
                    """
                        invoke-static { v$register }, $EXTENSION_CLASS->getFullScreenBackgroundColor(I)I
                        move-result v$register
                    """
                )
            }
        }
    }
}
