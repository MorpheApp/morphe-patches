/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

@file:Suppress("SpellCheckingInspection")

package app.morphe.patches.youtube.layout.miniplayer

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.misc.settings.preference.BasePreference
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_20_31_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_37_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_17_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_29_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_30_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_32_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.cloneParameters
import app.morphe.util.findFreeRegister
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstLiteralInstructionOrThrow
import app.morphe.util.insertLiteralOverride
import app.morphe.util.numberOfParameterRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

internal const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/MiniplayerPatch;"
internal const val MINIMAL_EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/MinimalMiniplayerPatch;"
internal const val MINIMAL_BOUNDS_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/MinimalMiniplayerPatch$MiniplayerBoundsController;"

@Suppress("unused")
val miniplayerPatch = bytecodePatch(
    name = "Miniplayer",
    description = "Adds options to change the in-app minimized player. " +
            "Patching 21.28.206 and lower has more miniplayer types to choose from."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        versionCheckPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        val preferences = mutableSetOf<BasePreference>()

        if (is_20_37_or_greater) {
            // 21.29 removed all modern miniplayers except modern 4
            preferences += if (!is_21_29_or_greater) {
                ListPreference("morphe_miniplayer_type")
            } else {
                // Only modern 4 is left, plus the minimal bar rebuilt on top of it.
                ListPreference(
                    key = "morphe_miniplayer_type",
                    entriesKey = "morphe_miniplayer_type_21_29_entries",
                    entryValuesKey = "morphe_miniplayer_type_21_29_entry_values"
                )
            }
        } else {
            preferences += ListPreference(
                key = "morphe_miniplayer_type",
                entriesKey = "morphe_miniplayer_type_legacy_20_03_entries",
                entryValuesKey = "morphe_miniplayer_type_legacy_20_03_entry_values"
            )
        }

        preferences += SwitchPreference("morphe_miniplayer_disable_resuming", summary = true)
        preferences += SwitchPreference("morphe_miniplayer_disable_drag_and_drop", summary = true)
        preferences += SwitchPreference("morphe_miniplayer_disable_horizontal_drag", summary = true)
        preferences += SwitchPreference("morphe_miniplayer_disable_rounded_corners")
        if (!is_21_29_or_greater) {
            preferences += SwitchPreference("morphe_miniplayer_hide_overlay_buttons")
        }
        if (!is_21_32_or_greater) {
            preferences += TextPreference("morphe_miniplayer_width_dip", inputType = InputType.NUMBER)
        }
        if (!is_21_29_or_greater) {
            preferences += NonInteractivePreference(
                key = "morphe_miniplayer_opacity",
                tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference"
            )
        }
        preferences += SwitchPreference("morphe_miniplayer_disable_horizontal_drag_playback", summary = true)
        preferences += SwitchPreference("morphe_miniplayer_disable_horizontal_reposition", summary = true)

        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_miniplayer_screen",
                sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                preferences = preferences
            )
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

        if (!is_21_29_or_greater) {
            MiniplayerModernConstructorFingerprint.classDef.methods.forEach {
                it.apply {
                    if (AccessFlags.CONSTRUCTOR.isSet(accessFlags)) {
                        val iPutIndex = indexOfFirstInstructionOrThrow {
                            opcode == Opcode.IPUT && getReference<FieldReference>()?.type == "I"
                        }

                        val register = getInstruction<TwoRegisterInstruction>(iPutIndex).registerA
                        addInstructionsAtControlFlowLabel(
                            iPutIndex,
                            """
                                invoke-static { v$register }, $EXTENSION_CLASS->getModernMiniplayerOverrideType(I)I
                                move-result v$register
                            """
                        )
                    } else {
                        findReturnIndicesReversed().forEach { index ->
                            insertMiniplayerBooleanOverride(index, "getModernMiniplayerOverride")
                        }
                    }
                }
            }

            MiniplayerModernConstructorFingerprint.matchAll().forEach {
                it.method.insertLiteralOverride(
                    MINIPLAYER_MODERN_FEATURE_LEGACY_KEY,
                    "$EXTENSION_CLASS->getModernMiniplayerOverride(Z)Z"
                )
            }

            MiniplayerModernConstructorFingerprint.insertMiniplayerFeatureFlagBooleanOverride(
                MINIPLAYER_DOUBLE_TAP_FEATURE_KEY,
                "getMiniplayerDoubleTapAction",
            )
        }

        if (!is_21_30_or_greater) {
            MiniplayerModernConstructorFingerprint.insertMiniplayerFeatureFlagBooleanOverride(
                MINIPLAYER_DRAG_DROP_FEATURE_KEY,
                "getMiniplayerDragAndDrop",
            )
        } else {
            MiniplayerDragAndDropFingerprint.let {
                it.method.cloneParameters().apply {
                    val instructionIndex = it.instructionMatches.last().index + numberOfParameterRegisters
                    val instructionRegister = getInstruction<OneRegisterInstruction>(
                        instructionIndex
                    ).registerA

                    addInstructionsAtControlFlowLabel(
                        instructionIndex + 1,
                        """
                            invoke-static { v$instructionRegister }, $EXTENSION_CLASS->getMiniplayerDragAndDrop(I)Z
                            move-result p0
                            if-eqz p0, :get_drag_and_drop
                            const/4 p0, 0
                            return p0
                            :get_drag_and_drop
                            nop
                        """
                    )
                }
            }
        }

        MiniplayerModernFeatureFingerprint.insertMiniplayerFeatureFlagBooleanOverride(
            MINIPLAYER_MODERN_FEATURE_KEY,
            "getModernFeatureFlagsActiveOverride",
        )

        if (!is_21_32_or_greater) {
            MiniplayerModernConstructorFingerprint.method.apply {
                val literalIndex = indexOfFirstLiteralInstructionOrThrow(
                    MINIPLAYER_INITIAL_SIZE_FEATURE_KEY
                )
                val targetIndex = indexOfFirstInstructionOrThrow(
                    literalIndex, Opcode.LONG_TO_INT
                )
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
        }

        if (!is_21_30_or_greater) {
            MiniplayerModernConstructorFingerprint.insertMiniplayerFeatureFlagBooleanOverride(
                MINIPLAYER_ROUNDED_CORNERS_FEATURE_KEY,
                "getRoundedCorners",
            )
        } else {
            MiniplayerRoundedCornersFingerprint.let {
                it.method.apply {
                    val index = it.instructionMatches.last().index
                    val free = findFreeRegister(index)

                    addInstructionsAtControlFlowLabel(
                        index,
                        """
                            invoke-static {}, $EXTENSION_CLASS->getRoundedCorners()Z
                            move-result v$free
                            if-nez v$free, :get_rounded_corners
                        """,
                        ExternalLabel("get_rounded_corners", getInstruction(index + 1))
                    )
                }

            }
        }

        //$EXTENSION_CLASS

        MiniplayerOnCloseHandlerFingerprint.matchAll().forEach {
            // 21.30+ inlines the flag lookup and must patch ~2 places.
            it.method.insertLiteralOverride(
                MINIPLAYER_DISABLED_FEATURE_KEY,
                "$EXTENSION_CLASS->getMiniplayerOnCloseHandler(Z)Z"
            )
        }

        // region Horizontal drag
        if (!is_21_30_or_greater) {
            MiniplayerModernConstructorFingerprint.insertMiniplayerFeatureFlagBooleanOverride(
                MINIPLAYER_HORIZONTAL_DRAG_FEATURE_KEY,
                "getHorizontalDrag",
            )
        } else {
            MiniplayerOffscreenRectValidatorFingerprint.method.addInstructions(
                0,
                """
                    invoke-static { }, $EXTENSION_CLASS->getHorizontalDrag()Z
                    move-result v0
                    if-eqz v0, :disable_offscreen_miniplayer
                    const/4 v0, 0x0
                    return v0
                    :disable_offscreen_miniplayer
                    nop
                """
            )
        }

        MiniplayerOffscreenHandlerFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.last().index
                val free = findFreeRegister(index)

                addInstructionsWithLabels(
                    index + 1,
                    """
                        invoke-static { }, $EXTENSION_CLASS->getHorizontalDrag()Z
                        move-result v$free
                        if-eqz v$free, :disable_offscreen_handler
                        return-void
                        :disable_offscreen_handler
                        nop
                    """
                )
            }
        }

        // endregion

        if (!is_21_30_or_greater) {
            MiniplayerModernConstructorFingerprint.insertMiniplayerFeatureFlagBooleanOverride(
                MINIPLAYER_ANIMATED_EXPAND_FEATURE_KEY,
                "getMaximizeAnimation",
            )
        } else {
            MiniplayerAnimatedExpandFingerprint.let {
                it.method.apply {
                    val insertIndex = it.instructionMatches[1].index
                    val labelIndex = it.instructionMatches.last().index
                    val free = findFreeRegister(insertIndex)

                    addInstructionsAtControlFlowLabel(
                        insertIndex,
                        """
                            invoke-static { }, $EXTENSION_CLASS->getMaximizeAnimation()Z
                            move-result v$free
                            if-eqz v$free, :get_maximize_animation
                        """,
                        ExternalLabel("get_maximize_animation", getInstruction(labelIndex))
                    )
                }
            }
        }

        Fingerprint(
            definingClass = MiniplayerHorizontalDragPlaybackFingerprint.instructionMatches[2]
                .getMethodCalled().definingClass,
            name = "onAnimationEnd",
        ).method.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $EXTENSION_CLASS->pausePlaybackWithHorizontalDrag()Z
                move-result v0
                if-eqz v0, :pause_playback_with_horizontal_drag
                return-void
                :pause_playback_with_horizontal_drag
                nop
            """
        )

        MiniplayerHorizontalRepositionFingerprint.method.apply {
            val previousRectParamFieldAccess = MiniplayerRectDragFieldsNameFingerprint.instructionMatches[1]
                .getInstruction<ReferenceInstruction>().reference

            addInstructions(
                0,
                """
                    iget-object v0, p0, $previousRectParamFieldAccess
                    invoke-static { p1, v0 }, $EXTENSION_CLASS->blockOffscreenMiniplayerHorizontalReposition(Landroid/graphics/Rect;Landroid/graphics/Rect;)Landroid/graphics/Rect;
                    move-result-object p1
                """
            )
        }

        NextGenWatchLayoutOnInterceptTouchEventFingerprint.method.addInstruction(
            0,
            "invoke-static { p1 }, $EXTENSION_CLASS->" +
                    "enableOffScreenMiniplayerButtonPressed(Landroid/view/MotionEvent;)V"
        )

        // endregion

        // region fix minimal miniplayer using the wrong pause/play bold icons.

        if (is_20_31_or_greater && !is_21_29_or_greater) {
            if (is_21_17_or_greater) {
                // 21.17+ removed the code to set the non-bold miniplayer pause/play icon,
                // and removed the non bold yt_fill_pause_white_36 icons.
                MiniplayerSetIconsFingerprint.let {
                    it.method.apply {
                        val setImageDrawableIndex = it.instructionMatches.first().index

                        addInstruction(
                            setImageDrawableIndex + 1,
                            "invoke-static { p0, p2 }, $EXTENSION_CLASS->" +
                                    "overrideMiniplayerActionButtonDrawable(Landroid/widget/ImageView;I)V",
                        )
                    }
                }
            } else {
                // Fix bold icons always shown for 20.31 to 21.16
                MiniplayerSetIconsLegacyFingerprint.method.apply {
                    findInstructionIndicesReversedOrThrow(
                        methodCall(
                            opcode = Opcode.INVOKE_INTERFACE,
                            returnType = "Z",
                            parameters = listOf()
                        )
                    ).forEach { index ->
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
        }

        // endregion

        // region Add hooks to hide modern miniplayer buttons.

        val fingerprints = mutableListOf(
            MiniplayerModernExpandButtonFingerprint to "hideMiniplayerExpandClose",
            MiniplayerModernCloseButtonFingerprint to "hideMiniplayerExpandClose",
            MiniplayerModernActionButtonFingerprint to "hideMiniplayerActionButton",
        )
        if (!is_21_29_or_greater) {
            fingerprints += MiniplayerModernOverlayViewFingerprint to "adjustMiniplayerOpacity"
        }
        fingerprints.forEach { (fingerprint, methodName) ->
            fingerprint.method.apply {
                val index = fingerprint.instructionMatches.last().index
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstruction(
                    index + 1,
                    "invoke-static { v$register }, $EXTENSION_CLASS->$methodName(Landroid/view/View;)V",
                )
            }
        }

        // endregion

        // region Minimal miniplayer.

        if (is_21_29_or_greater) {
            MiniplayerLegacyControlsFingerprint.let {
                it.method.apply {
                    val index = it.instructionMatches.last().index
                    val register = getInstruction<OneRegisterInstruction>(index).registerA

                    addInstruction(
                        index + 1,
                        "invoke-static { v$register }, $MINIMAL_EXTENSION_CLASS->" +
                                "setLegacyControls(Landroid/view/ViewGroup;)V"
                    )
                }
            }

            MiniplayerLegacyControlsVisibilityFingerprint.let {
                it.method.apply {
                    val index = it.instructionMatches[1].index
                    val register = getInstruction<OneRegisterInstruction>(index).registerA

                    addInstructions(
                        index + 1,
                        """
                            invoke-static { v$register }, $MINIMAL_EXTENSION_CLASS->getLegacyControlsVisibility(I)I
                            move-result v$register
                        """
                    )
                }
            }

            // Exposed so the bar shape can be applied on demand. Only this setter runs the
            // pass that lays the player out, the plain one records the rect and nothing redraws.
            MiniplayerHorizontalRepositionFingerprint.let { fingerprint ->
                fingerprint.classDef.apply {
                    interfaces.add(MINIMAL_BOUNDS_INTERFACE)

                    val setBounds = ImmutableMethod(
                        type,
                        "patch_setBounds",
                        listOf(ImmutableMethodParameter("Landroid/graphics/Rect;", null, "bounds")),
                        "V",
                        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                        null,
                        null,
                        MutableMethodImplementation(2)
                    ).toMutable()

                    setBounds.addInstructions(
                        0,
                        """
                            invoke-virtual { p0, p1 }, ${fingerprint.method}
                            return-void
                        """
                    )

                    methods.add(setBounds)
                }

                fingerprint.method.addInstruction(
                    0,
                    "invoke-static { p0 }, $MINIMAL_EXTENSION_CLASS->" +
                            "setBoundsController($MINIMAL_BOUNDS_INTERFACE)V"
                )
            }

            // YouTube sets the action button icon together with its content description, which
            // is the exact playback state at the moment it changes.
            MiniplayerSetIconsFingerprint.let {
                it.method.addInstruction(
                    it.instructionMatches.first().index + 1,
                    "invoke-static { p2 }, $MINIMAL_EXTENSION_CLASS->setPlaybackIcon(I)V"
                )
            }

            // Only this method recalculates the rect the video is laid out with. Insert after
            // the early return, so the unchanged rect is what gets compared.
            MiniplayerHorizontalRepositionFingerprint.let {
                it.method.apply {
                    addInstructionsAtControlFlowLabel(
                        it.instructionMatches.first().index,
                        """
                            invoke-static { p1 }, $MINIMAL_EXTENSION_CLASS->getMinimalBarBounds(Landroid/graphics/Rect;)Landroid/graphics/Rect;
                            move-result-object p1
                        """
                    )
                }
            }

            // Written on the rect itself rather than through its getter, because the field is
            // also read directly, including while animating.
            MiniplayerHorizontalRepositionFingerprint.method.apply {
                findInstructionIndicesReversedOrThrow(
                    methodCall(
                        opcode = Opcode.INVOKE_STATIC,
                        parameters = listOf("F", "Landroid/graphics/Rect;", "Landroid/graphics/Rect;"),
                        returnType = "V"
                    )
                ).forEach { index ->
                    val videoRect = getInstruction<FiveRegisterInstruction>(index).registerE

                    addInstruction(
                        index + 1,
                        "invoke-static { v$videoRect }, $MINIMAL_EXTENSION_CLASS->" +
                                "applyVideoRect(Landroid/graphics/Rect;)V"
                    )
                }
            }

            // Must run after the offscreen handler hook above, which patches the same method
            // and uses instruction indexes that inserting here would shift.
            MiniplayerOffscreenHandlerFingerprint.method.addInstructions(
                0,
                """
                    invoke-static { p1, p2, p3, p4 }, $MINIMAL_EXTENSION_CLASS->getMiniplayerBounds(IIII)Landroid/graphics/Rect;
                    move-result-object v0
                    iget p1, v0, Landroid/graphics/Rect;->left:I
                    iget p2, v0, Landroid/graphics/Rect;->top:I
                    iget p3, v0, Landroid/graphics/Rect;->right:I
                    iget p4, v0, Landroid/graphics/Rect;->bottom:I
                """
            )
        }

        // endregion
    }
}
