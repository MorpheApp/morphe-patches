/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4869
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.nsfw

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.cloneParameters
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.numberOfParameterRegistersLogical
import app.morphe.patcher.util.p0Register
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_40_0_or_greater
import app.morphe.patches.reddit.misc.version.is_2026_43_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/PermanentNsfwPatch;"

private const val PREFERENCE_STATE_DISABLED =
    $$"Lcom/reddit/domain/model/AccountPreferences$PreferenceState;->Disabled:Lcom/reddit/domain/model/AccountPreferences$PreferenceState;"

/**
 * Returns early from the method if NSFW content is permanently disabled.
 */
private fun MutableMethod.addPermanentlyDisabledReturn(index: Int, register: Int, returnInstructions: String) {
    addInstructionsWithLabels(
        index,
        """
            invoke-static {}, $EXTENSION_CLASS->isEnabled()Z
            move-result v$register
            if-eqz v$register, :notPermanentlyDisabled
            $returnInstructions
            :notPermanentlyDisabled
            nop
        """
    )
}

/**
 * Returns early from a method that has no free registers.
 */
private fun MutableMethod.addPermanentlyDisabledReturnClonedParameters(returnInstructions: (register: Int) -> String) {
    cloneParameters().apply {
        val register = p0Register
        addPermanentlyDisabledReturn(numberOfParameterRegistersLogical, register, returnInstructions(register))
    }
}

@Suppress("unused")
val permanentNsfwPatch = patch(
    name = "Permanently disable NSFW content",
    description = "Adds an option to permanently disable NSFW content and NSFW search results. " +
            "This patch works with Reddit 2026.40.0 and newer.",
    default = true
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        if (!is_2026_40_0_or_greater) {
            return@execute Logger.getLogger(this::class.java.name).warning(
                "'Permanently disable NSFW content' requires Reddit 2026.40.0+"
            )
        }

        // region Force the native NSFW setting off.

        // Viewing setting of the preference repository, which also has the setting of anonymous browsing.
        NsfwViewingPreferenceFingerprint.method.apply {
            val register = findFreeRegister(0)
            addPermanentlyDisabledReturn(
                0,
                register,
                """
                    const/4 v$register, 0x0
                    return v$register
                """
            )
        }

        // Viewing and search settings of the account model.
        listOf("getOver18", "getSearchIncludeOver18").forEach { name ->
            accountPreferencesFingerprint(name, "Z").method.addPermanentlyDisabledReturnClonedParameters { register ->
                """
                    const/4 v$register, 0x0
                    return v$register
                """
            }
        }

        // Newer age gating uses this setting state instead of getOver18().
        if (is_2026_43_0_or_greater) {
            accountPreferencesFingerprint(
                "getNsfwEnabledPreferenceState",
                $$"Lcom/reddit/domain/model/AccountPreferences$PreferenceState;"
            ).method.addPermanentlyDisabledReturnClonedParameters { register ->
                """
                    sget-object v$register, $PREFERENCE_STATE_DISABLED
                    return-object v$register
                """
            }
        }

        // Safe search setting of anonymous browsing.
        SafeSearchGetValueFingerprint.method.addPermanentlyDisabledReturnClonedParameters { register ->
            """
                const-string v$register, "on"
                return-object v$register
            """
        }

        FilterValuesConstructorFingerprint.method.addInstructions(
            0,
            """
                invoke-static/range { p4 .. p4 }, $EXTENSION_CLASS->enforceSafeSearch(Z)Z
                move-result p4
            """
        )

        // Prevent turning the viewing setting on.
        if (is_2026_43_0_or_greater) {
            SetOver18Fingerprint.instructionMatches.first().getMethodCalled()
        } else {
            AccountPreferencesSetOver18Fingerprint.method
        }.addInstructions(
            0,
            """
                invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->allowNsfw(Z)Z
                move-result p1
            """
        )

        // endregion

        // region Remove the NSFW settings.

        // Viewing and blur settings, used for both accounts and anonymous browsing.
        listOf("label_over18", "label_blur_nsfw_images").forEach { name ->
            stringResourceFingerprint(name, "V").method.apply {
                addPermanentlyDisabledReturn(0, findFreeRegister(0), "return-void")
            }
        }

        // Anonymous browsing profile drawer.
        LeaveIncognitoModeScreenCreateViewFingerprint.method.apply {
            findInstructionIndicesReversedOrThrow(Opcode.RETURN_OBJECT).forEach { index ->
                val register = getInstruction<OneRegisterInstruction>(index).registerA
                addInstruction(
                    index,
                    "invoke-static/range { v$register .. v$register }, " +
                            "$EXTENSION_CLASS->hideAnonymousControls(Landroid/view/View;)V"
                )
            }
        }

        // Show or hide NSFW search results header.
        SearchNsfwHeaderFingerprint.method.addPermanentlyDisabledReturnClonedParameters { "return-void" }

        // endregion

        // region Change the NSFW warnings to say NSFW content is permanently disabled.

        mapOf(
            "nsfw_title" to "getWarningTitle",
            "nsfw_dialog_title" to "getWarningTitle",
            "nsfw_description" to "getWarningMessage",
            "nsfw_dialog_message" to "getWarningMessage",
            "nsfw_dialog_over18_submessage" to "getWarningMessage",
            "nsfw_dialog_under18_submessage" to "getWarningMessage",
            "nsfw_no_thanks" to "getWarningDismissLabel"
        ).forEach { (name, methodName) ->
            stringResourceFingerprint(name).matchAll(1..4).forEach { match ->
                match.method.apply {
                    val index = match.instructionMatches.first().index
                    val register = getInstruction<OneRegisterInstruction>(index).registerA

                    addInstructions(
                        index + 1,
                        """
                            invoke-static/range { v$register .. v$register }, $EXTENSION_CLASS->$methodName(I)I
                            move-result v$register
                        """
                    )
                }
            }
        }

        // Fit the bottom sheet to its content, which is shorter without the button to turn on NSFW content.
        NsfwBottomSheetContentHeightFingerprint.let {
            it.method.apply {
                val index = it.instructionMatches.last().index
                val register = findFreeRegister(index)

                addInstructionsWithLabels(
                    index,
                    """
                        invoke-static {}, $EXTENSION_CLASS->isEnabled()Z
                        move-result v$register
                        if-nez v$register, :wrapContent
                    """,
                    // Skip the height call and its move-result.
                    ExternalLabel("wrapContent", getInstruction(index + 2))
                )
            }
        }

        // Remove the button that turns on NSFW content.
        val optInButtons = NsfwBottomSheetButtonsFingerprint.let { listOf(it.method to it.instructionMatches[1].index) } +
                NsfwDialogFingerprint.matchAll(2..2).map { it.method to it.instructionMatches[1].index }
        optInButtons.forEach { (method, index) ->
            method.apply {
                val register = findFreeRegister(index)

                addInstructionsWithLabels(
                    index,
                    """
                        invoke-static {}, $EXTENSION_CLASS->isEnabled()Z
                        move-result v$register
                        if-nez v$register, :skipOptInButton
                    """,
                    ExternalLabel("skipOptInButton", getInstruction(index + 1))
                )
            }
        }

        // endregion

        // region Dismiss search banners that ask to change the NSFW setting or to verify the age.
        // Banners are server driven, so they are identified by their button action and not their text.

        val bannerBehaviorsField = SearchBannerViewStateToStringFingerprint.instructionMatches[2].getFieldAccessed()
        val bannerDismissedField = SearchBannerViewStateToStringFingerprint.instructionMatches[4].getFieldAccessed()
        val bannerActionField = SearchBannerBehaviorsToStringFingerprint.instructionMatches.last().getFieldAccessed()
        val adjustNsfwSettingsType = SearchAdjustNsfwSettingsBehaviorToStringFingerprint.originalClassDef.type
        val verifyAgeType = SearchAgeVerificationFlowBehaviorToStringFingerprint.originalClassDef.type

        SearchBannerViewStateConstructorFingerprint.let {
            it.method.apply {
                // All parameters are saved to fields at this point, so p1 and p2 are free.
                addInstructionsWithLabels(
                    it.instructionMatches.last().index,
                    """
                        invoke-static {}, $EXTENSION_CLASS->isEnabled()Z
                        move-result p1
                        if-eqz p1, :keepBanner
                        iget-object p1, p0, $bannerBehaviorsField
                        if-eqz p1, :keepBanner
                        iget-object p1, p1, $bannerActionField
                        instance-of p2, p1, $adjustNsfwSettingsType
                        if-nez p2, :dismissBanner
                        instance-of p2, p1, $verifyAgeType
                        if-eqz p2, :keepBanner
                        :dismissBanner
                        const/4 p1, 0x1
                        iput-boolean p1, p0, $bannerDismissedField
                        :keepBanner
                        nop
                    """
                )
            }
        }

        // endregion

        // region Remove search dropdowns that only have safe search options.
        // A null UI model is ignored by the search screen. Other filters are not changed.

        val dropdownType = SearchDropdownToStringFingerprint.originalClassDef.type
        val dropdownOptionsField = SearchDropdownToStringFingerprint.instructionMatches.last().getFieldAccessed()

        SearchFilterUiModelFingerprint.let {
            // Returns the safe search value of a dropdown option, or null.
            val safeSearchParser = it.method.getInstruction<ReferenceInstruction>(
                it.instructionMatches.first().index
            ).reference as MethodReference
            val optionType = safeSearchParser.parameterTypes.single()

            val isSafeSearchDropdownMethod = ImmutableMethod(
                it.originalClassDef.type,
                "patch_isSafeSearchDropdown",
                listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, null)),
                "Z",
                AccessFlags.PRIVATE.value or AccessFlags.STATIC.value,
                null,
                null,
                MutableMethodImplementation(3)
            ).toMutable().apply {
                addInstructionsWithLabels(
                    0,
                    """
                        instance-of v0, p0, $dropdownType
                        if-eqz v0, :notSafeSearch
                        check-cast p0, $dropdownType
                        iget-object v0, p0, $dropdownOptionsField
                        invoke-interface { v0 }, Ljava/util/List;->isEmpty()Z
                        move-result v1
                        if-nez v1, :notSafeSearch
                        invoke-interface { v0 }, Ljava/util/List;->iterator()Ljava/util/Iterator;
                        move-result-object v0
                        :nextOption
                        invoke-interface { v0 }, Ljava/util/Iterator;->hasNext()Z
                        move-result v1
                        if-eqz v1, :safeSearch
                        invoke-interface { v0 }, Ljava/util/Iterator;->next()Ljava/lang/Object;
                        move-result-object v1
                        check-cast v1, $optionType
                        invoke-static { v1 }, $safeSearchParser
                        move-result-object v1
                        if-eqz v1, :notSafeSearch
                        goto :nextOption
                        :safeSearch
                        const/4 v0, 0x1
                        return v0
                        :notSafeSearch
                        const/4 v0, 0x0
                        return v0
                    """
                )
            }
            it.classDef.methods.add(isSafeSearchDropdownMethod)

            it.method.apply {
                val register = findFreeRegister(0)

                addInstructionsWithLabels(
                    0,
                    """
                        invoke-static {}, $EXTENSION_CLASS->isEnabled()Z
                        move-result v$register
                        if-eqz v$register, :showDropdown
                        invoke-static/range { p1 .. p1 }, $isSafeSearchDropdownMethod
                        move-result v$register
                        if-eqz v$register, :showDropdown
                        const/4 v$register, 0x0
                        return-object v$register
                        :showDropdown
                        nop
                    """
                )
            }
        }

        // endregion

        // region Hide NSFW communities from recently visited communities.

        // Do not save NSFW communities as recently visited.
        SetUpSubredditFingerprint.instructionMatches.first().getMethodCalled().apply {
            val register = findFreeRegister(0)

            addInstructionsWithLabels(
                0,
                """
                    invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->skipRecentSubreddit(Lcom/reddit/domain/model/Subreddit;)Z
                    move-result v$register
                    if-eqz v$register, :saveRecentSubreddit
                    const/4 v$register, 0x0
                    return-object v$register
                    :saveRecentSubreddit
                    nop
                """
            )
        }

        // Hide NSFW communities that were saved before NSFW content was disabled,
        // in the community drawer and the recently visited screen.
        listOf(
            $$$"Lcom/reddit/screens/drawer/community/CommunityDrawerPresenter$refreshRecentlyVisited$1$1$invokeSuspend$$inlined$map$1$2$1;",
            $$$"Lcom/reddit/screens/drawer/community/CommunityDrawerRecentlyVisitedDelegate$loadRecentlyVisited$$inlined$map$1$2$1;"
        ).forEach { continuationClass ->
            recentlyVisitedFingerprint(continuationClass).let {
                it.method.apply {
                    val index = it.instructionMatches.last().index
                    val register = getInstruction<FiveRegisterInstruction>(index).registerD

                    addInstructions(
                        index,
                        """
                            invoke-static/range { v$register .. v$register }, $EXTENSION_CLASS->filterRecentSubreddits(Ljava/util/List;)Ljava/util/List;
                            move-result-object v$register
                        """
                    )
                }
            }
        }

        // endregion

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
