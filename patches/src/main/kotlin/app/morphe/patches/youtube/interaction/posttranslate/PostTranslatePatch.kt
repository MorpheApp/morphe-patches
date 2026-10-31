/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4163
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.posttranslate

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
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
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/PostTranslatePatch;"

private const val EXTENSION_COMPONENT_HOST_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/PostTranslatePatch$ComponentHostInterface;"

private const val EXTENSION_TEXT_DRAWABLE_INTERFACE =
    $$"Lapp/morphe/extension/youtube/patches/PostTranslatePatch$TextDrawableInterface;"

private fun MutableClass.addPatchMethod(
    name: String,
    parameters: List<String>,
    returnType: String,
    registers: Int,
    block: MutableMethod.() -> Unit
) {
    methods.add(
        ImmutableMethod(
            type,
            name,
            parameters.map { ImmutableMethodParameter(it, null, null) },
            returnType,
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            null,
            null,
            MutableMethodImplementation(registers),
        ).toMutable().apply(block)
    )
}

@Suppress("unused")
val postTranslatePatch = bytecodePatch(
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
                interfaces.add(EXTENSION_COMPONENT_HOST_INTERFACE)
                addPatchMethod("patch_getMountedContents", listOf(), "Ljava/util/List;", 6) {
                    addInstructionsWithLabels(
                        0,
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
        }

        // The layout and the text of the text drawable are given the translation.
        val textField = LithoTextDrawableGetTextItemsFingerprint.instructionMatches.first().fieldReference
        LithoTextDrawableDrawFingerprint.let {
            val layoutField = it.instructionMatches.first().fieldReference

            it.classDef.apply {
                interfaces.add(EXTENSION_TEXT_DRAWABLE_INTERFACE)
                arrayOf(
                    Triple("Layout", "Landroid/text/Layout;", layoutField),
                    Triple("Text", "Ljava/lang/CharSequence;", textField)
                ).forEach { (name, fieldType, field) ->
                    addPatchMethod("patch_get$name", listOf(), fieldType, 2) {
                        addInstructions(
                            0,
                            """
                                iget-object v0, p0, $field
                                return-object v0
                            """
                        )
                    }
                    addPatchMethod("patch_set$name", listOf(fieldType), "V", 2) {
                        addInstructions(
                            0,
                            """
                                iput-object p1, p0, $field
                                return-void
                            """
                        )
                    }
                }
            }
        }

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
