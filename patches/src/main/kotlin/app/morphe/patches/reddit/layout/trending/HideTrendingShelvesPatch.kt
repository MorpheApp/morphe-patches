/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.trending

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_11_0_or_greater
import app.morphe.patches.reddit.misc.version.is_2026_16_0_or_greater
import app.morphe.patches.reddit.misc.version.is_2026_21_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.findFreeRegister
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/HideTrendingShelvesPatch;"
private const val EXTENSION_TRENDING_INTERFACE =
    $$"Lapp/morphe/extension/reddit/patches/HideTrendingShelvesPatch$TrendingInterface;"

@Suppress("unused")
val hideTrendingShelvesPatch = bytecodePatch(
    name = "Hide Trending shelves",
    description = "Adds an option to hide the Trending shelves from feed and search suggestions."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {

        // region patch for set content languages.

        (if (is_2026_16_0_or_greater) LocaleLanguageManagerConstructorFingerprint
        else LocaleLanguageManagerConstructorLegacyFingerprint).let {
            val languageMethod = LocaleLanguageManagerContentLanguagesFingerprint.match(it.classDef).method

            it.method.apply {
                val index = it.instructionMatches.last().index
                val free = findFreeRegister(index)

                addInstructions(
                    index,
                    """
                        invoke-virtual/range { p0 .. p0 }, $languageMethod
                        move-result-object v$free
                        invoke-static { v$free }, $EXTENSION_CLASS->setContentLanguages(Ljava/util/List;)V
                    """
                )
            }
        }

        // endregion

        // Implement trending interface.

        val stateParamType = SearchSectionHeaderFingerprint.method.parameters.first().type
        val isLegacy = stateParamType == "Ljava/lang/String;"

        val targetMethod = if (isLegacy) "hideTrendingHeaderLegacy" else "hideTrendingHeader"
        val targetParam = if (isLegacy) "Ljava/lang/String;" else EXTENSION_TRENDING_INTERFACE

        if (!isLegacy) {
            val stateClassDef = mutableClassDefBy(stateParamType)

            stateClassDef.apply {
                interfaces.add(EXTENSION_TRENDING_INTERFACE)

                val stringField = fields.first { it.type == "Ljava/lang/String;" }

                methods.add(
                    ImmutableMethod(
                        type,
                        "patch_getTrendingLabel",
                        emptyList(),
                        "Ljava/lang/String;",
                        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                        null,
                        null,
                        MutableMethodImplementation(1),
                    ).toMutable().apply {
                        addInstructions(
                            0,
                            """
                                iget-object v0, p0, $type->${stringField.name}:Ljava/lang/String;
                                return-object v0
                            """
                        )
                    }
                )
            }
        }

        // endregion

        // region patch for hide trending header.

        SearchSectionHeaderFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->$targetMethod($targetParam)Z
                move-result v0
                if-eqz v0, :ignore
                return-void
                :ignore
                nop
            """
        )

        // endregion

        // region patch for hide trending contents.

        fun Fingerprint.applyHideTrending() {
            method.addInstructionsWithLabels(
                0,
                """
                    invoke-static { }, $EXTENSION_CLASS->hideTrendingShelf()Z
                    move-result v0
                    if-eqz v0, :ignore
                    return-void
                    :ignore
                    nop
                """
            )
        }

        TrendingItemFingerprint.applyHideTrending()

        if (!is_2026_11_0_or_greater) {
            // Legacy seems to be removed in 2026.11.0+
            TrendingItemLegacyFingerprint.applyHideTrending()
        }

        TypeaheadSuggestionItemFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $EXTENSION_CLASS->hideTrendingCommunitiesShelf()Z
                move-result v0
                if-eqz v0, :ignore
                return-void
                :ignore
                nop
            """
        )

        if (is_2026_21_0_or_greater) {
            TrendingFeedUnitSectionFingerprint.applyHideTrending()
            TrendingFeedUnitDismissedSectionFingerprint.applyHideTrending()
        }

        // endregion

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
