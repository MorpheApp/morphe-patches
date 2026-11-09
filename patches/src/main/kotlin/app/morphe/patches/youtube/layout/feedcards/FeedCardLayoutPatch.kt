/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3768
 * https://github.com/MorpheApp/morphe-patches/pull/3769
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.feedcards

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.layout.theme.lithoColorHookPatch
import app.morphe.patches.shared.layout.theme.lithoDrawableColorHook
import app.morphe.patches.shared.misc.fix.proto.fixProtoLibraryPatch
import app.morphe.patches.shared.misc.proto.hookElement
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.youtube.misc.commandrouter.commandRouterHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_21_30_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.proto.elementProtoParserHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.shared.hookVideoIntentInterceptor
import app.morphe.patches.youtube.shared.openVideoIntentPatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/FeedCardLayoutPatch;"

private const val EXTENSION_ELEMENT_ITEM_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/FeedCardLayoutPatch$ElementItemInterface;"

private const val RECYCLER_VIEW_CLASS = "Landroid/support/v7/widget/RecyclerView;"

/**
 * Calls the extension method with the list and the parameter at the start of a method of the list,
 * which overrides a method of ViewGroup. If the list doesn't override the method, the method is added
 * and calls the method of ViewGroup first.
 */
private fun MutableClass.hookViewGroupMethod(name: String, parameterType: String, extensionMethodName: String) {
    val extensionMethod = "$EXTENSION_CLASS->$extensionMethodName($RECYCLER_VIEW_CLASS$parameterType)V"
    val method = methods.firstOrNull { method ->
        method.name == name && method.returnType == "V" && method.parameterTypes == listOf(parameterType)
    }

    if (method != null) {
        method.addInstruction(0, "invoke-static/range { p0 .. p1 }, $extensionMethod")
        return
    }

    methods.add(
        ImmutableMethod(
            type,
            name,
            listOf(ImmutableMethodParameter(parameterType, null, null)),
            "V",
            AccessFlags.PUBLIC.value,
            null,
            null,
            MutableMethodImplementation(2),
        ).toMutable().apply {
            addInstructions(
                0,
                """
                    invoke-super { p0, p1 }, Landroid/view/ViewGroup;->$name($parameterType)V
                    invoke-static { p0, p1 }, $extensionMethod
                    return-void
                """
            )
        }
    )
}

@Suppress("unused")
val feedCardLayoutPatch = patch(
    name = "Feed card layout",
    description = "Adds an option to show or hide the card layout of feed videos, which YouTube sends as an A/B test."
) {
    dependsOn(
        sharedExtensionPatch,
        openVideoIntentPatch,
        commandRouterHookPatch,
        fixProtoLibraryPatch,
        settingsPatch,
        elementProtoParserHookPatch,
        versionCheckPatch,
        lithoColorHookPatch { is_21_30_or_greater },
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.FEED.addPreferences(
            ListPreference("morphe_feed_card_layout")
        )

        // The layout is chosen by the experiments that the server sends in the context of every element.
        hookElement("$EXTENSION_CLASS->setFeedCardLayout")

        // The theme patch changes the background of the cards to the background color of the feed.
        lithoDrawableColorHook("$EXTENSION_CLASS->getCardColor(Landroid/graphics/drawable/Drawable;I)I")

        // Some lists, such as the Home feed, add a spacing between the items, and the cards have their own spacing.
        // The item is replaced by an item without spacing.
        DividerInfoToStringFingerprint.originalClassDefOrNull?.let { dividerInfoClass ->
            DividerInfoLookupFingerprint(dividerInfoClass.type).methodOrNull?.let { dividerInfoLookupMethod ->
                dividerInfoLookupMethod.addInstructions(
                    0,
                    """
                        invoke-static { p1 }, $EXTENSION_CLASS->getDividerItem(Ljava/lang/Object;)Ljava/lang/Object;
                        move-result-object p1
                    """
                )

                // The header of a horizontal shelf has the label that finds the shelf in the list, and it is
                // not mounted while it is scrolled out of the list. The shelves are also found by their element,
                // which the list looks up with the view of the item when it sets the spacing of the item.
                DividerItemOffsetsFingerprint(dividerInfoLookupMethod).let {
                    it.methodOrNull?.apply {
                        val lookupIndex = it.instructionMatches.first().index
                        val itemRegister = getInstruction<FiveRegisterInstruction>(lookupIndex).registerD

                        addInstruction(
                            lookupIndex,
                            "invoke-static/range { v$itemRegister .. v$itemRegister }, $EXTENSION_CLASS->setDividerItem(Ljava/lang/Object;)V"
                        )
                        addInstruction(
                            0,
                            "invoke-static/range { p2 .. p2 }, $EXTENSION_CLASS->setDividerItemView(Landroid/view/View;)V"
                        )
                    }
                }
            }

            // The cards are found by the bytes of their element.
            ElementItemConstructorFingerprint.let {
                val elementBytesField = it.instructionMatches[1].fieldReference

                it.classDef.apply {
                    interfaces.add(EXTENSION_ELEMENT_ITEM_INTERFACE)

                    methods.add(
                        ImmutableMethod(
                            type,
                            "patch_getElementBytes",
                            listOf(),
                            "[B",
                            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                            null,
                            null,
                            MutableMethodImplementation(2),
                        ).toMutable().apply {
                            addInstructions(
                                0,
                                """
                                    iget-object v0, p0, $elementBytesField
                                    return-object v0
                                """
                            )
                        }
                    )
                }
            }
        }

        mutableClassDefBy(RECYCLER_VIEW_CLASS).apply {
            // The cards have rounded corners, which are not part of the element.
            hookViewGroupMethod("onViewAdded", "Landroid/view/View;", "onChildAdded")

            // The Shorts shelf and the horizontal shelves have the background of the cards, which the list draws
            // below its items, because the views of the elements reset their own background.
            hookViewGroupMethod("onDraw", "Landroid/graphics/Canvas;", "onListDraw")
        }

        // The comment button opens the comments panel of the video, instead of the video.
        hookVideoIntentInterceptor(EXTENSION_CLASS, "openCommentsPanel")

        // The comments panel is shown over the feed, and the commands of the comments,
        // such as the command that opens the replies, show their panel over the feed too.
        EngagementPanelControllerOfSurfaceFingerprint.let {
            val feedControllerProvider = it.instructionMatches[3].fieldReference
            val getController = it.instructionMatches[4].methodReference
            val controllerClass = it.method.returnType
            val getShownPanelId = EngagementPanelControllerShownPanelIdFingerprint.method.let { method ->
                "${method.definingClass}->${method.name}()Ljava/lang/String;"
            }

            it.method.addInstructionsWithLabels(
                0,
                """
                    invoke-static { p1 }, $EXTENSION_CLASS->isPanelWithoutSurface(Ljava/lang/Enum;)Z
                    move-result v0
                    if-eqz v0, :surface_controller
                    iget-object v0, p0, $feedControllerProvider
                    invoke-interface { v0 }, $getController
                    move-result-object v0
                    check-cast v0, $controllerClass
                    invoke-virtual { v0 }, $getShownPanelId
                    move-result-object v0
                    invoke-static { v0 }, $EXTENSION_CLASS->showPanelOverFeed(Ljava/lang/String;)Z
                    move-result v0
                    if-eqz v0, :surface_controller
                    iget-object v0, p0, $feedControllerProvider
                    invoke-interface { v0 }, $getController
                    move-result-object v0
                    check-cast v0, $controllerClass
                    return-object v0
                    :surface_controller
                    nop
                """
            )
        }
    }
}
