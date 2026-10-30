/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.misc.navigation

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation.MatchAfterImmediately
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.opcode
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.youtube.misc.backgesture.addBackPressedHook
import app.morphe.patches.youtube.misc.backgesture.addPredictiveBackGestureHook
import app.morphe.patches.youtube.misc.backgesture.backGesturePatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.playservice.is_20_28_or_greater
import app.morphe.patches.youtube.misc.playservice.is_21_43_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.shared.ActionBarSearchResultsFingerprint
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.util.MethodUtil
import java.lang.ref.WeakReference

internal const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/shared/NavigationBar;"
internal const val EXTENSION_NAVIGATION_BUTTON_CLASS =
    $$"Lapp/morphe/extension/youtube/shared/NavigationBar$NavigationButton;"
private const val EXTENSION_TOOLBAR_INTERFACE =
    $$"Lapp/morphe/extension/youtube/shared/NavigationBar$AppCompatToolbarPatchInterface;"

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
                "(${EXTENSION_NAVIGATION_BUTTON_CLASS}Landroid/view/View;)V",
    )
}

private lateinit var browseIdMethodRef: WeakReference<MutableMethod>
private var browseIdInsertIndex = -1
private var browseIdRegister = -1

private lateinit var searchResultsMethodRef: WeakReference<MutableMethod>

/**
 * Calls the extension method with the browse id of every browse page that is shown, including
 * a page that is returned to and served from cache. Channel pages use the channel id as browse id.
 *
 * @param descriptor Static extension method with a single String parameter, which can be null.
 */
fun addBrowseIdHook(descriptor: String) {
    browseIdMethodRef.get()!!.addInstruction(
        browseIdInsertIndex,
        "invoke-static { v$browseIdRegister }, $descriptor"
    )
}

/**
 * Calls the extension method when the search feed is shown. It is not a browse page,
 * so returning to it from a browse page does not call the hooks of [addBrowseIdHook].
 *
 * @param descriptor Static extension method with no parameters.
 */
fun addSearchResultsHook(descriptor: String) {
    searchResultsMethodRef.get()!!.addInstruction(
        0,
        "invoke-static { }, $descriptor"
    )
}

internal val browseIdHookPatch = bytecodePatch(description = "Hooks the browse id of the page that is shown.") {
    dependsOn(sharedExtensionPatch)

    execute {
        BrowseFragmentOnCreateViewFingerprint.let {
            it.method.apply {
                // The endpoint of the page is read just before it is checked for browse data.
                val endpointIndex = it.instructionMatches[1].index
                val endpointField = getInstruction<ReferenceInstruction>(endpointIndex)
                    .getReference<FieldReference>()!!

                // Browse data is an extension of the endpoint, and reading it starts with that field.
                val browseDataExtensionIndex = indexOfFirstInstructionOrThrow(
                    endpointIndex, Opcode.SGET_OBJECT
                )
                val browseDataExtensionField = getInstruction<ReferenceInstruction>(browseDataExtensionIndex)
                    .getReference<FieldReference>()!!

                // Matches two 100% identical methods, either method works here.
                // 21.44+ has a third method that reads a different string of the browse data
                // and checks it is not empty, instead of returning it directly.
                val browseIdMethod = Fingerprint(
                    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
                    returnType = "Ljava/lang/String;",
                    parameters = listOf(endpointField.type),
                    filters = listOf(
                        fieldAccess(opcode = Opcode.SGET_OBJECT, reference = browseDataExtensionField),
                        fieldAccess(opcode = Opcode.IGET_OBJECT, type = "Ljava/lang/String;"),
                        anyInstruction(
                            opcode(Opcode.RETURN_OBJECT),
                            // 21.44+ jumps to the return.
                            opcode(Opcode.GOTO),
                            location = MatchAfterImmediately()
                        )
                    )
                ).matchAll(1..2).first().method

                // The fragment is handed the endpoint of every page it shows, including a page that
                // is returned to and served from cache, which makes no browse request to read.
                Fingerprint(
                    definingClass = definingClass,
                    returnType = "V",
                    parameters = listOf(endpointField.type),
                    filters = listOf(
                        fieldAccess(opcode = Opcode.IPUT_OBJECT, reference = endpointField)
                    )
                ).method.apply {
                    val insertIndex = indexOfFirstInstructionOrThrow(
                        fieldAccess(opcode = Opcode.IPUT_OBJECT, reference = endpointField)
                    ) + 1
                    val freeRegister = findFreeRegister(insertIndex)

                    addInstructions(
                        insertIndex,
                        """
                            invoke-static { p1 }, $browseIdMethod
                            move-result-object v$freeRegister
                        """
                    )

                    browseIdMethodRef = WeakReference(this)
                    browseIdInsertIndex = insertIndex + 2
                    browseIdRegister = freeRegister
                }
            }
        }

        searchResultsMethodRef = WeakReference(SearchResultsFragmentOnCreateViewFingerprint.method)
    }
}

val navigationBarHookPatch = bytecodePatch(description = "Hooks the active navigation or search bar.") {
    dependsOn(
        sharedExtensionPatch,
        versionCheckPatch,
        backGesturePatch,
        playerTypeHookPatch, // Required to detect the search bar in all situations.
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
                        "$EXTENSION_CLASS->${hook.methodName}(${hook.parameters})V",
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
            if (!is_21_43_or_greater) {
                val drawableTabMethod = PivotBarButtonsCreateDrawableViewFingerprint.method
                addHook(NavigationHook.NAVIGATION_TAB_LOADED) predicate@{
                    MethodUtil.methodSignaturesMatch(
                        getReference<MethodReference>() ?: return@predicate false,
                        drawableTabMethod,
                    )
                }
            }

            if (!is_20_28_or_greater) {
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
                            "$EXTENSION_CLASS->navigationTabSelected(Landroid/view/View;Z)V",
                )
            }
        }

        // Hook onto navigation bar touches. Needed to detect a tap on the selected
        // navigation button closes the search, as the button is not selected again.
        PivotBarDispatchTouchEventFingerprint.method.addInstruction(
            0,
            "invoke-static { p1 }, $EXTENSION_CLASS->navigationBarTouched(Landroid/view/MotionEvent;)V",
        )

        // Hook onto back button pressed. Needed to fix race problem with
        // Litho filtering based on navigation tab before the tab is updated.
        addBackPressedHook(EXTENSION_CLASS)
        addPredictiveBackGestureHook(EXTENSION_CLASS)

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
                            "$EXTENSION_CLASS->searchBarResultsViewLoaded(Landroid/view/View;)V",
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
                    "invoke-static { v$register }, $EXTENSION_CLASS->setToolbar(Landroid/widget/FrameLayout;)V"
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
                        invoke-static { v$enumMapRegister, v$freeRegister }, $EXTENSION_CLASS->setCairoNotificationFilledIcon(Ljava/util/EnumMap;Ljava/lang/Enum;)V
                    """
                )
            }
        }
    }
}

private enum class NavigationHook(val methodName: String, val parameters: String) {
    SET_LAST_APP_NAVIGATION_ENUM("setLastAppNavigationEnum", "Ljava/lang/Enum;"),
    NAVIGATION_TAB_LOADED("navigationTabLoaded", "Landroid/view/View;"),
    NAVIGATION_IMAGE_RESOURCE_TAB_LOADED("navigationImageResourceTabLoaded", "Landroid/view/View;"),
}
