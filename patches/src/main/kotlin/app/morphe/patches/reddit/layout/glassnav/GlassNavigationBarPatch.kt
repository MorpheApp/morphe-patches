/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5272
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.glassnav

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addFreeRegisters
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.registersUsed
import app.morphe.patcher.util.returnEarly
import app.morphe.patches.reddit.layout.autoscroll.ScreenAttachFingerprint
import app.morphe.patches.reddit.layout.autoscroll.ScreenDetachFingerprint
import app.morphe.patches.reddit.misc.compose.MODIFIER_INTERFACE
import app.morphe.patches.reddit.misc.compose.composePatch
import app.morphe.patches.reddit.misc.compose.modifierType
import app.morphe.patches.reddit.misc.feed.feedHookPatch
import app.morphe.patches.reddit.misc.feed.listFeedStateType
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_24_0_or_greater
import app.morphe.patches.reddit.misc.version.is_2026_45_0_or_greater
import app.morphe.patches.reddit.misc.version.is_2026_47_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/GlassNavigationBarPatch;"
private const val TOP_TABS_EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/GlassNavigationBarTopTabs;"
private const val NEUTRAL_COLORS_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/GlassNavigationBarPatch$NeutralColors;"
private const val TOP_TABS_LIST_STATE_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/GlassNavigationBarTopTabs$ListState;"

@Suppress("unused")
val glassNavigationBarPatch = patch(
    name = "Glass navigation bar",
    description = "Adds an option to show the bottom navigation bar, or the side rail on tablets, " +
        "as a floating translucent capsule. This patch works with Reddit 2026.24.0 and newer."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch, composePatch, feedHookPatch)

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

        // The row and the bar are taller if the capsule is taller than the row.
        listOf(BottomNavRowHeightFingerprint, BottomNavBarHeightFingerprint).forEach { fingerprint ->
            fingerprint.method.apply {
                // 50dp height of the row.
                val index = fingerprint.instructionMatches[1].index
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstructions(
                    index + 1,
                    """
                        invoke-static/range { v$register .. v$register }, $EXTENSION_CLASS->getBarRowHeight(F)F
                        move-result v$register
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

        BottomNavButtonFingerprint.matchAll().forEach { match ->
            match.method.apply {
                // Hook the space first, so its index stays the same.
                val spaceIndex = match.instructionMatches[1].index
                val spaceRegister = getInstruction<OneRegisterInstruction>(spaceIndex).registerA

                addInstructions(
                    spaceIndex + 1,
                    """
                        invoke-static/range { v$spaceRegister .. v$spaceRegister }, $EXTENSION_CLASS->getBarButtonTopSpace(F)F
                        move-result v$spaceRegister
                    """
                )

                addInstructions(
                    0,
                    """
                        invoke-static/range { p3 .. p4 }, $EXTENSION_CLASS->getBarButtonModifier(Z$MODIFIER_INTERFACE)Ljava/lang/Object;
                        move-result-object p4
                        check-cast p4, $modifierType
                    """
                )
            }
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

                    // Tabs in top bar.
                    addInstruction(
                        fieldMatch.index + 1,
                        "invoke-static/range { v$composerRegister .. v$composerRegister }, " +
                            "$TOP_TABS_EXTENSION_CLASS->composeTabsInRow(Ljava/lang/Object;)V"
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

                    // Content below the top bar, such as the tabs. No parameter before it is a long.
                    val belowParameter = parameterTypes.indexOfFirst { type ->
                        type.toString() == "Lkotlin/jvm/functions/Function2;"
                    }

                    addInstructions(
                        0,
                        """
                            invoke-static/range { p$belowParameter .. p$belowParameter }, $TOP_TABS_EXTENSION_CLASS->getTopBarBelow(Lkotlin/jvm/functions/Function2;)Lkotlin/jvm/functions/Function2;
                            move-result-object p$belowParameter
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

            val tabRowCallMatch = HomeTabBarFingerprint.instructionMatches[1]

            HomeTabBarFingerprint.method.apply {
                val pagerRegister = tabRowCallMatch.instruction.registersUsed[1]

                addInstructionsAtControlFlowLabel(
                    tabRowCallMatch.index,
                    "invoke-static/range { v$pagerRegister .. v$pagerRegister }, " +
                        "$TOP_TABS_EXTENSION_CLASS->setTabBarPager(Ljava/lang/Object;)V"
                )

                addInstructions(
                    0,
                    """
                        invoke-static/range { p3 .. p3 }, $TOP_TABS_EXTENSION_CLASS->spreadTabsEvenly(Z)Z
                        move-result p3
                    """
                )
            }

            HomeTabBarWrapperFingerprint.method.addInstructions(
                0,
                """
                    invoke-static/range { p3 .. p3 }, $TOP_TABS_EXTENSION_CLASS->getTabBarModifier($MODIFIER_INTERFACE)Ljava/lang/Object;
                    move-result-object p3
                    check-cast p3, $modifierType
                """
            )

            val roundedCornerShapeMethod = TabRowIndicatorFingerprint.instructionMatches[1].methodReference

            NewRoundedCornerShapeFingerprint.method.addFreeRegisters(1).first.addInstructions(
                0,
                """
                    invoke-static { p0 }, $roundedCornerShapeMethod
                    move-result-object v0
                    return-object v0
                """
            )

            TabRowIndicatorCallerFingerprint.matchAll().forEach { match ->
                match.method.apply {
                    val pagerRegister = match.instructionMatches[0].instruction.registersUsed[0]
                    val indicatorMatch = match.instructionMatches[1]
                    val modifierRegister = indicatorMatch.instruction.registersUsed[2]

                    // The registers are not next to each other, so invoke-static/range cannot be used.
                    if (maxOf(pagerRegister, modifierRegister) > 15) {
                        throw PatchException("Register above v15: v$pagerRegister, v$modifierRegister")
                    }

                    addInstructionsAtControlFlowLabel(
                        indicatorMatch.index,
                        """
                            invoke-static { v$modifierRegister, v$pagerRegister }, $TOP_TABS_EXTENSION_CLASS->getIndicatorModifier(${MODIFIER_INTERFACE}Ljava/lang/Object;)Ljava/lang/Object;
                            move-result-object v$modifierRegister
                            check-cast v$modifierRegister, $modifierType
                        """
                    )
                }
            }

            HomeTabBarHeightCallerFingerprint.matchAll().forEach { match ->
                match.method.apply {
                    val moveResultMatch = match.instructionMatches[1]
                    val heightRegister = moveResultMatch.instruction.registersUsed[0]

                    addInstructions(
                        moveResultMatch.index + 1,
                        """
                            invoke-static/range { v$heightRegister .. v$heightRegister }, $TOP_TABS_EXTENSION_CLASS->getHeaderHeight(F)F
                            move-result v$heightRegister
                        """
                    )
                }
            }

            HomeTabItemFingerprint.let {
                it.method.apply {
                    // Hook the last instruction first, so the earlier index stays the same.
                    val itemMatch = it.instructionMatches[3]
                    val modifierRegister = itemMatch.instruction.registersUsed[2]

                    addInstructionsAtControlFlowLabel(
                        itemMatch.index,
                        """
                            invoke-static/range { v$modifierRegister .. v$modifierRegister }, $TOP_TABS_EXTENSION_CLASS->getTabModifier($MODIFIER_INTERFACE)Ljava/lang/Object;
                            move-result-object v$modifierRegister
                            check-cast v$modifierRegister, $modifierType
                        """
                    )

                    val paddingMatch = it.instructionMatches[0]
                    val topRegister = paddingMatch.instruction.registersUsed[2]

                    addInstructionsAtControlFlowLabel(
                        paddingMatch.index,
                        """
                            invoke-static/range { v$topRegister .. v$topRegister }, $TOP_TABS_EXTENSION_CLASS->getTabTopPadding(F)F
                            move-result v$topRegister
                        """
                    )
                }
            }

            HomeTabLabelFingerprint.let {
                it.method.apply {
                    val textMatch = it.instructionMatches[1]
                    val textRegisters = textMatch.instruction.registersUsed
                    val textParameters = textMatch.methodReference.parameterTypes.map { type -> type.toString() }

                    // A long parameter uses two registers.
                    fun parameterRegister(index: Int) = textRegisters.first() +
                        textParameters.take(index).sumOf { type -> if (type == "J" || type == "D") 2 else 1 }

                    val fontSizeRegister = parameterRegister(3)
                    val lineHeightRegister = parameterRegister(10)
                    val defaultMaskRegister = textRegisters.last()

                    addInstructionsAtControlFlowLabel(
                        textMatch.index,
                        """
                            invoke-static/range { v$fontSizeRegister .. v${fontSizeRegister + 1} }, $TOP_TABS_EXTENSION_CLASS->getLabelFontSize(J)J
                            move-result-wide v$fontSizeRegister
                            invoke-static/range { v$lineHeightRegister .. v${lineHeightRegister + 1} }, $TOP_TABS_EXTENSION_CLASS->getLabelLineHeight(J)J
                            move-result-wide v$lineHeightRegister
                            invoke-static/range { v$defaultMaskRegister .. v$defaultMaskRegister }, $TOP_TABS_EXTENSION_CLASS->getLabelDefaultMask(I)I
                            move-result v$defaultMaskRegister
                        """
                    )
                }
            }

            // See-through top bar once the feed scrolls.
            // The app checks if the list is scrolled with a shared lambda class and a case number.
            NestedListTrackerFingerprint.let {
                val scrolledCase = it.instructionMatches[0].getInstruction<NarrowLiteralInstruction>().narrowLiteral
                val scrolledInit = it.instructionMatches[1].methodReference

                mutableClassDefBy(listFeedStateType).addPatchInterface(
                    TOP_TABS_LIST_STATE_INTERFACE,
                    "patch_isScrolled",
                    """
                        new-instance v0, ${scrolledInit.definingClass}
                        const v1, $scrolledCase
                        invoke-direct { v0, p0, v1 }, $scrolledInit
                        invoke-interface { v0 }, Lkotlin/jvm/functions/Function0;->invoke()Ljava/lang/Object;
                        move-result-object v0
                        check-cast v0, Ljava/lang/Boolean;
                        invoke-virtual { v0 }, Ljava/lang/Boolean;->booleanValue()Z
                        move-result v0
                        return v0
                    """
                )
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
