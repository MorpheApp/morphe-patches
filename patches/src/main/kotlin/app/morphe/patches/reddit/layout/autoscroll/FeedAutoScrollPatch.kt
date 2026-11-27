/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4958
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.autoscroll

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patches.reddit.misc.feed.feedHookPatch
import app.morphe.patches.reddit.misc.feed.gridFeedStateType
import app.morphe.patches.reddit.misc.feed.listFeedStateType
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_37_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.patches.reddit.shared.NavBarStateChangeFingerprint
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/FeedAutoScrollPatch;"
private const val SCROLLABLE_STATE_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/FeedAutoScrollPatch$ScrollableState;"

@Suppress("unused")
val feedAutoScrollPatch = patch(
    name = "Feed auto-scroll",
    description = "Adds an option to slowly scroll the feed on screen automatically."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch, feedHookPatch)

    execute {
        // Scroll states of the list and grid feeds.
        listOf(listFeedStateType, gridFeedStateType).forEach { stateType ->
            val dispatchRawDelta = Fingerprint(
                definingClass = stateType,
                returnType = "F",
                parameters = listOf("F")
            ).method

            mutableClassDefBy(stateType).addPatchInterface(
                SCROLLABLE_STATE_INTERFACE,
                "patch_dispatchRawDelta",
                dispatchRawDelta
            )
        }

        // region Button.

        MainActivityOnResumeFingerprint.method.addInstruction(
            0,
            "invoke-static { p0 }, $EXTENSION_CLASS->onMainActivityResumed(Landroid/app/Activity;)V"
        )

        MainActivityOnPauseFingerprint.method.addInstruction(
            0,
            "invoke-static { }, $EXTENSION_CLASS->onMainActivityPaused()V"
        )

        NavBarStateChangeFingerprint.method.addInstruction(
            0,
            "invoke-static { p1 }, $EXTENSION_CLASS->onNavBarStateChange(F)V"
        )

        PostDetailPagerInflateFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches[2].index
                val register = getInstruction<OneRegisterInstruction>(index).registerA

                addInstruction(
                    index + 1,
                    "invoke-static/range { v$register .. v$register }, " +
                            "$EXTENSION_CLASS->onPostDetailPagerViewCreated(Landroid/view/View;)V"
                )
            }
        }

        val (screenAttachFingerprint, screenDetachFingerprint) = if (is_2026_37_0_or_greater) {
            ScreenAttachFingerprint to ScreenDetachFingerprint
        } else {
            ScreenAttachLegacyFingerprint to ScreenDetachLegacyFingerprint
        }

        screenAttachFingerprint.method.addInstruction(
            0,
            "invoke-static { p0 }, $EXTENSION_CLASS->onScreenAttached(Ljava/lang/Object;)V"
        )

        screenDetachFingerprint.method.addInstruction(
            0,
            "invoke-static { p0 }, $EXTENSION_CLASS->onScreenDetached(Ljava/lang/Object;)V"
        )

        // endregion

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
