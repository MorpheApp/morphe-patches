/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.layout.buttons.action

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.litho.filter.lithoFilterPatch
import app.morphe.patches.music.misc.litho.node.treeNodeElementHookPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.litho.filter.addLithoFilter
import app.morphe.patches.shared.misc.litho.node.hookTreeNodeResult
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

private const val ACTION_BUTTONS_FILTER =
    "Lapp/morphe/extension/music/patches/components/MusicActionButtonsFilter;"

private const val EXTENSION_BUTTON_PROTO_INTERFACE =
    $$"Lapp/morphe/extension/music/patches/components/MusicActionButtonsFilter$ButtonProtoBufferInterface;"

@Suppress("unused")
val hideMusicActionButtonsPatch = bytecodePatch(
    name = "Hide music action buttons",
    description = "Adds options to hide action buttons under the player."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        lithoFilterPatch,
        treeNodeElementHookPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_music_action_buttons_screen",
                preferences = setOf(
                    SwitchPreference("morphe_music_hide_action_bar"),
                    SwitchPreference("morphe_music_hide_like_dislike_button"),
                    SwitchPreference("morphe_music_hide_comments_button"),
                    SwitchPreference("morphe_music_hide_lyrics_button"),
                    SwitchPreference("morphe_music_hide_share_button"),
                    SwitchPreference("morphe_music_hide_save_button"),
                    SwitchPreference("morphe_music_hide_download_button"),
                    SwitchPreference("morphe_music_hide_radio_button")
                )
            )
        )

        addLithoFilter(ACTION_BUTTONS_FILTER)
        hookTreeNodeResult("$ACTION_BUTTONS_FILTER->onLazilyConvertedElementLoaded")

        // Add the ButtonProtoBufferInterface + patch_getButtonProto() delegate on the
        // obfuscated litho message class that owns each button's serialized proto buffer.
        // The extension iterates the reachable object graph of each tree-node entry and
        // dispatches once it lands on an instance of this interface, so we don't need to
        // hard-code obfuscated field / method names.
        ButtonProtoBufferGetterFingerprint.let {
            val getterMethod = it.method
            it.classDef.apply {
                interfaces.add(EXTENSION_BUTTON_PROTO_INTERFACE)
                methods.add(
                    ImmutableMethod(
                        type,
                        "patch_getBuffer",
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
                                invoke-virtual { p0 }, $type->${getterMethod.name}()[B
                                move-result-object v0
                                return-object v0
                            """
                        )
                    }
                )
            }
        }
    }
}
