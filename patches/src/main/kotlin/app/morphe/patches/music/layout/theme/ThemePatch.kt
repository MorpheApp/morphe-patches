/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2524
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.layout.theme

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.playservice.is_9_30_or_greater
import app.morphe.patches.music.misc.playservice.versionCheckPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.layout.theme.THEME_COLOR_EXTENSION_CLASS
import app.morphe.patches.shared.layout.theme.THEME_DEFAULT_COLOR_NAMES_DARK
import app.morphe.patches.shared.layout.theme.baseThemePatch
import app.morphe.patches.music.shared.MusicActivityOnCreateFingerprint
import app.morphe.patches.shared.layout.theme.baseThemeResourcePatch
import app.morphe.patches.shared.layout.theme.usePatchedThemeColor
import app.morphe.patches.shared.misc.settings.preference.InputType
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.TextPreference
import app.morphe.patches.shared.misc.settings.preference.noTitleUnsortedPreferenceCategory
import app.morphe.util.doRecursively
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import org.w3c.dom.Element

private const val EXTENSION_CLASS = "Lapp/morphe/extension/music/patches/theme/ThemePatch;"

private const val HEADER_FADE_LAYOUT_CLASS =
    "app.morphe.extension.music.patches.theme.HeaderFadeLayout"

private const val DETAIL_PAGE_HEADER_LAYOUT = "res/layout/music_element_header.xml"

private val musicColorNamesDark = {
    THEME_DEFAULT_COLOR_NAMES_DARK + setOf(
        "yt_black_pure",
        "yt_black_pure_opacity80",
        "yt_ref_color_constants_default_baseline_black_black1",
        "ytm_color_grey_12",
        "material_grey_800"
    )
}

/**
 * The header of a playlist, album or artist page ends in a translucent black, which is only
 * invisible while the app background is pure black.
 * https://github.com/MorpheApp/morphe-patches/issues/200
 */
private val headerFadeResourcePatch = resourcePatch(
    description = "Fades the header of a detail page into the app background."
) {
    execute {
        if (!get(DETAIL_PAGE_HEADER_LAYOUT).exists()) {
            return@execute
        }

        document(DETAIL_PAGE_HEADER_LAYOUT).use { document ->
            // Replaced after the walk, which would otherwise lose the node it is on.
            val containers = mutableListOf<Element>()

            document.doRecursively loop@{ node ->
                if (node !is Element) return@loop

                val idAttribute = node.getAttributeNode("android:id") ?: return@loop
                if (idAttribute.textContent == "@id/elements_container") {
                    containers += node
                }
            }

            containers.forEach { container ->
                val fadeLayout = container.ownerDocument.createElement(HEADER_FADE_LAYOUT_CLASS)

                val attributes = container.attributes
                for (index in 0 until attributes.length) {
                    val attribute = attributes.item(index)
                    fadeLayout.setAttribute(attribute.nodeName, attribute.nodeValue)
                }

                container.parentNode.replaceChild(fadeLayout, container)
            }
        }
    }
}

@Suppress("unused")
val themePatch = baseThemePatch(
    extensionClassDescriptor = EXTENSION_CLASS,
    useModernLithoColorHook = {
        is_9_30_or_greater
    },
    block = {
        dependsOn(
            sharedExtensionPatch,
            settingsPatch,
            resourceMappingPatch,
            versionCheckPatch,
            baseThemeResourcePatch(
                colorNamesDark = musicColorNamesDark,
                // The theme of the launcher activity, which the system draws the splash with.
                splashScreenThemeParent = "@style/Theme.YouTubeMusic.Home"
            ),
            headerFadeResourcePatch
        )

        compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)
    },

    executeBlock = {
        // The splash screen is drawn by the system with the theme of the launcher activity, so
        // the activity is handed over as soon as it exists. A patched background color is
        // already a part of that theme, and no theme is generated to hand over.
        if (!usePatchedThemeColor) {
            MusicActivityOnCreateFingerprint.method.addInstruction(
                0,
                // The register of 'this' can be above v15, so the range format is needed.
                "invoke-static/range { p0 .. p0 }, $THEME_COLOR_EXTENSION_CLASS" +
                        "->setSplashScreenTheme(Landroid/app/Activity;)V"
            )
        }

        // Color of the new content count of the top bar, which is red in the app and does
        // not go with a Material You background.
        TopBarNewContentCountFingerprint.let {
            it.method.apply {
                // Not the last match, which is the call that inflates the stub.
                val checkCastIndex = it.instructionMatches[2].index
                val stubRegister = getInstruction<OneRegisterInstruction>(checkCastIndex).registerA

                addInstruction(
                    checkCastIndex + 1,
                    "invoke-static { v$stubRegister }, $THEME_COLOR_EXTENSION_CLASS" +
                            "->onNewContentIndicator(Landroid/view/ViewStub;)V"
                )
            }
        }

        // The same button shows a dot when there is no count, and the dot is a view of the
        // layout instead of a stub, so it is colored as soon as the top bar creates it.
        TopBarNewContentDotFingerprint.let {
            it.method.apply {
                val moveResultIndex = it.instructionMatches[2].index
                val dotRegister = getInstruction<OneRegisterInstruction>(moveResultIndex).registerA

                addInstruction(
                    moveResultIndex + 1,
                    "invoke-static { v$dotRegister }, $THEME_COLOR_EXTENSION_CLASS" +
                            "->onNewContentIndicator(Landroid/view/View;)V"
                )
            }
        }

        // A patched background color cannot be changed, so there is nothing to select.
        if (!usePatchedThemeColor) {
            PreferenceScreen.GENERAL.addPreferences(
                noTitleUnsortedPreferenceCategory(
                    ListPreference(
                        "morphe_theme_color_dark",
                        tag = "app.morphe.extension.shared.theme.ThemeColorListPreference"
                    ),
                    TextPreference(
                        "morphe_theme_color_dark_custom",
                        tag = "app.morphe.extension.shared.settings.preference.ColorPickerPreference",
                        inputType = InputType.TEXT_CAP_CHARACTERS
                    )
                )
            )
        }
    }
)
