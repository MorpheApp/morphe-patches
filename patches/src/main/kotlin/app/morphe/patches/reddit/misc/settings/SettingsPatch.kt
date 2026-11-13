/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.misc.settings

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.listDecodableFiles
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceXml
import app.morphe.patcher.string
import app.morphe.patcher.util.cloneParameters
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.p0Register
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.registersUsed
import app.morphe.patcher.util.returnEarly
import app.morphe.patches.all.misc.crashlog.crashLogPatch
import app.morphe.patches.all.misc.fix.openurllinks.removeLinkVerification
import app.morphe.patches.all.misc.resources.addAppResources
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.all.misc.resources.localesReddit
import app.morphe.patches.all.misc.resources.setAddResourceLocale
import app.morphe.patches.all.misc.updates.disablePlayStoreUpdatesPatch
import app.morphe.patches.reddit.misc.extension.hooks.RedditActivityOnCreateFingerprint
import app.morphe.patches.reddit.misc.extension.hooks.redditActivityOnCreateHook
import app.morphe.patches.reddit.misc.extension.sharedExtensionPatch
import app.morphe.patches.reddit.misc.fix.signature.spoofSignaturePatch
import app.morphe.patches.reddit.misc.version.is_2024_03_0_or_greater
import app.morphe.patches.reddit.misc.version.is_2026_14_0_or_greater
import app.morphe.patches.reddit.misc.version.is_2026_25_0_or_greater
import app.morphe.patches.reddit.misc.version.is_2026_30_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.patches.shared.misc.announcements.patchesUpdateNoticePatch
import app.morphe.patches.shared.misc.bridge.addBridge
import app.morphe.patches.shared.misc.checks.experimentalAppNoticePatch
import app.morphe.util.ResourceGroup
import app.morphe.util.copyResources
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.util.MethodUtil
import java.util.logging.Logger

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/settings/RedditActivityHook;"

private const val SETTINGS_STYLE_EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/settings/preference/RedditSettingsStyle;"

private const val APP_DOWNGRADE_EXTENSION_CLASS =
    "Lapp/morphe/extension/reddit/patches/AppDowngradeNoticePatch;"

/**
 * Order of the Morphe row in the first settings section. The rows of the app start at 0.
 */
private const val MORPHE_SETTING_ROW_ORDER = -1000

private val logger = Logger.getLogger("RedditSettingsPatch")

/**
 * Packed size of a 20dp icon, the size of the Reddit settings icons (width shl 32 or height).
 */
private const val SETTING_ICON_SIZE = "0x1400000014L"

val settingsPatch = patch(
    description = "Applies mandatory patches to implement Morphe settings into the application."
) {
    compatibleWith(COMPATIBILITY_REDDIT)

    dependsOn(
        sharedExtensionPatch,
        disablePlayStoreUpdatesPatch,
        spoofSignaturePatch,
        removeLinkVerification,
        addResourcesPatch,
        versionCheckPatch,
        crashLogPatch(
            mainActivityFingerprint = RedditActivityOnCreateFingerprint
        ),
        experimentalAppNoticePatch(
            mainActivityFingerprint = redditActivityOnCreateHook.fingerprint,
            recommendedAppVersion = COMPATIBILITY_REDDIT.targets.first { !it.isExperimental }.version!!
        ),
        patchesUpdateNoticePatch(
            mainActivityFingerprint = redditActivityOnCreateHook.fingerprint,
            changelogScope = "Reddit"
        ),
        patch {
            execute {
                copyResources(
                    "settings",
                    ResourceGroup("drawable",
                        "morphe_ic_dialog_alert.xml",
                        "morphe_settings_custom_checkmark.xml",
                        "morphe_settings_custom_checkmark_bold.xml",
                        "morphe_settings_icon_reddit.xml"
                    )
                )
            }
        }
    )

    execute {
        setAddResourceLocale(localesReddit)
        addAppResources("shared")
        addAppResources("reddit")

        if (!is_2024_03_0_or_greater) {
            throw PatchException(
                """

                    !!!
                    !!! Reddit 2024.02.0 supports only 1 patch.
                    !!! Select the recommended patches to patch this legacy app version.
                    !!!
                """
            )
        }

        // Disable Play Store updates allows downgrading, but older app data can cause app crashes and other problems.
        RedditActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $APP_DOWNGRADE_EXTENSION_CLASS->showDowngradeNoticeIfNeeded(Landroid/app/Activity;)V"
        )

        // Turn off Google Play in app update prompt.
        GooglePlayUpdateCheckFingerprint.method.returnEarly(null)

        // Force Play Store Verification checks to pass.
        PlayStoreVerificationFingerprint.method.returnEarly(false)

        // Show toast informing that Google sign-in does not work.
        if (is_2026_14_0_or_greater) {
            // After clicking a login type, the second Google sign-in button still shows
            // the Google login dialog. Unclear where this additional UI layout is handled,
            // but it may be provided server side.
            GoogleSignInFunctionFingerprint.matchAll(2 .. 3).forEach {
                val index = it.instructionMatches[1].index
                val register = it.method.getInstruction(index).registersUsed[3]
                it.method.addInstructions(
                    index,
                    """
                        invoke-static { }, $EXTENSION_CLASS->getGoogleSignInFunction()Lkotlin/jvm/functions/Function0;
                        move-result-object v$register
                    """
                )
            }
        }

        if (is_2026_25_0_or_greater) {
            // The app settings use Compose since 2026.25.0. Use the same style for the Morphe settings.
            mutableClassDefBy(SETTINGS_STYLE_EXTENSION_CLASS).methods
                .single { it.name == "isModernStyle" }
                .returnEarly(true)
        }

        if (is_2026_25_0_or_greater && !hookComposeSettingRow()) {
            if (is_2026_30_0_or_greater) {
                logger.warning(
                    "Could not find the Compose settings row. Using the privacy policy link instead."
                )
            }

            // Change menu item title to Morphe.
            // This also renames the privacy policy link of the community drawer.
            listDecodableFiles("res/values*/strings.xml").forEach { path ->
                resourceXml(path).use { strings ->
                    strings.resourceOrNull("label_privacy_policy")?.text = "Morphe"
                }
            }

            StartUrlActivityFingerprint.let {
                val index = it.instructionMatches.last().index
                it.method.apply {
                    val p0Register = p0Register
                    val free = findFreeRegister(index, p0Register + 1, p0Register + 2)
                    addInstructionsWithLabels(
                        index,
                        """
                            invoke-static { p1, p2 }, $EXTENSION_CLASS->openMorpheSettings(Landroid/app/Activity;Landroid/net/Uri;)Z
                            move-result v$free
                            if-eqz v$free, :ignore
                            return-void
                            :ignore
                            nop
                        """
                    )
                }
            }
        }

        if (is_2026_30_0_or_greater) {
            return@execute
        }

        CheckIntegrityPlayStoreFingerprint.method.returnEarly(0)

        /**
         * Replace settings label and icon
         */
        PreferenceManagerLegacyFingerprint.let {
            it.method.apply {
                val labelIndex = it.instructionMatches[5].index
                val labelRegister = getInstruction<OneRegisterInstruction>(labelIndex).registerA

                addInstructions(
                    labelIndex + 1,
                    """
                        invoke-static { }, $EXTENSION_CLASS->getSettingLabel()Ljava/lang/String;
                        move-result-object v$labelRegister
                    """
                )

                val iconIndex = it.instructionMatches[2].index
                val iconRegister = getInstruction<OneRegisterInstruction>(iconIndex).registerA

                addInstructions(
                    iconIndex + 1,
                    """
                        invoke-static { }, $EXTENSION_CLASS->getSettingIcon()Landroid/graphics/drawable/Drawable;
                        move-result-object v$iconRegister
                    """
                )
            }
        }

        PreferenceDestinationLegacyFingerprint.let {
            val getActivityMethod = Fingerprint(
                accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
                returnType = RedditActivityFingerprint.originalClassDef.type,
                parameters = listOf()
            ).method

            val startActivityMethod = Fingerprint(
                definingClass = getActivityMethod.definingClass,
                returnType = "V",
                parameters = listOf(
                    "Landroid/content/Intent",
                    "I",
                    "Landroid/os/Bundle;"
                ),
                filters = listOf(
                    string(" not attached to Activity"),
                )
            ).method

            it.method.cloneParameters().addInstructionsWithLabels(
                0,
                """
                    invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->isAcknowledgment(Ljava/lang/Enum;)Z
                    move-result v0
                    if-eqz v0, :ignore

                    invoke-virtual { p0 }, $getActivityMethod
                    move-result-object v0
                    invoke-static { v0 }, $EXTENSION_CLASS->initializeByIntent(Landroid/content/Context;)Landroid/content/Intent;
                    move-result-object v0

                    const/4 v1, -1
                    const/4 v2, 0x0
                    invoke-virtual { p0, v0, v1, v2 }, ${getActivityMethod.definingClass}->${startActivityMethod.name}(Landroid/content/Intent;ILandroid/os/Bundle;)V
                    return-void

                    :ignore
                    nop
                """
            )
        }

        WebBrowserActivityOnCreateFingerprint.let {
            it.method.apply {
                val insertIndex = it.instructionMatches.first().index
                val freeRegister = findFreeRegister(insertIndex)

                addInstructionsWithLabels(
                    insertIndex + 1,
                    """
                        invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->hook(Landroid/app/Activity;)Z
                        move-result v$freeRegister
                        if-eqz v$freeRegister, :ignore
                        return-void
                        :ignore
                        nop
                    """
                )
            }
        }

        // Override finish() to show the restart dialog when closing the settings.
        WebBrowserActivityOnCreateFingerprint.classDef.let { activityClass ->
            ImmutableMethod(
                activityClass.type,
                "finish",
                emptyList(),
                "V",
                AccessFlags.PUBLIC.value,
                null,
                null,
                MutableMethodImplementation(2)
            ).toMutable().apply {
                addInstructionsWithLabels(
                    0,
                    """
                        invoke-static { p0 }, $EXTENSION_CLASS->handleFinish(Landroid/app/Activity;)Z
                        move-result v0
                        if-nez v0, :handled
                        invoke-super { p0 }, ${activityClass.superclass}->finish()V
                        :handled
                        return-void
                    """
                )
            }.let(activityClass.methods::add)
        }
    }
}

/**
 * Turns the "Privacy policy" row of the Compose settings into the Morphe settings row,
 * with the Morphe title, icon and click action, at the top of the first section.
 *
 * Unlike relabeling the privacy policy string and intercepting its URL, this only changes the
 * settings row, so other privacy policy links of the app keep working.
 *
 * 2026.30.0+
 *
 * @return If the row was found and hooked.
 */
private fun hookComposeSettingRow(): Boolean {
    val constructorMatch = PrivacyPolicySettingRowConstructorFingerprint.matchOrNull() ?: return false
    val contentMatch = PrivacyPolicySettingRowContentFingerprint.matchOrNull() ?: return false
    val keyField = constructorMatch.instructionMatches[1].fieldReference

    // Title, click action and icon.
    contentMatch.let {
        val callIndex = it.instructionMatches.last().index
        it.method.apply {
            val call = getInstruction<RegisterRangeInstruction>(callIndex)
            val titleRegister = call.startRegister
            val onClickRegister = call.startRegister + 1
            val iconRegister = call.startRegister + 5

            val iconInstructions = if (implementSettingIconComposable()) {
                """
                    invoke-static { }, $EXTENSION_CLASS->getSettingIconComposable()Lkotlin/jvm/functions/Function2;
                    move-result-object v$iconRegister
                """
            } else {
                logger.warning(
                    "Could not find the Reddit icon composable. The settings row keeps the original icon."
                )
                ""
            }

            addInstructions(
                callIndex,
                """
                    invoke-static { }, $EXTENSION_CLASS->getSettingLabel()Ljava/lang/String;
                    move-result-object v$titleRegister
                    invoke-static/range { v$onClickRegister .. v$onClickRegister }, $EXTENSION_CLASS->getSettingOnClick(Lkotlin/jvm/functions/Function0;)Lkotlin/jvm/functions/Function0;
                    move-result-object v$onClickRegister
                    $iconInstructions
                """
            )
        }
    }

    // Position. Rows are sorted by the order of the section, then by the order of the row.
    constructorMatch.classDef.methods.let { methods ->
        fun MutableMethod.isGetter(returnType: String) = this.returnType == returnType &&
                parameterTypes.isEmpty() && !AccessFlags.STATIC.isSet(accessFlags)

        methods.singleOrNull { it.isGetter(USER_SETTINGS_SECTION_CLASS) }?.overrideForMorpheRow(
            keyField,
            """
                sget-object v0, $USER_SETTINGS_SECTION_CLASS->General:$USER_SETTINGS_SECTION_CLASS
                return-object v0
            """
        ) ?: logger.warning("Could not find the section of the settings row")

        (methods.singleOrNull { it.name == "getOrder" && it.isGetter("I") }
            ?: methods.singleOrNull { it.name != "hashCode" && it.isGetter("I") })?.overrideForMorpheRow(
            keyField,
            """
                const/16 v0, $MORPHE_SETTING_ROW_ORDER
                return v0
            """
        ) ?: logger.warning("Could not find the order of the settings row")
    }

    return true
}

/**
 * Returns a different value from a getter of a settings row class, if the row is the Morphe row.
 * The class is shared by several rows, which differ by their key.
 */
private fun MutableMethod.overrideForMorpheRow(
    keyField: FieldReference,
    returnInstructions: String
) {
    // The register v0 is used before the original code assigns it.
    if (implementation!!.registerCount <= MethodUtil.getParameterRegisterCount(this)) {
        throw PatchException("No free register in $this")
    }

    addInstructionsWithLabels(
        0,
        """
            iget-object v0, p0, $keyField
            invoke-static { v0 }, $EXTENSION_CLASS->isMorpheSettingRow(Ljava/lang/String;)Z
            move-result v0
            if-eqz v0, :original
            $returnInstructions
            :original
            nop
        """
    )
}

/**
 * Implements the extension placeholder that draws the Morphe icon in the settings row,
 * with the icon composable of the Reddit icon set. The icon set loads a vector drawable resource.
 *
 * @return If the icon composable of the app was found.
 */
private fun implementSettingIconComposable(): Boolean {
    val iconDataClass = IconDataToStringFingerprint.originalClassDefOrNull ?: return false
    val iconDataType = iconDataClass.type
    val hasExpectedConstructor = iconDataClass.methods.any {
        MethodUtil.isConstructor(it) && it.parameterTypes.map(CharSequence::toString) == listOf("J", "I", "Z")
    }
    if (!hasExpectedConstructor) return false

    val iconComposable = IconComposableFingerprint(iconDataType).originalMethodOrNull ?: return false
    val composerType = iconComposable.parameterTypes[5]

    mutableClassDefBy(EXTENSION_CLASS).apply {
        val placeholder = methods.single { it.name == "drawSettingIcon" }
        methods.remove(placeholder)

        // Parameters: composer, drawable resource id.
        addBridge(
            placeholder.name,
            listOf("Ljava/lang/Object;", "I"),
            "V",
            11,
            """
                new-instance v0, $iconDataType
                const-wide v1, $SETTING_ICON_SIZE
                const/4 v3, 0x0
                invoke-direct { v0, v1, v2, p1, v3 }, $iconDataType-><init>(JIZ)V

                # Icon, content description, modifier, tint, unknown, composer, changed, defaults.
                # Same arguments as the settings icons of the app, with the default modifier and tint.
                const/4 v1, 0x0
                const/4 v2, 0x0
                const-wide/16 v3, 0x0
                const/4 v5, 0x0
                move-object v6, p0
                check-cast v6, $composerType
                const/16 v7, 0x30
                const/16 v8, 0x1c
                invoke-static/range { v0 .. v8 }, $iconComposable
                return-void
            """,
            placeholder.accessFlags
        )
    }

    return true
}
