package app.morphe.patches.music.layout.buttons.navigation

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceXml
import app.morphe.patches.music.layout.miniplayer.miniplayerColorHookPatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.mediasession.addSetPlaybackStateHook
import app.morphe.patches.music.misc.mediasession.mediaSessionHookPatch
import app.morphe.patches.music.misc.playservice.is_9_39_or_greater
import app.morphe.patches.music.misc.playservice.versionCheckPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.util.insertLiteralOverride
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

private const val EXTENSION_CLASS = "Lapp/morphe/extension/music/patches/NavigationBarPatch;"
private const val EXTENSION_GLASS_CLASS = "Lapp/morphe/extension/music/patches/MusicGlassNavigationBar;"
private const val EXTENSION_GLASS_MINIPLAYER_CLASS = "Lapp/morphe/extension/music/patches/MusicGlassMiniplayer;"
private const val EXTENSION_TIME_BAR_INTERFACE = "Lapp/morphe/extension/music/patches/MusicGlassMiniplayer\$TimeBar;"
private const val MUSIC_PLAYBACK_CONTROLS_TIME_BAR_CLASS =
    "Lcom/google/android/apps/youtube/music/watchpage/MusicPlaybackControlsTimeBar;"

@Suppress("unused")
val navigationBarPatch = patch(
    name = "Navigation bar",
    description = "Adds options to hide navigation bar, labels and buttons, and to show the navigation bar and miniplayer as glass."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        // The color of the album art, for the tint of the glass.
        miniplayerColorHookPatch,
        // If a song plays, for the spinning artwork of the glass miniplayer.
        mediaSessionHookPatch,
        patch {
            dependsOn(versionCheckPatch)

            execute {
                // Ensure the first ImageView has 'layout_weight' to stay properly sized
                // when the TextView is hidden.
                resourceXml("res/layout/image_with_text_tab.xml").use { layout ->
                    layout.elements("ImageView").firstOrNull()?.let {
                        if (!it.hasAttribute("android:layout_weight")) it.setAttribute("android:layout_weight", "0.5")
                    }
                }

                // The floating miniplayer, used with the glass navigation bar and miniplayer, gets the same room
                // on its sides as the pages and the capsule. The attribute is only read by the floating miniplayer.
                if (is_9_39_or_greater) {
                    listOf(
                        "res/layout/watch_while_layout.xml",
                        // Inflated instead of the layout above when a server flag defers the player.
                        "res/layout/watch_while_layout_deferred.xml"
                    ).forEach { path ->
                        resourceXml(path).use { layout ->
                            val root = layout.root
                            root.attributeNames.filter { it.endsWith(":floatingMiniPlayerHorizontalMargin") }
                                .forEach { root.setAttribute(it, "@dimen/item_large_spacing") }
                        }
                    }
                }
            }
        }
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.GENERAL.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_music_navigation_bar_screen",
                sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("morphe_music_hide_navigation_bar_home_button"),
                    SwitchPreference("morphe_music_hide_navigation_bar_samples_button"),
                    SwitchPreference("morphe_music_hide_navigation_bar_explore_button"),
                    SwitchPreference("morphe_music_hide_navigation_bar_library_button"),
                    SwitchPreference("morphe_music_hide_navigation_bar_upgrade_button"),
                    SwitchPreference("morphe_music_hide_navigation_bar"),
                    SwitchPreference("morphe_music_hide_navigation_bar_labels"),
                    SwitchPreference("morphe_music_navigation_bar_glass", summary = true),
                    // Height, width, button spacing, opacity and rim brightness, with a preview.
                    NonInteractivePreference(
                        key = "morphe_music_navigation_bar_glass_height",
                        titleKey = "morphe_music_navigation_bar_glass_size_title",
                        summaryKey = "morphe_music_navigation_bar_glass_size_summary",
                        tag = "app.morphe.extension.music.settings.preference.MusicGlassNavigationBarPreference",
                        selectable = true
                    ),
                    NonInteractivePreference(
                        key = "morphe_music_navigation_bar_glass_blur",
                        tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference"
                    ),
                    SwitchPreference("morphe_music_navigation_bar_glass_light", summary = true),
                    SwitchPreference("morphe_music_navigation_bar_glass_album_tint", summary = true),
                    SwitchPreference("morphe_music_navigation_bar_glass_hide_indicator", summary = true),
                )
            )
        )

        PreferenceScreen.addMiniplayerPreferences(
            SwitchPreference("morphe_music_miniplayer_glass", summary = true),
            // Corner radius, artwork padding, rim brightness and opacity, with a preview.
            NonInteractivePreference(
                key = "morphe_music_miniplayer_glass_corner_radius",
                titleKey = "morphe_music_miniplayer_glass_style_title",
                summaryKey = "morphe_music_miniplayer_glass_style_summary",
                tag = "app.morphe.extension.music.settings.preference.MusicGlassMiniplayerPreference",
                selectable = true
            ),
            NonInteractivePreference(
                key = "morphe_music_miniplayer_glass_blur",
                tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference"
            ),
            SwitchPreference("morphe_music_miniplayer_glass_light", summary = true),
            SwitchPreference("morphe_music_miniplayer_glass_album_tint", summary = true),
            SwitchPreference("morphe_music_miniplayer_glass_spinning_artwork", summary = true),
            SwitchPreference("morphe_music_miniplayer_glass_hide_progress_bar", summary = true),
        )

        addSetPlaybackStateHook(
            "$EXTENSION_GLASS_MINIPLAYER_CLASS->onSetPlaybackState(Landroid/media/session/PlaybackState;)V"
        )

        // The time bars of the player and of the miniplayer share an obfuscated class. The time bar
        // of the miniplayer has no id, so the class is marked for the glass miniplayer to find it.
        val timeBarClass = classDefBy(MUSIC_PLAYBACK_CONTROLS_TIME_BAR_CLASS).superclass!!
        mutableClassDefBy(timeBarClass).interfaces.add(EXTENSION_TIME_BAR_INTERFACE)

        TabLayoutTextFingerprint.let {
            it.method.apply {
                // Modify in reverse order to preserve match indices.

                // Hide navigation buttons.
                val pivotTabIndex = it.instructionMatches.last().index
                val pivotTabRegister = getInstruction<FiveRegisterInstruction>(pivotTabIndex).registerC

                addInstructions(
                    pivotTabIndex,
                    """
                        invoke-static { v$pivotTabRegister }, $EXTENSION_CLASS->hideNavigationButton(Landroid/view/View;)V
                        invoke-static { v$pivotTabRegister }, $EXTENSION_GLASS_CLASS->navigationTabCreated(Landroid/view/View;)V
                    """
                )

                // Set navigation enum and hide navigation buttons.
                val enumIndex = it.instructionMatches[7].index
                val enumRegister = getInstruction<OneRegisterInstruction>(enumIndex).registerA

                addInstruction(
                    enumIndex + 1,
                    "invoke-static { v$enumRegister }, $EXTENSION_CLASS->setLastAppNavigationEnum(Ljava/lang/Enum;)V"
                )

                // Hide navigation labels.
                val labelIndex = it.instructionMatches[3].index
                val targetParameter = getInstruction<ReferenceInstruction>(labelIndex).reference
                val targetRegister = getInstruction<OneRegisterInstruction>(labelIndex).registerA

                addInstruction(
                    labelIndex + 1,
                    "invoke-static { v$targetRegister }, $EXTENSION_CLASS->hideNavigationLabel(Landroid/widget/TextView;)V"
                )
            }
        }

        // The glass navigation bar and miniplayer use the floating miniplayer of the app.
        if (is_9_39_or_greater) {
            FloatingMiniplayerFeatureFlagFingerprint.matchAll().forEach {
                it.method.insertLiteralOverride(
                    it.instructionMatches.first().index,
                    "$EXTENSION_GLASS_CLASS->useFloatingMiniplayer(Z)Z"
                )
            }
        }

        // The glass navigation bar leaves the room around the capsule clear, so the page is laid out
        // behind the bar, down to the bottom of the screen.
        WatchWhileLayoutOnMeasureFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.last().index
                val call = getInstruction<FiveRegisterInstruction>(index)
                val contentRegister = call.registerC
                val heightRegister = call.registerE

                addInstructions(
                    index,
                    """
                        invoke-static { v$contentRegister, v$heightRegister }, $EXTENSION_GLASS_CLASS->getContentHeight(Landroid/view/View;I)I
                        move-result v$heightRegister
                    """
                )
            }
        }
    }
}
