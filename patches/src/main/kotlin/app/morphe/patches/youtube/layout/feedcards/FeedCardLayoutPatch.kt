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
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.layout.theme.lithoColorHookPatch
import app.morphe.patches.shared.layout.theme.lithoDrawableColorHook
import app.morphe.patches.shared.misc.fix.proto.fixProtoLibraryPatch
import app.morphe.patches.shared.misc.fix.proto.parseByteArrayWithRegistryMethodRef
import app.morphe.patches.shared.misc.proto.hookElement
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.youtube.layout.hide.player.popup.PlayerPopupPanelsFingerprint
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_21_30_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.proto.elementProtoParserHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.shared.hookVideoIntentCommand
import app.morphe.patches.youtube.shared.openVideoIntentPatch
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
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
val feedCardLayoutPatch = bytecodePatch(
    name = "Feed card layout",
    description = "Adds an option to show or hide the card layout of feed videos, which YouTube sends as an A/B test."
) {
    dependsOn(
        sharedExtensionPatch,
        openVideoIntentPatch,
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
            DividerInfoLookupFingerprint(dividerInfoClass.type).methodOrNull?.addInstructions(
                0,
                """
                    invoke-static { p1 }, $EXTENSION_CLASS->getDividerItem(Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object p1
                """
            )

            // The cards are found by the bytes of their element.
            ElementItemConstructorFingerprint.let {
                val elementBytesField = it.instructionMatches[1].getFieldAccessed()

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

        // The comment button opens the video, and the comments are opened when the watch page is ready.
        hookVideoIntentCommand("$EXTENSION_CLASS->onWatchCommand(Ljava/lang/Object;Ljava/util/Map;)V")

        val uiReadyCommandMethod = PlayerPopupPanelsFingerprint.method
        val commandType = uiReadyCommandMethod.parameterTypes.first().toString()
        val commandDefaultInstance = classDefBy(commandType).fields.single {
            AccessFlags.STATIC.isSet(it.accessFlags) && it.type == commandType
        }
        // The command executor of the method, and its method that runs a command.
        val (executorField, executeMethod) = uiReadyCommandMethod.let {
            val executeIndex = it.indexOfFirstInstructionReversedOrThrow(Opcode.INVOKE_INTERFACE)
            val executorIndex = it.indexOfFirstInstructionReversedOrThrow(executeIndex, Opcode.IGET_OBJECT)

            Pair(
                it.getInstruction(executorIndex).getReference<FieldReference>()!!,
                it.getInstruction(executeIndex).getReference<MethodReference>()!!
            )
        }

        WatchNextUiReadyFingerprint(uiReadyCommandMethod).let {
            it.method.apply {
                val responseField = it.instructionMatches.first().getFieldAccessed()
                val uiReadyCommandField = it.instructionMatches[3].getFieldAccessed()
                val parameterType = parameterTypes.first().toString()

                it.classDef.methods.add(
                    ImmutableMethod(
                        definingClass,
                        "patch_openFeedCardComments",
                        listOf(ImmutableMethodParameter(parameterType, null, null)),
                        "V",
                        AccessFlags.PRIVATE.value or AccessFlags.FINAL.value,
                        null,
                        null,
                        MutableMethodImplementation(5),
                    ).toMutable().apply {
                        addInstructionsWithLabels(
                            0,
                            """
                                if-eqz p1, :exit
                                iget-object v0, p1, $responseField
                                invoke-static { v0 }, $EXTENSION_CLASS->getCommentsCommand(Ljava/lang/Object;)[B
                                move-result-object v1
                                if-eqz v1, :exit

                                # Parse the command with the extensions of the app.
                                sget-object v0, $commandDefaultInstance
                                invoke-static { }, Lcom/google/protobuf/ExtensionRegistryLite;->getGeneratedRegistry()Lcom/google/protobuf/ExtensionRegistryLite;
                                move-result-object v2
                                invoke-static { v0, v1, v2 }, ${parseByteArrayWithRegistryMethodRef.get()!!}
                                move-result-object v0
                                check-cast v0, $commandType

                                # Run the command, without the popup panel checks of the watch next command.
                                new-instance v1, Ljava/util/HashMap;
                                invoke-direct { v1 }, Ljava/util/HashMap;-><init>()V
                                iget-object v2, p0, $uiReadyCommandField
                                iget-object v2, v2, $executorField
                                check-cast v2, ${executeMethod.definingClass}
                                invoke-interface { v2, v0, v1 }, $executeMethod

                                :exit
                                return-void
                            """
                        )
                    }
                )

                addInstruction(
                    0,
                    "invoke-direct/range { p0 .. p1 }, $definingClass->patch_openFeedCardComments($parameterType)V"
                )
            }
        }
    }
}
