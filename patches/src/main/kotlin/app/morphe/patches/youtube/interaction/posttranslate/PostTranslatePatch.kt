/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4163
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.posttranslate

import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.shared.misc.proto.hookElement
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.textcomponent.hookLithoSpannableString
import app.morphe.patches.shared.misc.textcomponent.lithoSpannableStringPatch
import app.morphe.patches.youtube.layout.flyout.flyoutPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.feedcards.feedVideoCardsHookPatch
import app.morphe.patches.youtube.misc.proto.elementProtoParserHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/PostTranslatePatch;"

private const val EXTENSION_COMPONENT_HOST_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/PostTranslatePatch$ComponentHostInterface;"

private const val EXTENSION_TEXT_DRAWABLE_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/PostTranslatePatch$TextDrawableInterface;"

val postTranslatePatch = patch(
    name = "Translate posts",
    description = "Adds a menu item to translate the text of posts.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        addResourcesPatch,
        elementProtoParserHookPatch,
        lithoSpannableStringPatch,
        // Adds the item to the menu of the post.
        flyoutPatch,
        // Shows the translation again when the post is laid out again.
        feedVideoCardsHookPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.FEED.addPreferences(
            SwitchPreference("morphe_post_translate", summary = true),
            ListPreference("morphe_post_translate_language"),
        )

        // The element of a post includes its whole text, while a long text is shown cut off.
        hookElement("$EXTENSION_CLASS->onElementLoaded")

        // Texts that Litho creates again are replaced with their translation.
        hookLithoSpannableString(EXTENSION_CLASS)

        // The translation is shown by the text drawables of the post, which are the mounted
        // contents of the hosts, so the contents are read the same way the host reads their texts.
        ComponentHostGetTextContentFingerprint.let {
            val matches = it.instructionMatches
            val ensureMountItems = matches[0].methodReference
            val mountItemsField = matches[1].fieldReference
            val sizeMethod = matches[2].methodReference
            val valueAtMethod = matches[3].methodReference
            val contentField = matches[4].fieldReference

            it.classDef.apply {
                addPatchInterface(
                    EXTENSION_COMPONENT_HOST_INTERFACE,
                    "patch_getMountedContents",
                    """
                        invoke-virtual { p0 }, $ensureMountItems
                        iget-object v0, p0, $mountItemsField
                        invoke-virtual { v0 }, $sizeMethod
                        move-result v1
                        new-instance v2, Ljava/util/ArrayList;
                        invoke-direct { v2, v1 }, Ljava/util/ArrayList;-><init>(I)V
                        const/4 v3, 0x0
                        :loop
                        if-ge v3, v1, :done
                        invoke-virtual { v0, v3 }, $valueAtMethod
                        move-result-object v4
                        check-cast v4, ${contentField.definingClass}
                        iget-object v4, v4, $contentField
                        invoke-interface { v2, v4 }, Ljava/util/List;->add(Ljava/lang/Object;)Z
                        add-int/lit8 v3, v3, 0x1
                        goto :loop
                        :done
                        return-object v2
                    """
                )
            }
        }

        // The layout and the text of the text drawable are given the translation.
        val textField = LithoTextDrawableGetTextItemsFingerprint.instructionMatches.first().fieldReference
        LithoTextDrawableDrawFingerprint.let {
            val layoutField = it.instructionMatches.first().fieldReference

            it.classDef.apply {
                mapOf(
                    "Layout" to layoutField,
                    "Text" to textField
                ).forEach { (name, field) ->
                    addPatchInterface(EXTENSION_TEXT_DRAWABLE_INTERFACE, "patch_get$name", field)
                    addPatchInterface(EXTENSION_TEXT_DRAWABLE_INTERFACE, "patch_set$name", field)
                }
            }
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
