/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */

package app.morphe.patches.music.layout.miniplayer

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.mapping.ResourceType
import app.morphe.patches.shared.misc.mapping.getResourceId
import app.morphe.patches.shared.misc.mapping.resourceMappingPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.util.adoptChild
import app.morphe.util.doRecursively
import app.morphe.util.findFreeRegister
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstLiteralInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import org.w3c.dom.Element

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/music/patches/AddMiniplayerNextPreviousButtonsPatch;"

private const val IMAGE_VIEW_TAG =
    "com.google.android.libraries.youtube.common.ui.TouchImageView"

private const val NEXT_BUTTON_ID = "mini_player_next_button"
private const val PREVIOUS_BUTTON_ID = "mini_player_previous_button"

// Resource IDs resolved from existing layout slots before public.xml is renamed.
private var nextButtonResourceId = -1L
private var previousButtonResourceId = -1L

private val addMiniplayerButtonsResourcePatch = resourcePatch(
    description = "addMiniplayerButtonsResourcePatch"
) {
    dependsOn(resourceMappingPatch)

    execute {
        // Read numeric IDs before renaming so bytecode injection can reference them.
        nextButtonResourceId = getResourceId(ResourceType.ID, "TOP_START")
        previousButtonResourceId = getResourceId(ResourceType.ID, "TOP_END")

        // Rename the reused slots to descriptive IDs for the new buttons.
        val publicFile = get("res/values/public.xml")
        publicFile.writeText(
            publicFile.readText()
                .replace("\"TOP_END\"", "\"$PREVIOUS_BUTTON_ID\"")
                .replace("\"TOP_START\"", "\"$NEXT_BUTTON_ID\"")
        )

        // Inject previous button before play/pause, next button as last child of mini_player.
        var previousButtonInserted = false

        document("res/layout/watch_while_layout.xml").use { document ->
            document.doRecursively loop@{ node ->
                if (node !is Element) return@loop

                val idAttr = node.getAttributeNode("android:id") ?: return@loop

                if (!previousButtonInserted &&
                    idAttr.textContent == "@id/mini_player_play_pause_replay_button"
                ) {
                    val previousButton = node.ownerDocument.createElement(IMAGE_VIEW_TAG).apply {
                        setAttribute("android:id", "@id/$PREVIOUS_BUTTON_ID")
                        setAttribute("android:padding", "@dimen/item_medium_spacing")
                        setAttribute("android:layout_width", "@dimen/remix_generic_button_size")
                        setAttribute("android:layout_height", "@dimen/remix_generic_button_size")
                        setAttribute("android:src", "@drawable/music_player_prev")
                        setAttribute("android:scaleType", "fitCenter")
                        setAttribute("android:contentDescription", "@string/accessibility_previous")
                        setAttribute("style", "@style/MusicPlayerButton")
                    }
                    node.parentNode.insertBefore(previousButton, node)
                    previousButtonInserted = true
                }

                if (idAttr.textContent == "@id/mini_player") {
                    node.adoptChild(IMAGE_VIEW_TAG) {
                        setAttribute("android:id", "@id/$NEXT_BUTTON_ID")
                        setAttribute("android:padding", "@dimen/item_medium_spacing")
                        setAttribute("android:layout_width", "@dimen/remix_generic_button_size")
                        setAttribute("android:layout_height", "@dimen/remix_generic_button_size")
                        setAttribute("android:src", "@drawable/music_player_next")
                        setAttribute("android:scaleType", "fitCenter")
                        setAttribute("android:contentDescription", "@string/accessibility_next")
                        setAttribute("style", "@style/MusicPlayerButton")
                    }
                }
            }
        }
    }
}

@Suppress("unused")
val addMiniplayerNextPreviousButtonsPatch = bytecodePatch(
    name = "Add miniplayer next/previous buttons",
    description = "Adds an option to show next and previous track buttons in the miniplayer."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        resourceMappingPatch,
        addMiniplayerButtonsResourcePatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_music_add_miniplayer_next_button"),
            SwitchPreference("morphe_music_add_miniplayer_previous_button"),
        )

        val playPauseResourceId = getResourceId(ResourceType.ID, "mini_player_play_pause_replay_button")

        // region 1 - Miniplayer constructor: register onClick listeners for both buttons.
        // Injected before the play/pause view lookup so we can reuse the same parent reference.
        MiniPlayerConstructorFingerprint.method.apply {
            fun injectOnClickListener(viewId: Long, extensionMethod: String) {
                val playPauseIndex = indexOfFirstLiteralInstructionOrThrow(playPauseResourceId)
                val findViewByIdIndex = indexOfFirstInstructionOrThrow(playPauseIndex, Opcode.INVOKE_VIRTUAL)
                val parentRegister = getInstruction<FiveRegisterInstruction>(findViewByIdIndex).registerC
                val freeReg = findFreeRegister(playPauseIndex)

                addInstructions(
                    playPauseIndex,
                    """
                        const v$freeReg, $viewId
                        invoke-virtual { v$parentRegister, v$freeReg }, Landroid/view/View;->findViewById(I)Landroid/view/View;
                        move-result-object v$freeReg
                        invoke-static { v$freeReg }, $EXTENSION_CLASS_DESCRIPTOR->$extensionMethod(Landroid/view/View;)V
                    """
                )
            }

            injectOnClickListener(nextButtonResourceId, "setNextButtonOnClickListener")
            injectOnClickListener(previousButtonResourceId, "setPreviousButtonOnClickListener")
        }

        // region 2 - MppWatchWhileLayout.onFinishInflate: store button views and extend view array.
        // setButtonView calls store weak references used by getViewArray.
        // getViewArray wraps the original view array to include the new buttons.
        MppWatchWhileLayoutFingerprint.method.apply {
            fun injectSetButtonView(viewId: Long, extensionMethod: String) {
                val playPauseIndex = indexOfFirstLiteralInstructionOrThrow(playPauseResourceId)
                val findViewByIdIndex = indexOfFirstInstructionOrThrow(playPauseIndex, Opcode.INVOKE_VIRTUAL)
                val thisRegister = getInstruction<FiveRegisterInstruction>(findViewByIdIndex).registerC
                val freeReg = findFreeRegister(playPauseIndex)

                addInstructions(
                    playPauseIndex,
                    """
                        const v$freeReg, $viewId
                        invoke-virtual { v$thisRegister, v$freeReg }, $definingClass->findViewById(I)Landroid/view/View;
                        move-result-object v$freeReg
                        invoke-static { v$freeReg }, $EXTENSION_CLASS_DESCRIPTOR->$extensionMethod(Landroid/view/View;)V
                    """
                )
            }

            injectSetButtonView(nextButtonResourceId, "setNextButtonView")
            injectSetButtonView(previousButtonResourceId, "setPreviousButtonView")

            val newArrayIndex = indexOfFirstInstructionOrThrow(Opcode.NEW_ARRAY)
            val invokeStaticIndex = indexOfFirstInstructionOrThrow(newArrayIndex, Opcode.INVOKE_STATIC)
            val viewArrayRegister = getInstruction<FiveRegisterInstruction>(invokeStaticIndex).registerC

            addInstructions(
                invokeStaticIndex,
                """
                    invoke-static { v$viewArrayRegister }, $EXTENSION_CLASS_DESCRIPTOR->getViewArray([Landroid/view/View;)[Landroid/view/View;
                    move-result-object v$viewArrayRegister
                """
            )
        }
    }
}
