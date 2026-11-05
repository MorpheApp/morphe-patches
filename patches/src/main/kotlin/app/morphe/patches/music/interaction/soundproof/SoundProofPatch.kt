/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.interaction.soundproof

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.MediaSessionSetMetadataFingerprint
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceCategory
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.settings.preference.screenInfoPreferenceCategory
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources
import app.morphe.util.doRecursively
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import org.w3c.dom.Element
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/soundproof/SoundProofPatch;"

private const val SEEK_BAR_PREFERENCE =
    "app.morphe.extension.shared.settings.preference.SeekBarPreference"

private const val BUTTON_VIEW_TAG =
    "app.morphe.extension.music.patches.soundproof.ui.SoundProofButton"

private val soundProofButtonResourcePatch = resourcePatch(
    description = "Adds the SoundProof button to the top bar of the player."
) {
    execute {
        copyResources(
            "soundproof",
            ResourceGroup(
                "drawable",
                "morphe_soundproof_shield.xml",
                "morphe_soundproof_shield_alert.xml",
                "morphe_soundproof_shield_check.xml",
                "morphe_soundproof_shield_question.xml"
            )
        )

        document("res/layout/watch_while_layout.xml").use { document ->
            val elementsById = mutableMapOf<String, Element>()
            document.doRecursively { node ->
                if (node is Element && node.hasAttribute("android:id")) {
                    elementsById[node.getAttribute("android:id")] = node
                }
            }

            // Top bar: collapse, spacer, centered audio and video switch, cast, overflow menu.
            val anchor = elementsById["@id/cast_button_container"]
                ?: elementsById["@id/contextual_menu"]
                ?: throw PatchException("Could not find the player top bar")
            val topBar = anchor.parentNode as Element

            val button = document.createElement(BUTTON_VIEW_TAG).apply {
                setAttribute("android:id", "@+id/morphe_soundproof_button")
                setAttribute("android:layout_width", "@dimen/remix_generic_button_size")
                setAttribute("android:layout_height", "@dimen/remix_generic_button_size")
                setAttribute("android:scaleType", "center")
                setAttribute("style", "@style/MusicPlayerButton")
            }
            topBar.insertBefore(button, anchor)

            // The switch is centered because the left side holds a spacer as wide as a button.
            // A second spacer keeps it centered once the right side has one more button.
            val existingSpacer = topBar.childElements().firstOrNull { it.tagName == "Space" }
            if (existingSpacer == null) {
                Logger.getLogger(this::class.java.name)
                    .warning("Player top bar has no spacer, the audio and video switch is not recentered")
            } else {
                val spacer = document.createElement("Space").apply {
                    setAttribute("android:id", "@+id/morphe_soundproof_spacer")
                    setAttribute("android:layout_width", "@dimen/remix_generic_button_size")
                    setAttribute("android:layout_height", "@dimen/remix_generic_button_size")
                }
                topBar.insertBefore(spacer, existingSpacer.nextSibling)
            }
        }
    }
}

private fun Element.childElements(): List<Element> =
    (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()

@Suppress("unused")
val soundProofPatch = bytecodePatch(
    name = "SoundProof",
    description = "Adds an option to skip tracks of artists reported as AI in the SoundProof community database.",
    // Off by default: it downloads a third-party database and skips tracks on its own.
    default = false
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        soundProofButtonResourcePatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_music_soundproof_screen",
                sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("morphe_music_soundproof_show_button", summary = true),
                    SwitchPreference("morphe_music_soundproof_skip_ai_artists", summary = true),
                    SwitchPreference("morphe_music_soundproof_toast_on_skip", summary = true),
                    PreferenceCategory(
                        key = "morphe_music_soundproof_threshold",
                        sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                        preferences = setOf(
                            NonInteractivePreference(
                                key = "morphe_music_soundproof_minimum_ai_percent",
                                tag = SEEK_BAR_PREFERENCE,
                                selectable = true
                            ),
                            NonInteractivePreference(
                                key = "morphe_music_soundproof_minimum_votes",
                                tag = SEEK_BAR_PREFERENCE,
                                selectable = true
                            )
                        )
                    ),
                    screenInfoPreferenceCategory(
                        key = "morphe_music_soundproof_about_category",
                        preferences = setOf(
                            NonInteractivePreference(
                                key = "morphe_music_soundproof_about",
                                titleKey = null,
                                summaryKey = "morphe_music_soundproof_about_summary"
                            )
                        )
                    )
                )
            )
        )

        // Both the session and the metadata are needed: the artist is read from the metadata,
        // and the skip goes through the session's own transport controls.
        MediaSessionSetMetadataFingerprint.apply {
            // Other patches insert before the same call, which shifts its index.
            clearMatch()

            method.apply {
                val setMetadataIndex = instructionMatches.last().index
                val setMetadataCall = getInstruction<FiveRegisterInstruction>(setMetadataIndex)
                addInstruction(
                    setMetadataIndex,
                    "invoke-static { v${setMetadataCall.registerC}, v${setMetadataCall.registerD} }, " +
                        "$EXTENSION_CLASS->onSetMetadata(Landroid/media/session/MediaSession;Landroid/media/MediaMetadata;)V"
                )
            }
        }
    }
}
