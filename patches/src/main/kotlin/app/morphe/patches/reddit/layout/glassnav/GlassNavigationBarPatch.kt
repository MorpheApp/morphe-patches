/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5272
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.glassnav

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.InstructionLocation.MatchAfterWithin
import app.morphe.patcher.checkCast
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addFreeRegisters
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.registersUsed
import app.morphe.patches.reddit.layout.autoscroll.ScreenAttachFingerprint
import app.morphe.patches.reddit.layout.autoscroll.ScreenDetachFingerprint
import app.morphe.patches.reddit.misc.compose.MODIFIER_INTERFACE
import app.morphe.patches.reddit.misc.compose.NewRoundedCornerShapeFingerprint
import app.morphe.patches.reddit.misc.compose.composePatch
import app.morphe.patches.reddit.misc.compose.modifierType
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_24_0_or_greater
import app.morphe.patches.reddit.misc.version.is_2026_45_0_or_greater
import app.morphe.patches.reddit.misc.version.is_2026_47_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.patcher.util.returnEarly
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/GlassNavigationBarPatch;"
private const val NEUTRAL_COLORS_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/GlassNavigationBarPatch$NeutralColors;"

@Suppress("unused")
val glassNavigationBarPatch = patch(
    name = "Glass navigation bar",
    description = "Adds an option to show the bottom navigation bar, or the side rail on tablets, " +
        "as a floating translucent capsule. This patch works with Reddit 2026.24.0 and newer."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch, composePatch)

    execute {
        if (!is_2026_24_0_or_greater) {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "'Glass navigation bar' requires Reddit 2026.24.0+"
            )
        }

        val backdropFingerprint = if (is_2026_47_0_or_greater) {
            BottomNavBackdropFingerprint
        } else {
            BottomNavBackdropLegacyFingerprint
        }

        // region Access to theme classes.

        val backgroundMethod = backdropFingerprint.instructionMatches[2].methodReference
        val railBackgroundMethod = RailBackgroundFingerprint.instructionMatches[3].methodReference

        mutableClassDefBy(backgroundMethod.definingClass).apply {
            addPatchInterface(NEUTRAL_COLORS_INTERFACE, "patch_getBackground", backgroundMethod)
            addPatchInterface(NEUTRAL_COLORS_INTERFACE, "patch_getRailBackground", railBackgroundMethod)
        }

        // endregion

        // region Side rail.

        SideNavRailContentFingerprint.let {
            it.method.apply {
                val modifierIndex = it.instructionMatches[3].index
                val modifierRegister = it.instructionMatches[3].instruction.registersUsed[1]

                addInstructions(
                    modifierIndex,
                    """
                        invoke-static/range { v$modifierRegister .. v$modifierRegister }, $EXTENSION_CLASS->getRailModifier($MODIFIER_INTERFACE)Ljava/lang/Object;
                        move-result-object v$modifierRegister
                        check-cast v$modifierRegister, $modifierType
                    """
                )

                val heightIndex = it.instructionMatches[1].index
                val heightRegister = it.instructionMatches[1].instruction.registersUsed[0]

                addInstruction(
                    heightIndex,
                    "invoke-static/range { v$heightRegister .. v$heightRegister }, " +
                        "$EXTENSION_CLASS->setRailHeight(F)V"
                )
            }
        }

        RailBackgroundFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches[3].index
                val register = it.instructionMatches[3].instruction.registersUsed[0]

                replaceInstruction(
                    index,
                    "invoke-static/range { v$register .. v$register }, " +
                        "$EXTENSION_CLASS->getRailBackground($NEUTRAL_COLORS_INTERFACE)J"
                )
            }
        }

        // endregion

        // region Bottom navigation bar.

        backdropFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches[3].index
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstructions(
                    index + 1,
                    """
                        invoke-static/range { v$register .. v${register + 1} }, $EXTENSION_CLASS->getBarColor(J)J
                        move-result-wide v$register
                    """
                )
            }
        }

        BottomNavBarFingerprint.let {
            it.method.apply {
                val surfaceIndex = it.instructionMatches[2].index
                val surfaceRegisters = it.instructionMatches[2].instruction.registersUsed
                val modifierRegister = surfaceRegisters[0]
                val elevationRegister = surfaceRegisters[2]
                val colorRegister = surfaceRegisters[3]

                addInstructions(
                    surfaceIndex,
                    """
                        invoke-static/range { v$modifierRegister .. v$modifierRegister }, $EXTENSION_CLASS->getBarModifier($MODIFIER_INTERFACE)Ljava/lang/Object;
                        move-result-object v$modifierRegister
                        check-cast v$modifierRegister, $modifierType
                        invoke-static/range { v$elevationRegister .. v$elevationRegister }, $EXTENSION_CLASS->getBarElevation(F)F
                        move-result v$elevationRegister
                        invoke-static/range { v$colorRegister .. v${colorRegister + 1} }, $EXTENSION_CLASS->getBarColor(J)J
                        move-result-wide v$colorRegister
                    """
                )

                val drawIndex = it.instructionMatches[0].index
                val drawRegister = it.instructionMatches[0].instruction.registersUsed[1]

                addInstructions(
                    drawIndex,
                    """
                        invoke-static/range { v$drawRegister .. v$drawRegister }, $EXTENSION_CLASS->getBarDraw(Lkotlin/jvm/functions/Function1;)Lkotlin/jvm/functions/Function1;
                        move-result-object v$drawRegister
                    """
                )
            }
        }

        BottomNavStackHostFingerprint.matchAll().forEach {
            val navStackHostMatch = it.instructionMatches[1]
            val navStackHostMethod = navStackHostMatch.methodReference
            val companionField = ModifierCompanionFingerprint.instructionMatches[0].fieldReference

            // 2026.40.0 and lower can omit the modifier. It is then null and a flag is set that
            // replaces it with the default, so set the default here and clear the flag.
            val helperMethod = ImmutableMethod(
                it.classDef.type,
                "patch_${it.method.name}_navStackHost",
                navStackHostMethod.parameterTypes.map { type ->
                    ImmutableMethodParameter(type.toString(), null, null)
                },
                navStackHostMethod.returnType,
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                null,
                null,
                MutableMethodImplementation(8)
            ).toMutable().apply {
                addInstructionsWithLabels(
                    0,
                    """
                        and-int/lit8 v0, p6, 0x1
                        if-eqz v0, :has_modifier
                        sget-object p1, $companionField
                        and-int/lit8 p6, p6, -0x2
                        :has_modifier
                        invoke-static { p1 }, $EXTENSION_CLASS->getContentModifier($MODIFIER_INTERFACE)Ljava/lang/Object;
                        move-result-object p1
                        check-cast p1, $modifierType
                        invoke-static/range { p0 .. p6 }, $navStackHostMethod
                        return-void
                    """
                )
            }
            it.classDef.methods.add(helperMethod)

            val firstRegister = navStackHostMatch.instruction.registersUsed.first()
            val lastRegister = navStackHostMatch.instruction.registersUsed.last()
            it.method.replaceInstruction(
                navStackHostMatch.index,
                "invoke-static/range { v$firstRegister .. v$lastRegister }, $helperMethod"
            )
        }

        if (is_2026_47_0_or_greater) {
            NavScreenContentStateConstructorFingerprint.method.addInstructions(
                0,
                """
                    invoke-static { p1 }, $EXTENSION_CLASS->setShowBottomNav(Z)V
                    invoke-static { p2 }, $EXTENSION_CLASS->padContentForBottomNav(Z)Z
                    move-result p2
                """
            )
        }

        BottomNavBarPlacementFingerprint.let {
            it.method.apply {
                val heightIndex = it.instructionMatches[3].index
                val heightRegister = getInstruction<TwoRegisterInstruction>(heightIndex).registerA
                val roundIndex = it.instructionMatches[5].index
                val slideRegister = it.instructionMatches[5].instruction.registersUsed[0]

                addInstructions(
                    roundIndex,
                    """
                        invoke-static { v$slideRegister, v$heightRegister }, $EXTENSION_CLASS->getBarSlide(FF)F
                        move-result v$slideRegister
                    """
                )
            }
        }

        StaticBottomNavBarFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches[0].index
                val modifierRegister = it.instructionMatches[0].instruction.registersUsed[1]

                addInstructions(
                    index,
                    """
                        invoke-static/range { v$modifierRegister .. v$modifierRegister }, $EXTENSION_CLASS->getStaticBarModifier($MODIFIER_INTERFACE)Ljava/lang/Object;
                        move-result-object v$modifierRegister
                        check-cast v$modifierRegister, $modifierType
                    """
                )
            }
        }

        if (is_2026_45_0_or_greater) {
            CreateButtonOffsetFingerprint.let {
                it.method.apply {
                    val index = it.instructionMatches[2].index
                    val register = getInstruction<TwoRegisterInstruction>(index).registerA

                    addInstructions(
                        index + 1,
                        """
                            invoke-static { v$register }, $EXTENSION_CLASS->getCreateButtonOffset(F)F
                            move-result v$register
                        """
                    )
                }
            }
        }

        // endregion

        // region Bottom bar pill.

        val bottomNavButtonMatches = BottomNavButtonFingerprint.matchAll()
        if (bottomNavButtonMatches.isEmpty()) {
            throw PatchException("No BottomNavButtonFingerprint matches found")
        }

        bottomNavButtonMatches.forEach { match ->
            match.method.addInstructions(
                0,
                """
                    invoke-static/range { p3 .. p4 }, $EXTENSION_CLASS->getBarButtonModifier(Z$MODIFIER_INTERFACE)Ljava/lang/Object;
                    move-result-object p4
                    check-cast p4, $modifierType
                """
            )
        }

        // endregion

        // region Search button.

        // Older versions do not tell if the current screen shows the bar,
        // so the button would stay on top of screens opened from Home.
        if (is_2026_47_0_or_greater) {
            IsSearchButtonSupportedFingerprint.method.returnEarly(true)

            HomeTopBarFingerprint.let {
                it.method.apply {
                    fun hookModifier(index: Int, register: Int, hookMethod: String) {
                        addInstructionsAtControlFlowLabel(
                            index,
                            """
                                invoke-static/range { v$register .. v$register }, $EXTENSION_CLASS->$hookMethod($MODIFIER_INTERFACE)Ljava/lang/Object;
                                move-result-object v$register
                                check-cast v$register, $modifierType
                            """
                        )
                    }

                    // Hook the last instructions first, so the earlier indexes stay the same.
                    val avatarMatch = it.instructionMatches[4]
                    hookModifier(
                        avatarMatch.index,
                        avatarMatch.instruction.registersUsed[3],
                        "getTopBarButtonModifier"
                    )

                    val fieldMatch = it.instructionMatches[3]
                    val fieldParameters = fieldMatch.methodReference.parameterTypes.map { type -> type.toString() }
                    val fieldRegisters = fieldMatch.instruction.registersUsed
                    val composerType = parameterTypes[parameterTypes.size - 2].toString()
                    val composerRegister = fieldRegisters[fieldParameters.indexOf(composerType)]
                    val clickRegister = fieldRegisters[fieldParameters.indexOf("Lkotlin/jvm/functions/Function0;")]

                    addInstruction(
                        fieldMatch.index + 1,
                        "invoke-static/range { v$composerRegister .. v$composerRegister }, " +
                            "$EXTENSION_CLASS->inlineTopTabs(Ljava/lang/Object;)V"
                    )

                    addInstructionsAtControlFlowLabel(
                        fieldMatch.index,
                        """
                            invoke-static/range { v$clickRegister .. v$clickRegister }, $EXTENSION_CLASS->getSearchFieldClick(Lkotlin/jvm/functions/Function0;)Lkotlin/jvm/functions/Function0;
                            move-result-object v$clickRegister
                        """
                    )
                    hookModifier(
                        fieldMatch.index,
                        fieldRegisters[fieldParameters.indexOf(modifierType)],
                        "getSearchFieldModifier"
                    )

                    val menuMatch = it.instructionMatches[1]
                    hookModifier(
                        menuMatch.index,
                        menuMatch.instruction.registersUsed[1],
                        "getTopBarButtonModifier"
                    )

                    val belowRegister = parameterTypes.indexOfFirst { type ->
                        type.toString() == "Lkotlin/jvm/functions/Function2;"
                    }

                    addInstructions(
                        0,
                        """
                            invoke-static/range { p$belowRegister .. p$belowRegister }, $EXTENSION_CLASS->getTopBarBelow(Lkotlin/jvm/functions/Function2;)Lkotlin/jvm/functions/Function2;
                            move-result-object p$belowRegister
                        """
                    )
                }
            }

            ScreenAttachFingerprint.method.addInstruction(
                0,
                "invoke-static/range { p0 .. p1 }, $EXTENSION_CLASS->onScreenAttached(Ljava/lang/Object;Landroid/view/View;)V"
            )

            ScreenDetachFingerprint.method.addInstruction(
                0,
                "invoke-static/range { p0 .. p1 }, $EXTENSION_CLASS->onScreenDetached(Ljava/lang/Object;Landroid/view/View;)V"
            )

            // Tabs in top bar.

            val tabsCallMatch = HomeTabBarFingerprint.instructionMatches[1]
            val tabsCall = tabsCallMatch.methodReference
            val pagerType = tabsCall.parameterTypes[1].toString()
            val tabsClass = tabsCall.definingClass
            val pagerRegister = tabsCallMatch.instruction.registersUsed[1]

            HomeTabBarFingerprint.method.apply {
                addInstructionsAtControlFlowLabel(
                    tabsCallMatch.index,
                    "invoke-static/range { v$pagerRegister .. v$pagerRegister }, " +
                        "$EXTENSION_CLASS->setTopTabsPager(Ljava/lang/Object;)V"
                )
                addInstructions(
                    0,
                    """
                        invoke-static/range { p3 .. p3 }, $EXTENSION_CLASS->getTopTabsEvenly(Z)Z
                        move-result p3
                    """
                )
            }

            val indicatorMethod = Fingerprint(
                definingClass = tabsClass,
                accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
                returnType = "V",
                parameters = listOf("I", "L", "L", "Z")
            ).method

            val roundedCornerShapeMethod = Fingerprint(
                definingClass = tabsClass,
                accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
                returnType = "V",
                parameters = listOf("I", "L", "L", "Z"),
                filters = listOf(
                    literal(2.0f),
                    methodCall(
                        opcode = Opcode.INVOKE_STATIC,
                        parameters = listOf("F"),
                        returnType = "L",
                        location = MatchAfterWithin(6)
                    )
                )
            ).instructionMatches[1].methodReference

            NewRoundedCornerShapeFingerprint.method.addFreeRegisters(1).first.addInstructions(
                0,
                """
                    invoke-static { p0 }, $roundedCornerShapeMethod
                    move-result-object v0
                    return-object v0
                """
            )

            val indicatorCallerMatches = Fingerprint(
                filters = listOf(
                    checkCast(pagerType),
                    methodCall(reference = indicatorMethod)
                )
            ).matchAll()

            if (indicatorCallerMatches.isEmpty()) {
                throw PatchException("No callers of HomeTabBar indicator found")
            }

            indicatorCallerMatches.asReversed().forEach { match ->
                match.method.apply {
                    val callPagerRegister = getInstruction<OneRegisterInstruction>(
                        match.instructionMatches[0].index
                    ).registerA
                    val indicatorCallMatch = match.instructionMatches[1]
                    val modifierRegister = indicatorCallMatch.instruction.registersUsed[2]

                    if (callPagerRegister > 15) {
                        throw PatchException("callPagerRegister $callPagerRegister > 15")
                    }
                    if (modifierRegister > 15) {
                        throw PatchException("modifierRegister $modifierRegister > 15")
                    }

                    addInstructionsAtControlFlowLabel(
                        indicatorCallMatch.index,
                        """
                            invoke-static { v$modifierRegister, v$callPagerRegister }, $EXTENSION_CLASS->getTopTabsIndicatorModifier(${MODIFIER_INTERFACE}Ljava/lang/Object;)Ljava/lang/Object;
                            move-result-object v$modifierRegister
                            check-cast v$modifierRegister, $modifierType
                        """
                    )
                }
            }

            HomeTabBarWrapperFingerprint.method.addInstructions(
                0,
                """
                    invoke-static/range { p3 .. p3 }, $EXTENSION_CLASS->getTopTabsModifier($MODIFIER_INTERFACE)Ljava/lang/Object;
                    move-result-object p3
                    check-cast p3, $modifierType
                """
            )

            val tabBarClassType = HomeTabBarFingerprint.classDef.type
            val heightCallerMatches = Fingerprint(
                returnType = "V",
                filters = listOf(
                    methodCall(reference = HomeTabBarHeightFingerprint.method),
                    opcode(Opcode.MOVE_RESULT, location = MatchAfterImmediately())
                )
            ).matchAll().filter { match -> match.classDef.type != tabBarClassType }

            if (heightCallerMatches.isEmpty()) {
                throw PatchException("No callers of HomeTabBarHeightFingerprint found")
            }

            heightCallerMatches.forEach { match ->
                match.method.apply {
                    val moveResultMatch = match.instructionMatches[1]
                    val heightRegister = getInstruction<OneRegisterInstruction>(moveResultMatch.index).registerA

                    addInstructions(
                        moveResultMatch.index + 1,
                        """
                            invoke-static/range { v$heightRegister .. v$heightRegister }, $EXTENSION_CLASS->getHeaderTabsHeight(F)F
                            move-result v$heightRegister
                        """
                    )
                }
            }

            HomeTabItemFingerprint.let {
                it.method.apply {
                    val itemMatch = it.instructionMatches[3]
                    val modifierRegister = itemMatch.instruction.registersUsed[2]

                    addInstructionsAtControlFlowLabel(
                        itemMatch.index,
                        """
                            invoke-static/range { v$modifierRegister .. v$modifierRegister }, $EXTENSION_CLASS->getTopTabsItemModifier($MODIFIER_INTERFACE)Ljava/lang/Object;
                            move-result-object v$modifierRegister
                            check-cast v$modifierRegister, $modifierType
                        """
                    )

                    val paddingMatch = it.instructionMatches[0]
                    val topRegister = paddingMatch.instruction.registersUsed[2]

                    addInstructionsAtControlFlowLabel(
                        paddingMatch.index,
                        """
                            invoke-static/range { v$topRegister .. v$topRegister }, $EXTENSION_CLASS->getTopTabsItemTopPadding(F)F
                            move-result v$topRegister
                        """
                    )
                }
            }

            HomeTabLabelFingerprint.let {
                it.method.apply {
                    val textMatch = it.instructionMatches[1]
                    val textRegisters = textMatch.instruction.registersUsed
                    var offset = 0
                    val paramOffsets = textMatch.methodReference.parameterTypes.map { type ->
                        offset.also {
                            val typeStr = type.toString()
                            offset += if (typeStr == "J" || typeStr == "D") 2 else 1
                        }
                    }
                    val firstRegister = textRegisters.first()
                    val fontSizeRegister = firstRegister + paramOffsets[3]
                    val lineHeightRegister = firstRegister + paramOffsets[10]
                    val maskRegister = textRegisters.last()

                    addInstructionsAtControlFlowLabel(
                        textMatch.index,
                        """
                            invoke-static/range { v$fontSizeRegister .. v${fontSizeRegister + 1} }, $EXTENSION_CLASS->getTopTabsLabelFontSize(J)J
                            move-result-wide v$fontSizeRegister
                            invoke-static/range { v$lineHeightRegister .. v${lineHeightRegister + 1} }, $EXTENSION_CLASS->getTopTabsLabelLineHeight(J)J
                            move-result-wide v$lineHeightRegister
                            invoke-static/range { v$maskRegister .. v$maskRegister }, $EXTENSION_CLASS->getTopTabsLabelMask(I)I
                            move-result v$maskRegister
                        """
                    )
                }
            }
        }

        // endregion

        // region Labels.

        SideNavRailContentFingerprint.instructionMatches.last().getMethodCalled().addInstructions(
            0,
            """
                invoke-static { p2 }, $EXTENSION_CLASS->showLabel(Z)Z
                move-result p2
            """
        )

        // endregion

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
