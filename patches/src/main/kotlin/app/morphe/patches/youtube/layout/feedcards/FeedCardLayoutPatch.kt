/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3768
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.feedcards

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.shared.layout.theme.lithoColorHookPatch
import app.morphe.patches.shared.layout.theme.lithoDrawableColorHook
import app.morphe.patches.shared.misc.proto.hookElement
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playservice.is_21_30_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.proto.elementProtoParserHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/FeedCardLayoutPatch;"

private const val RECYCLER_VIEW_CLASS = "Landroid/support/v7/widget/RecyclerView;"

@Suppress("unused")
val feedCardLayoutPatch = bytecodePatch(
    name = "Feed card layout",
    description = "Adds an option to show or hide the card layout of feed videos, which YouTube sends as an A/B test."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        elementProtoParserHookPatch,
        versionCheckPatch,
        lithoColorHookPatch { is_21_30_or_greater },
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.FEED.addPreferences(
            SwitchPreference("morphe_feed_card_layout")
        )

        // The layout is chosen by the experiments that the server sends in the context of every element.
        hookElement("$EXTENSION_CLASS->setFeedCardLayout")

        // The theme patch changes the background of the cards to the background color of the feed.
        lithoDrawableColorHook("$EXTENSION_CLASS->getCardColor(Landroid/graphics/drawable/Drawable;I)I")

        // The cards have rounded corners, which are not part of the element.
        mutableClassDefBy(RECYCLER_VIEW_CLASS).methods.add(
            ImmutableMethod(
                RECYCLER_VIEW_CLASS,
                "onViewAdded",
                listOf(ImmutableMethodParameter("Landroid/view/View;", null, null)),
                "V",
                AccessFlags.PUBLIC.value,
                null,
                null,
                MutableMethodImplementation(2),
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        invoke-super { p0, p1 }, Landroid/view/ViewGroup;->onViewAdded(Landroid/view/View;)V
                        invoke-static { p0, p1 }, $EXTENSION_CLASS->onChildAdded(${RECYCLER_VIEW_CLASS}Landroid/view/View;)V
                        return-void
                    """
                )
            }
        )
    }
}
