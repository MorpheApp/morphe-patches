/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.navigation

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.misc.mapping.resourceMappingPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.playservice.is_19_35_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_21_or_greater
import app.morphe.patches.youtube.misc.playservice.is_20_28_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.shared.ActionBarSearchResultsFingerprint
import app.morphe.patches.youtube.shared.YouTubeMainActivityOnBackPressedFingerprint
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources
import app.morphe.util.findFreeRegister
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.util.MethodUtil
import java.lang.ref.WeakReference

internal const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/shared/NavigationBar;"
internal const val EXTENSION_NAVIGATION_BUTTON_DESCRIPTOR =
    "Lapp/morphe/extension/youtube/shared/NavigationBar\$NavigationButton;"
private const val EXTENSION_TOOLBAR_INTERFACE =
    "Lapp/morphe/extension/youtube/shared/NavigationBar${'$'}AppCompatToolbarPatchInterface;"

private lateinit var hookNavigationButtonCreatedMethodRef : WeakReference<MutableMethod>

private lateinit var bottomBarContainerMethodRef: WeakReference<MutableMethod>
private var bottomBarContainerInsertIndex = -1
private var bottomBarContainerRegister = -1
private var bottomBarContainerOffset = 0

fun addBottomBarContainerHook(
    descriptor: String,
    highPriority: Boolean = false
) {
    val insertIndex = if (highPriority) {
        bottomBarContainerInsertIndex
    } else {
        bottomBarContainerInsertIndex + bottomBarContainerOffset
    }

    bottomBarContainerMethodRef.get()!!.addInstruction(
        insertIndex,
        "invoke-static { v$bottomBarContainerRegister }, $descriptor"
    )

    bottomBarContainerOffset++
}

fun hookNavigationButtonCreated(extensionClassDescriptor: String) {
    hookNavigationButtonCreatedMethodRef.get()!!.addInstruction(
        0,
        "invoke-static { p0, p1 }, $extensionClassDescriptor->navigationTabCreated" +
                "(${EXTENSION_NAVIGATION_BUTTON_DESCRIPTOR}Landroid/view/View;)V",
    )
}

val navigationBarHookPatch = bytecodePatch(description = "Hooks the active navigation or search bar.") {
    dependsOn(
        sharedExtensionPatch,
        versionCheckPatch,
        playerTypeHookPatch, // Required to detect the search bar in all situations.
        resourceMappingPatch, // Used by fingerprints
        resourcePatch {
            // Copy missing notification icon.
            execute {
                copyResources(
                    "navigationbuttons",
                    ResourceGroup(
                        "drawable",
                        "morphe_fill_bell_cairo_black_24.xml"
                    )
                )
            }
        }
    )

    execute {
        fun MutableMethod.addHook(hook: NavigationHook, insertPredicate: Instruction.() -> Boolean) {
            val filtered = instructions.filter(insertPredicate)
            if (filtered.isEmpty()) throw PatchException("Could not find insert indexes")
            filtered.forEach {
                val insertIndex = it.location.index + 2
                val register = getInstruction<OneRegisterInstruction>(insertIndex - 1).registerA

                addInstruction(
                    insertIndex,
                    "invoke-static { v$register }, " +
                        "$EXTENSION_CLASS_DESCRIPTOR->${hook.methodName}(${hook.parameters})V",
                )
            }
        }

        InitializeButtonsFingerprint.method.apply {
            // Hook the current navigation bar enum value. Note, the 'You' tab does not have an enum value.
            val navigationEnumClassName = NavigationEnumFingerprint.classDef.type
            addHook(NavigationHook.SET_LAST_APP_NAVIGATION_ENUM) {
                opcode == Opcode.INVOKE_STATIC &&
                    getReference<MethodReference>()?.definingClass == navigationEnumClassName
            }

            // Hook the creation of navigation tab views.
            val drawableTabMethod = PivotBarButtonsCreateDrawableViewFingerprint.method
            addHook(NavigationHook.NAVIGATION_TAB_LOADED) predicate@{
                MethodUtil.methodSignaturesMatch(
                    getReference<MethodReference>() ?: return@predicate false,
                    drawableTabMethod,
                )
            }

            if (is_20_21_or_greater && !is_20_28_or_greater) {
                val imageResourceIntTabMethod = PivotBarButtonsCreateResourceIntViewFingerprint.originalMethod
                addHook(NavigationHook.NAVIGATION_TAB_LOADED) predicate@{
                    MethodUtil.methodSignaturesMatch(
                        getReference<MethodReference>() ?: return@predicate false,
                        imageResourceIntTabMethod,
                    )
                }
            }

            val imageResourceTabMethod = PivotBarButtonsCreateResourceStyledViewFingerprint.originalMethod
            addHook(NavigationHook.NAVIGATION_IMAGE_RESOURCE_TAB_LOADED) predicate@{
                MethodUtil.methodSignaturesMatch(
                    getReference<MethodReference>() ?: return@predicate false,
                    imageResourceTabMethod,
                )
            }
        }

        PivotBarButtonsViewSetSelectedFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.first().index
                val instruction = getInstruction<FiveRegisterInstruction>(index)
                val viewRegister = instruction.registerC
                val isSelectedRegister = instruction.registerD

                addInstruction(
                    index + 1,
                    "invoke-static { v$viewRegister, v$isSelectedRegister }, " +
                            "$EXTENSION_CLASS_DESCRIPTOR->navigationTabSelected(Landroid/view/View;Z)V",
                )
            }
        }

        // Hook onto back button pressed. Needed to fix race problem with
        // Litho filtering based on navigation tab before the tab is updated.
        YouTubeMainActivityOnBackPressedFingerprint.method.addInstruction(
            0,
            "invoke-static { p0 }, $EXTENSION_CLASS_DESCRIPTOR->onBackPressed(Landroid/app/Activity;)V",
        )

        // Hook the search bar.

        // Two different layouts are used at the hooked code.
        // Insert before the first ViewGroup method call after inflating,
        // so this works regardless which layout is used.
        ActionBarSearchResultsFingerprint.let {
            it.clearMatch()
            it.method.apply {
                val instructionIndex = it.instructionMatches[1].index
                val viewRegister = getInstruction<FiveRegisterInstruction>(instructionIndex).registerC

                addInstruction(
                    instructionIndex,
                    "invoke-static { v$viewRegister }, " +
                            "$EXTENSION_CLASS_DESCRIPTOR->searchBarResultsViewLoaded(Landroid/view/View;)V",
                )
            }
        }

        // Hook the back button visibility.

        ToolbarLayoutFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.last().index
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstruction(
                    index + 1,
                    "invoke-static { v$register }, $EXTENSION_CLASS_DESCRIPTOR->setToolbar(Landroid/widget/FrameLayout;)V"
                )
            }
        }

        // Add interface for extensions code to call obfuscated methods.
        AppCompatToolbarBackButtonFingerprint.let {
            it.classDef.apply {
                interfaces.add(EXTENSION_TOOLBAR_INTERFACE)

                val definingClass = type
                val obfuscatedMethodName = it.originalMethod.name
                val returnType = "Landroid/graphics/drawable/Drawable;"

                methods.add(
                    ImmutableMethod(
                        definingClass,
                        "patch_getNavigationIcon",
                        listOf(),
                        returnType,
                        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                        null,
                        null,
                        MutableMethodImplementation(2),
                    ).toMutable().apply {
                        addInstructions(
                            0,
                            """
                                invoke-virtual { p0 }, $definingClass->$obfuscatedMethodName()$returnType
                                move-result-object v0
                                return-object v0
                            """
                        )
                    }
                )
            }
        }

        hookNavigationButtonCreatedMethodRef = WeakReference(
            NavigationBarHookCallbackFingerprint.method
        )

        InitializeBottomBarContainerFingerprint.let {
            it.method.apply {
                bottomBarContainerMethodRef = WeakReference(this)
                bottomBarContainerInsertIndex = it.instructionMatches.last().index
                bottomBarContainerRegister =
                    getInstruction<FiveRegisterInstruction>(bottomBarContainerInsertIndex).registerC
            }
        }

        // Fix YT bug of notification tab missing the filled icon.
        if (is_19_35_or_greater) {
            val cairoNotificationEnumReference = ImageEnumConstructorFingerprint
                .instructionMatches.last().getInstruction<ReferenceInstruction>().reference

            SetEnumMapFingerprint.let {
                it.method.apply {
                    val setEnumIntegerIndex = it.instructionMatches.last().index
                    val enumMapRegister = getInstruction<FiveRegisterInstruction>(setEnumIntegerIndex).registerC
                    val insertIndex = setEnumIntegerIndex + 1
                    val freeRegister = findFreeRegister(insertIndex, enumMapRegister)

                    addInstructions(
                        insertIndex,
                        """
                            sget-object v$freeRegister, $cairoNotificationEnumReference
                            invoke-static { v$enumMapRegister, v$freeRegister }, $EXTENSION_CLASS_DESCRIPTOR->setCairoNotificationFilledIcon(Ljava/util/EnumMap;Ljava/lang/Enum;)V
                        """
                    )
                }
            }
        }
    }
}

private enum class NavigationHook(val methodName: String, val parameters: String) {
    SET_LAST_APP_NAVIGATION_ENUM("setLastAppNavigationEnum", "Ljava/lang/Enum;"),
    NAVIGATION_TAB_LOADED("navigationTabLoaded", "Landroid/view/View;"),
    NAVIGATION_IMAGE_RESOURCE_TAB_LOADED("navigationImageResourceTabLoaded", "Landroid/view/View;"),
}
