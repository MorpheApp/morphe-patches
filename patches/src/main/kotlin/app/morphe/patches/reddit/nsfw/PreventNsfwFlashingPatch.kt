/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4869
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.nsfw

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_40_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

internal const val PREVENT_NSFW_FLASHING_EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/PreventNsfwFlashingPatch;"

/**
 * Prevents unintended NSFW content reveals when NSFW viewing is turned off.
 */
internal val preventNsfwFlashingPatch = patch {
    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        if (!is_2026_40_0_or_greater) return@execute

        val nsfwViewingPreference = NsfwViewingPreferenceFingerprint.originalMethod
        // The preference repository interface, implemented by the class of the getter.
        val preferenceRepositoryType = PostLoadedNsfwIncognitoVerifyEventHandlerConstructorFingerprint
            .instructionMatches.first().getFieldAccessed().type

        // Feed images can open the viewer without the post going through the NSFW warning.
        // The handlers do not have a preference repository, so get it from the active session.
        val getUserComponent = ActiveUserComponentFingerprint.instructionMatches.first().getMethodCalled()
        val getPreferences = userComponentPreferencesFingerprint(
            getUserComponent.returnType,
            preferenceRepositoryType
        ).originalMethod
        listOf("OnClickImageEventHandler", "OnClickGalleryImageEventHandler").forEach { handler ->
            val fingerprint = feedImageClickFingerprint(handler)
            val (linkField, unitField) = fingerprint.instructionMatches.map { it.getFieldAccessed() }
            fingerprint.method.apply {
                val register = findFreeRegister(0)
                addInstructionsWithLabels(
                    0,
                    """
                        move-object/from16 v$register, p0
                        iget-object v$register, v$register, $linkField
                        if-eqz v$register, :openImage
                        invoke-virtual { v$register }, Lcom/reddit/domain/model/Link;->getOver18()Z
                        move-result v$register
                        if-eqz v$register, :openImage
                        invoke-static {}, $getUserComponent
                        move-result-object v$register
                        check-cast v$register, ${getPreferences.definingClass}
                        invoke-virtual { v$register }, $getPreferences
                        move-result-object v$register
                        check-cast v$register, ${nsfwViewingPreference.definingClass}
                        invoke-virtual { v$register }, $nsfwViewingPreference
                        move-result v$register
                        if-nez v$register, :openImage
                        sget-object v$register, $unitField
                        return-object v$register
                        :openImage
                        nop
                    """
                )
            }
        }

        // region Show the empty post view of NSFW posts until the NSFW warning is shown.

        val mapperField = PostDetailStateProducerFingerprint.instructionMatches.last().getFieldAccessed()
        val mapperPreferencesField = constructorFieldFingerprint(mapperField.type, preferenceRepositoryType)
            .instructionMatches.first().getFieldAccessed()
        val producerField = constructorFieldFingerprint(POST_DETAIL_VIEW_MODEL_CLASS, mapperField.definingClass)
            .instructionMatches.first().getFieldAccessed()

        val shouldHidePostMethod = ImmutableMethod(
            POST_DETAIL_VIEW_MODEL_CLASS,
            "patch_shouldHideNsfw",
            listOf(ImmutableMethodParameter("Lcom/reddit/domain/model/Link;", null, null)),
            "Z",
            AccessFlags.PRIVATE.value,
            null,
            null,
            MutableMethodImplementation(3)
        ).toMutable().apply {
            addInstructionsWithLabels(
                0,
                """
                    if-eqz p1, :show
                    invoke-virtual { p1 }, Lcom/reddit/domain/model/Link;->getOver18()Z
                    move-result v0
                    if-eqz v0, :show
                    iget-object v0, p0, $producerField
                    iget-object v0, v0, $mapperField
                    iget-object v0, v0, $mapperPreferencesField
                    check-cast v0, ${nsfwViewingPreference.definingClass}
                    invoke-virtual { v0 }, $nsfwViewingPreference
                    move-result v0
                    xor-int/lit8 v0, v0, 0x1
                    return v0
                    :show
                    const/4 v0, 0x0
                    return v0
                """
            )
        }

        PostUnitViewStateFingerprint.let {
            it.classDef.methods.add(shouldHidePostMethod)

            it.method.apply {
                val viewModelRegister = getInstruction<FiveRegisterInstruction>(
                    it.instructionMatches.last().index
                ).registerD
                val linkIndex = it.instructionMatches[1].index
                val linkRegister = getInstruction<TwoRegisterInstruction>(linkIndex).registerA
                val index = linkIndex + 1
                val freeRegister = findFreeRegister(index)

                // Show the empty post view, which also hides the media preview. The post state
                // is not changed, so Reddit can still check the post and show the warning.
                addInstructionsWithLabels(
                    index,
                    """
                        invoke-direct { v$viewModelRegister, v$linkRegister }, $shouldHidePostMethod
                        move-result v$freeRegister
                        if-eqz v$freeRegister, :show
                        const/4 v$linkRegister, 0x0
                        :show
                        nop
                    """
                )
            }
        }

        // endregion

        // region Replace NSFW profiles with the NSFW warning before the profile is published to the
        // header and feed. The native warning is shown too late if the profile is already cached.

        ReplaceScreenExtensionFingerprint.method.addInstructions(
            0,
            """
                check-cast p1, Lcom/reddit/screen/BaseScreen;
                invoke-static { p0, p1 }, ${ReplaceScreenFingerprint.originalMethod}
                return-void
            """
        )

        val profileNsfwField = ProfileToStringFingerprint.instructionMatches.last().getFieldAccessed()
        // Provides the context of the profile screen.
        val contextProviderField = NsfwHomeNavigationFingerprint.instructionMatches.first().getFieldAccessed()
        val profileContextField = constructorFieldFingerprint(
            PROFILE_DETAILS_VIEW_MODEL_CLASS,
            contextProviderField.definingClass
        ).instructionMatches.first().getFieldAccessed()
        val profilePreferencesFingerprint = constructorFieldFingerprint(
            PROFILE_DETAILS_VIEW_MODEL_CLASS,
            preferenceRepositoryType
        )
        val profilePreferencesField = profilePreferencesFingerprint.instructionMatches.first().getFieldAccessed()

        val showProfileWarningMethod = ImmutableMethod(
            PROFILE_DETAILS_VIEW_MODEL_CLASS,
            "patch_showNsfwProfileWarning",
            listOf(ImmutableMethodParameter(profileNsfwField.definingClass, null, null)),
            "Z",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            null,
            null,
            MutableMethodImplementation(3)
        ).toMutable().apply {
            addInstructionsWithLabels(
                0,
                """
                    iget-boolean v0, p1, $profileNsfwField
                    if-eqz v0, :show
                    iget-object v0, p0, $profilePreferencesField
                    check-cast v0, ${nsfwViewingPreference.definingClass}
                    invoke-virtual { v0 }, $nsfwViewingPreference
                    move-result v0
                    if-nez v0, :show
                    iget-object v0, p0, $profileContextField
                    iget-object v0, v0, $contextProviderField
                    invoke-interface { v0 }, Lkotlin/jvm/functions/Function0;->invoke()Ljava/lang/Object;
                    move-result-object v0
                    check-cast v0, Landroid/content/Context;
                    invoke-static { v0 }, $PREVENT_NSFW_FLASHING_EXTENSION_CLASS->showProfileWarning(Landroid/content/Context;)V
                    const/4 v0, 0x1
                    return v0
                    :show
                    const/4 v0, 0x0
                    return v0
                """
            )
        }
        profilePreferencesFingerprint.classDef.methods.add(showProfileWarningMethod)

        PublishProfileDetailsFingerprint.let {
            it.method.apply {
                val viewModelRegister = getInstruction<TwoRegisterInstruction>(
                    it.instructionMatches[1].index
                ).registerB
                val index = it.instructionMatches[2].index
                val profileRegister = getInstruction<FiveRegisterInstruction>(index).registerD
                val unitField = it.instructionMatches[3].getFieldAccessed()
                val freeRegister = findFreeRegister(index)

                addInstructionsWithLabels(
                    index,
                    """
                        check-cast v$profileRegister, ${profileNsfwField.definingClass}
                        invoke-virtual { v$viewModelRegister, v$profileRegister }, $showProfileWarningMethod
                        move-result v$freeRegister
                        if-eqz v$freeRegister, :show
                        sget-object v$freeRegister, $unitField
                        return-object v$freeRegister
                        :show
                        nop
                    """
                )
            }
        }

        // endregion
    }
}
