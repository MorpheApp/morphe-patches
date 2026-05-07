@file:Suppress("SpellCheckingInspection")

package app.morphe.patches.youtube.layout.miniplayer

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.getResourceId
import app.morphe.patches.shared.misc.settings.preference.BasePreference
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_20_31_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_37_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_17_or_greater
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstLiteralInstructionOrThrow
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/MiniplayerPatch;"

@Suppress("unused")
val miniplayerPatch = bytecodePatch(
    name = "Miniplayer",
    description = "Adds options to change the in-app minimized player."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        getResourceId(
            ResourceType.ID,
            "modern_miniplayer_subtitle_text",
        )

        val preferences = mutableSetOf<BasePreference>()

        preferences +=
            if (is_20_37_or_greater) {
                ListPreference("morphe_miniplayer_type")
            } else {
                ListPreference(
                    key = "morphe_miniplayer_type",
                    entriesKey = "morphe_miniplayer_type_legacy_20_03_entries",
                    entryValuesKey = "morphe_miniplayer_type_legacy_20_03_entry_values"
                )
            }

        preferences += SwitchPreference("morphe_miniplayer_disable_resuming")
        preferences += SwitchPreference("morphe_miniplayer_disable_drag_and_drop")
        preferences += SwitchPreference("morphe_miniplayer_disable_horizontal_drag")
        preferences += SwitchPreference("morphe_miniplayer_disable_rounded_corners")
        preferences += SwitchPreference("morphe_miniplayer_hide_subtext")
        preferences += SwitchPreference("morphe_miniplayer_hide_overlay_buttons")
        preferences += SwitchPreference("morphe_miniplayer_hide_rewind_forward")
        preferences += TextPreference("morphe_miniplayer_width_dip", inputType = InputType.NUMBER)
        preferences += TextPreference("morphe_miniplayer_opacity", inputType = InputType.NUMBER)

        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_miniplayer_screen",
                sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                preferences = preferences,
            ),
        )

        fun MutableMethod.insertMiniplayerBooleanOverride(index: Int, methodName: String) {
            val register = getInstruction<OneRegisterInstruction>(index).registerA
            addInstructions(
                index,
                """
                    invoke-static {v$register}, $EXTENSION_CLASS->$methodName(Z)Z
                    move-result v$register
                """
            )
        }

        fun Method.findReturnIndicesReversed() = findInstructionIndicesReversedOrThrow(Opcode.RETURN)

        /**
         * Adds an override to force legacy tablet miniplayer to be used or not used.
         */
        fun MutableMethod.insertLegacyTabletMiniplayerOverride(index: Int) {
            insertMiniplayerBooleanOverride(index, "getLegacyTabletMiniplayerOverride")
        }

        /**
         * Adds an override to force modern miniplayer to be used or not used.
         */
        fun MutableMethod.insertModernMiniplayerOverride(index: Int) {
            insertMiniplayerBooleanOverride(index, "getModernMiniplayerOverride")
        }

        fun Fingerprint.insertMiniplayerFeatureFlagBooleanOverride(
            literal: Long,
            extensionMethod: String,
        ) = method.insertLiteralOverride(
            literal,
            "$EXTENSION_CLASS->$extensionMethod(Z)Z"
        )

        fun Fingerprint.insertMiniplayerFeatureFlagFloatOverride(
            literal: Long,
            extensionMethod: String,
        ) {
            method.apply {
                val literalIndex = indexOfFirstLiteralInstructionOrThrow(literal)
                val targetIndex = indexOfFirstInstructionOrThrow(literalIndex, Opcode.DOUBLE_TO_FLOAT)
                val register = getInstruction<OneRegisterInstruction>(targetIndex).registerA

                addInstructions(
                    targetIndex + 1,
                    """
                        invoke-static { v$register }, $EXTENSION_CLASS->$extensionMethod(F)F
                        move-result v$register
                    """
                )
            }
        }

        /**
         * Adds an override to specify which modern miniplayer is used.
         */
        fun MutableMethod.insertModernMiniplayerTypeOverride(iPutIndex: Int) {
            val register = getInstruction<TwoRegisterInstruction>(iPutIndex).registerA

            addInstructionsAtControlFlowLabel(
                iPutIndex,
                """
                    invoke-static { v$register }, $EXTENSION_CLASS->getModernMiniplayerOverrideType(I)I
                    move-result v$register
                """
            )
        }

        // region Disable resuming miniplayer (Continue watching)

        ShowMiniplayerCommandFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches[1].index
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstructions(
                    index,
                    """
                        invoke-static { v$register }, $EXTENSION_CLASS->disableResumingStartupMiniPlayer(Z)Z
                        move-result v$register
                    """
                )
            }
        }

        // endregion

        // region Enable tablet miniplayer.
        // Parts of the YT code is removed in 20.37+ and the legacy player no longer works.

        if (!is_20_37_or_greater) {
            MiniplayerOverrideNoContextFingerprint.method.apply {
                findReturnIndicesReversed().forEach { index ->
                    insertLegacyTabletMiniplayerOverride(
                        index
                    )
                }
            }

            // endregion

            // region Legacy tablet miniplayer hooks.
            MiniplayerOverrideFingerprint.instructionMatches.last().getMethodCalled().apply {
                findReturnIndicesReversed().forEach { index ->
                    insertLegacyTabletMiniplayerOverride(index)
                }
            }

            MiniplayerResponseModelSizeCheckFingerprint.let {
                it.method.insertLegacyTabletMiniplayerOverride(it.instructionMatches.last().index)
            }
        }

        // endregion

        // region Enable modern miniplayer.

        MiniplayerModernConstructorFingerprint.classDef.methods.forEach {
            it.apply {
                if (AccessFlags.CONSTRUCTOR.isSet(accessFlags)) {
                    val iPutIndex = indexOfFirstInstructionOrThrow {
                        this.opcode == Opcode.IPUT && this.getReference<FieldReference>()?.type == "I"
                    }

                    insertModernMiniplayerTypeOverride(iPutIndex)
                } else {
                    findReturnIndicesReversed().forEach { index -> insertModernMiniplayerOverride(index) }
                }
            }
        }

        MiniplayerModernConstructorFingerprint.insertMiniplayerFeatureFlagBooleanOverride(
            MINIPLAYER_DRAG_DROP_FEATURE_KEY,
            "getMiniplayerDragAndDrop",
        )


        MiniplayerModernConstructorFingerprint.insertMiniplayerFeatureFlagBooleanOverride(
            MINIPLAYER_MODERN_FEATURE_LEGACY_KEY,
            "getModernMiniplayerOverride",
        )

        MiniplayerModernFeatureFingerprint.insertMiniplayerFeatureFlagBooleanOverride(
            MINIPLAYER_MODERN_FEATURE_KEY,
            "getModernFeatureFlagsActiveOverride",
        )

        MiniplayerModernConstructorFingerprint.insertMiniplayerFeatureFlagBooleanOverride(
            MINIPLAYER_DOUBLE_TAP_FEATURE_KEY,
            "getMiniplayerDoubleTapAction",
        )

        MiniplayerModernConstructorFingerprint.method.apply {
            val literalIndex = indexOfFirstLiteralInstructionOrThrow(
                MINIPLAYER_INITIAL_SIZE_FEATURE_KEY,
            )
            val targetIndex = indexOfFirstInstructionOrThrow(literalIndex, Opcode.LONG_TO_INT)
            val register = getInstruction<OneRegisterInstruction>(targetIndex).registerA

            addInstructions(
                targetIndex + 1,
                """
                    invoke-static { v$register }, $EXTENSION_CLASS->getMiniplayerDefaultSize(I)I
                    move-result v$register
                """
            )
        }

        // Override a minimum size constant.
        MiniplayerMinimumSizeFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches[1].index
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                // Smaller sizes can be used, but the miniplayer will always start in size 170 if set any smaller.
                // The 170 initial limit probably could be patched to allow even smaller initial sizes,
                // but 170 is already half the horizontal space and smaller does not seem useful.
                replaceInstruction(index, "const/16 v$register, 170")
            }
        }

        MiniplayerModernConstructorFingerprint.insertMiniplayerFeatureFlagBooleanOverride(
            MINIPLAYER_ROUNDED_CORNERS_FEATURE_KEY,
            "getRoundedCorners",
        )

        MiniplayerOnCloseHandlerFingerprint.insertMiniplayerFeatureFlagBooleanOverride(
            MINIPLAYER_DISABLED_FEATURE_KEY,
            "getMiniplayerOnCloseHandler"
        )

        MiniplayerModernConstructorFingerprint.insertMiniplayerFeatureFlagBooleanOverride(
            MINIPLAYER_HORIZONTAL_DRAG_FEATURE_KEY,
            "getHorizontalDrag",
        )

        MiniplayerModernConstructorFingerprint.insertMiniplayerFeatureFlagBooleanOverride(
            MINIPLAYER_ANIMATED_EXPAND_FEATURE_KEY,
            "getMaximizeAnimation",
        )

        // endregion

        // region fix minimal miniplayer using the wrong pause/play bold icons.

        if (is_20_31_or_greater && !is_21_17_or_greater) {
            // 21.17+ removed the code to set the non-bold miniplayer pause/play icon,
            // and removed the non bold yt_fill_pause_white_36 icons.
            MiniplayerSetIconsFingerprint.method.apply {
                findInstructionIndicesReversedOrThrow {
                    val reference = getReference<MethodReference>()
                    opcode == Opcode.INVOKE_INTERFACE
                        && reference?.returnType == "Z" && reference.parameterTypes.isEmpty()
                }.forEach { index ->
                    val register = getInstruction<OneRegisterInstruction>(index + 1).registerA

                    addInstructions(
                        index + 2,
                        """
                            invoke-static { v$register }, $EXTENSION_CLASS->allowBoldIcons(Z)Z
                            move-result v$register
                        """
                    )
                }
            }
        }

        // endregion

        // region Add hooks to hide modern miniplayer buttons.

        listOf(
            MiniplayerModernExpandButtonFingerprint to "hideMiniplayerExpandClose",
            MiniplayerModernCloseButtonFingerprint to "hideMiniplayerExpandClose",
            MiniplayerModernActionButtonFingerprint to "hideMiniplayerActionButton",
            MiniplayerModernRewindButtonFingerprint to "hideMiniplayerRewindForward",
            MiniplayerModernForwardButtonFingerprint to "hideMiniplayerRewindForward",
            MiniplayerModernOverlayViewFingerprint to "adjustMiniplayerOpacity"
        ).forEach { (fingerprint, methodName) ->
            fingerprint.method.apply {
                val index = fingerprint.instructionMatches.last().index
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstruction(
                    index + 1,
                    "invoke-static { v$register }, $EXTENSION_CLASS->$methodName(Landroid/view/View;)V",
                )
            }
        }

        MiniplayerModernAddViewListenerFingerprint.method.addInstruction(
            0,
            "invoke-static { p1 }, $EXTENSION_CLASS->" +
                "hideMiniplayerSubTexts(Landroid/view/View;)V",
        )

        // endregion
    }
}
