package app.morphe.patches.reddit.nsfw

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.util.findFreeRegister
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.patch
import app.morphe.patcher.resourceLiteral
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.util.numberOfParameterRegistersLogical
import app.morphe.patcher.methodCall
import app.morphe.patcher.newInstance
import app.morphe.patcher.string
import app.morphe.patcher.util.cloneParameters
import app.morphe.patcher.util.p0Register
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.misc.version.is_2026_45_0_or_greater
import app.morphe.patches.reddit.misc.version.versionCheckPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION = "Lapp/morphe/extension/reddit/patches/PermanentNsfwPatch;"

/** Permanently enforce Reddit's native NSFW-off state and remove viewing controls. */
@Suppress("unused")
val permanentNsfwPatch = patch(
    name = "Permanently disable NSFW content (experimental)",
    description = "Adds an irreversible option to disable NSFW content, removes adult viewing controls after activation, " +
        "and prevents re-enabling native NSFW viewing and search. Works with Reddit 2026.40.0 and 2026.45.0. Experimental.",
    default = false
) {
    compatibleWith(COMPATIBILITY_REDDIT.excluding(
        *COMPATIBILITY_REDDIT.targets.mapNotNull {
            it.version.takeUnless { version -> version in setOf("2026.40.0", "2026.45.0") }
        }.toTypedArray()
    ))
    dependsOn(settingsPatch, versionCheckPatch)

    execute {
        Fingerprint(
            definingClass = "Lapp/morphe/extension/reddit/settings/preference/RedditPreferenceFragment;",
            name = "initialize", parameters = listOf(), returnType = "V"
        ).method.cloneParameters().apply {
            findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { index ->
                addInstruction(
                    index,
                    "invoke-static/range { p0 .. p0 }, $EXTENSION->installPreference(Landroid/preference/PreferenceFragment;)V"
                )
            }
        }

        // This isolated header contains the show/hide-NSFW control, including incognito.
        Fingerprint(
            definingClass = "Lcom/reddit/search/combined/ui/composables/",
            returnType = "V",
            filters = listOf(
                resourceLiteral(ResourceType.STRING, "click_label_search_show_nsfw"),
                resourceLiteral(ResourceType.STRING, "click_label_search_hide_nsfw")
            )
        ).method.cloneParameters().apply {
            addInstructionsWithLabels(numberOfParameterRegistersLogical, """
                invoke-static {}, $EXTENSION->isEnabled()Z
                move-result p0
                if-eqz p0, :original
                return-void
                :original
                nop
            """)
        }
        Fingerprint(
            definingClass = "Lcom/reddit/search/domain/model/FilterValues;",
            name = "<init>", returnType = "V",
            parameters = listOf("Lcom/reddit/domain/model/search/Query;",
                "Lcom/reddit/search/domain/model/SearchSortType;",
                "Lcom/reddit/search/domain/model/SearchSortTimeFrame;", "Z",
                "Ljava/util/List;", "Ljava/util/List;", "Ljava/lang/String;", "Ljava/util/Map;", "Ljava/util/List;")
        ).method.addInstructionsWithLabels(0, """
            invoke-static/range { p4 .. p4 }, $EXTENSION->enforceSafeSearch(Z)Z
            move-result p4
        """)

        // Both account and anonymous settings use these native row renderers.
        listOf("label_over18", "label_blur_nsfw_images").forEach { resource ->
            Fingerprint(
                returnType = "V",
                filters = listOf(resourceLiteral(ResourceType.STRING, resource))
            ).method.apply {
                val register = findFreeRegister(0)
                addInstructionsWithLabels(0, """
                    invoke-static {}, $EXTENSION->isEnabled()Z
                    move-result v$register
                    if-eqz v$register, :original
                    return-void
                    :original
                    nop
                """)
            }
        }

        Fingerprint(
            definingClass = "Lcom/reddit/incognito/screens/leave/LeaveIncognitoModeScreen;",
            parameters = listOf("Landroid/view/LayoutInflater;", "Landroid/view/ViewGroup;"),
            returnType = "Landroid/view/View;"
        ).method.apply {
            findInstructionIndicesReversedOrThrow(Opcode.RETURN_OBJECT).forEach { index ->
                val view = getInstruction<OneRegisterInstruction>(index).registerA
                addInstruction(index, "invoke-static/range { v$view .. v$view }, $EXTENSION->hideAnonymousControls(Landroid/view/View;)V")
            }
        }

        // Unlike the account model, this getter also reads saved anonymous preferences.
        Fingerprint(
            parameters = listOf(), returnType = "Z",
            filters = listOf(string("nsfw_over18_enabled"), methodCall(
                definingClass = "Lcom/reddit/domain/model/AccountPreferences;", name = "getOver18"
            ))
        ).method.apply {
            val register = findFreeRegister(0)
            addInstructionsWithLabels(0, """
                invoke-static {}, $EXTENSION->isEnabled()Z
                move-result v$register
                if-eqz v$register, :original
                const/4 v$register, 0x0
                return v$register
                :original
                nop
            """)
        }

        // Override viewing/search preferences at their shared model boundary as well:
        // a native toggle, sync, or account switch cannot re-enable them locally.
        listOf("getOver18", "getSearchIncludeOver18").forEach { getter ->
            Fingerprint(
                definingClass = "Lcom/reddit/domain/model/AccountPreferences;",
                name = getter,
                parameters = listOf(),
                returnType = "Z"
            ).method.cloneParameters().apply {
                val register = p0Register
                addInstructionsWithLabels(
                    1,
                    """
                        invoke-static {}, $EXTENSION->isEnabled()Z
                        move-result v$register
                        if-eqz v$register, :original
                        const/4 v$register, 0x0
                        return v$register
                        :original
                        nop
                    """
                )
            }
        }

        if (is_2026_45_0_or_greater) {
            // Modern age gating reads this enum instead of the legacy getOver18 boolean.
            Fingerprint(
                definingClass = "Lcom/reddit/domain/model/AccountPreferences;",
                name = "getNsfwEnabledPreferenceState", parameters = listOf(),
                returnType = "Lcom/reddit/domain/model/AccountPreferences\$PreferenceState;"
            ).method.cloneParameters().apply {
                val register = p0Register
                addInstructionsWithLabels(1, """
                    invoke-static {}, $EXTENSION->isEnabled()Z
                    move-result v$register
                    if-eqz v$register, :original
                    sget-object v$register, Lcom/reddit/domain/model/AccountPreferences${'$'}PreferenceState;->Disabled:Lcom/reddit/domain/model/AccountPreferences${'$'}PreferenceState;
                    return-object v$register
                    :original
                    nop
                """)
            }
        }
        // Anonymous sessions also retain an independent safe-search enum.
        Fingerprint(
            definingClass = "Lcom/reddit/domain/SafeSearch;", name = "getValue",
            parameters = listOf(), returnType = "Ljava/lang/String;"
        ).method.cloneParameters().apply {
            val register = p0Register
            addInstructionsWithLabels(1, """
                invoke-static {}, $EXTENSION->isEnabled()Z
                move-result v$register
                if-eqz v$register, :original
                const-string v$register, "on"
                return-object v$register
                :original
                nop
            """)
        }
        if (is_2026_45_0_or_greater) {
            // Use the native setter as usual, but refuse attempts to turn viewing back on.
            Fingerprint(
                definingClass = "Lcom/reddit/account/repository/RedditPreferenceRepository\$setOver18\$1;",
                name = "invokeSuspend", parameters = listOf("Ljava/lang/Object;"),
                returnType = "Ljava/lang/Object;",
                filters = listOf(methodCall(
                    parameters = listOf("Z", "Z", "Lkotlin/coroutines/jvm/internal/ContinuationImpl;"),
                    returnType = "Ljava/lang/Object;"
                ))
            ).instructionMatches.single().getMethodCalled().addInstructionsWithLabels(0, """
                invoke-static/range { p1 .. p1 }, $EXTENSION->allowNsfw(Z)Z
                move-result p1
            """)
        } else {
            Fingerprint(
                definingClass = "Lcom/reddit/domain/model/AccountPreferences;",
                name = "setOver18", parameters = listOf("Z"), returnType = "V"
            ).method.addInstructionsWithLabels(0, """
                invoke-static/range { p1 .. p1 }, $EXTENSION->allowNsfw(Z)Z
                move-result p1
            """)
        }

        // These native resources are shared by profile, community and post gates.
        mapOf(
            "nsfw_title" to "disabledTitleResource",
            "nsfw_dialog_title" to "disabledTitleResource",
            "nsfw_description" to "disabledMessageResource",
            "nsfw_dialog_message" to "disabledMessageResource",
            "nsfw_dialog_over18_submessage" to "disabledMessageResource",
            "nsfw_dialog_under18_submessage" to "disabledMessageResource",
            "nsfw_no_thanks" to "goBackLabelResource"
        ).forEach { (resource, hook) ->
            Fingerprint(filters = listOf(resourceLiteral(ResourceType.STRING, resource)))
                .matchAll(1..4).forEach { match ->
                    match.method.apply {
                        val index = match.instructionMatches.single().index
                        val register = getInstruction<OneRegisterInstruction>(index).registerA
                        addInstructionsWithLabels(index + 1, """
                            invoke-static/range { v$register .. v$register }, $EXTENSION->$hook(I)I
                            move-result v$register
                        """)
                    }
                }
        }
        // Omit the primary opt-in button; retain the native dismissal action.
        val primaryButtons = Fingerprint(
            returnType = "Ljava/lang/Object;",
            filters = listOf(
                resourceLiteral(ResourceType.STRING, "nsfw_description"),
                methodCall(definingClass = "Lcom/reddit/ui/compose/ds/", returnType = "V"),
                string("nsfw_button_sheet_cancel")
            )
        ).matchAll(1..1).map { it.method to it.instructionMatches[1].index } +
            Fingerprint(filters = listOf(
                resourceLiteral(ResourceType.STRING, "nsfw_dialog_title"),
                methodCall(name = "setPositiveButton", parameters = listOf("I", "Landroid/content/DialogInterface\$OnClickListener;"))
            )).matchAll(2..2).map { it.method to it.instructionMatches[1].index }
        primaryButtons.forEach { (method, index) ->
            method.apply {
                val register = findFreeRegister(index)
                addInstructionsWithLabels(index, """
                    invoke-static {}, $EXTENSION->isEnabled()Z
                    move-result v$register
                    if-nez v$register, :skipOptIn
                """, ExternalLabel("skipOptIn", getInstruction(index + 1)))
            }
        }

        // The sheet supplies its own navigateBack callback. Android Activity back
        // does not pop this screen, so use that callback for the decline action.
        val navigateHome = Fingerprint(
            returnType = "V",
            filters = listOf(
                methodCall(smali = "Lkotlin/jvm/functions/Function0;->invoke()Ljava/lang/Object;"),
                newInstance("Landroid/content/Intent;"),
                string("com.reddit.frontpage.main_toast_message")
            )
        ).method
        val viewModel = "Lcom/reddit/agegating/impl/nsfw/NsfwBottomSheetViewModel;"
        val warningHandler = Fingerprint(
            definingClass = "Lcom/reddit/agegating/impl/nsfw/NsfwBottomSheetViewModel\$1;",
            name = "access\$invokeSuspend\$handleEvent", returnType = "Ljava/lang/Object;",
            filters = listOf(
                methodCall(parameters = listOf("Lcom/reddit/session/mode/common/SessionMode;"), returnType = "Z"),
                methodCall(smali = navigateHome.toString()),
                fieldAccess(definingClass = "Lkotlin/Unit;", opcode = Opcode.SGET_OBJECT)
            )
        )
        warningHandler.method.apply {
            val index = warningHandler.instructionMatches.first().index
            val unit = warningHandler.instructionMatches.last().getFieldAccessed()
            // Intercept before the session split: the signed-in arm clears the
            // navigation stack and replaces it with Home before the other arm runs.
            val navigateBackField = Fingerprint(definingClass = viewModel, name = "<init>").method
                .implementation!!.instructions.asSequence()
                .mapNotNull { (it as? ReferenceInstruction)?.reference as? FieldReference }
                .first { it.definingClass == viewModel && it.type == "Lkotlin/jvm/functions/Function0;" }
            val register = findFreeRegister(index)
            addInstructionsWithLabels(index, """
                invoke-static {}, $EXTENSION->isEnabled()Z
                move-result v$register
                if-eqz v$register, :nativeDismiss
                iget-object v$register, p0, $navigateBackField
                invoke-interface { v$register }, Lkotlin/jvm/functions/Function0;->invoke()Ljava/lang/Object;
                sget-object v$register, $unit
                return-object v$register
                :nativeDismiss
                nop
            """)
        }

        // The same sheet installs a disabled no-op BackHandler. While permanent
        // disabling is active, wire the gesture to its native navigateBack callback.
        Fingerprint(
            definingClass = "Lcom/reddit/agegating/impl/nsfw/composables/NsfwBottomSheet;",
            filters = listOf(methodCall(definingClass = "Landroidx/activity/compose/b;", name = "a"))
        ).apply {
            method.apply {
                val index = instructionMatches.single().index
                val call = getInstruction<FiveRegisterInstruction>(index)
                val callback = call.registerF
                val enabled = call.registerG
                addInstructionsWithLabels(index, """
                    invoke-static {}, $EXTENSION->isEnabled()Z
                    move-result v$enabled
                    if-eqz v$enabled, :nativeBackHandler
                    new-instance v$callback, Lcom/reddit/agegating/impl/nsfw/composables/NsfwBottomSheet${'$'}onInitialize${'$'}1${'$'}1;
                    invoke-direct { v$callback, p0 }, Lcom/reddit/agegating/impl/nsfw/composables/NsfwBottomSheet${'$'}onInitialize${'$'}1${'$'}1;-><init>(Ljava/lang/Object;)V
                    :nativeBackHandler
                    nop
                """)
            }
        }

        // Search banners are server-authored. Use their native CTA behavior,
        // independent of translated text, to suppress prompts to change NSFW settings.
        val searchBannerClass = Fingerprint(filters = listOf(string("SearchBannerViewState(id="))).originalClassDef
        val searchBannerView = searchBannerClass.type
        val dismissedField = searchBannerClass.fields.single { it.type == "Z" }
        val searchBannerBehaviors = Fingerprint(filters = listOf(string("SearchBannerBehaviors(cta="))).originalClassDef
        val ctaField = searchBannerBehaviors.fields.first()
        val adjustNsfw = Fingerprint(filters = listOf(string("SearchAdjustNsfwSettingsBehavior(id="))).originalClassDef.type
        val verifyAge = Fingerprint(filters = listOf(string("SearchAgeVerificationFlowBehavior(id="))).originalClassDef.type
        Fingerprint(definingClass = searchBannerView, name = "<init>", returnType = "V").method.apply {
            // All constructor parameters have already been stored here, so p1 can
            // be reused without growing a register-starved constructor.
            val index = findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).single()
            addInstructionsWithLabels(index, """
                if-eqz p6, :keepBanner
                iget-object p1, p6, $ctaField
                instance-of p1, p1, $adjustNsfw
                if-nez p1, :checkPermanent
                iget-object p1, p6, $ctaField
                instance-of p1, p1, $verifyAge
                if-eqz p1, :keepBanner
                :checkPermanent
                invoke-static {}, $EXTENSION->isEnabled()Z
                move-result p1
                if-eqz p1, :keepBanner
                const/4 p1, 0x1
                iput-boolean p1, p0, $dismissedField
                :keepBanner
                nop
            """)
        }

        // Reddit's search dropdown can be composed entirely of native SafeSearch
        // options. The screen already ignores a null mapped item, so omit only
        // that dropdown while leaving sort and other filters alone.
        val searchDropdown = Fingerprint(filters = listOf(string("SearchDropdown(id="))).originalClassDef
        val dropdownOptions = searchDropdown.fields.single { it.type == "Ljava/util/List;" }
        Fingerprint(
            definingClass = "Lcom/reddit/search/combined/ui/CombinedSearchResultsViewModel;",
            filters = listOf(methodCall(returnType = "Lcom/reddit/domain/SafeSearch;"))
        ).apply {
            method.apply {
                val parser = getInstruction<ReferenceInstruction>(instructionMatches.first().index).reference as MethodReference
                val optionType = parser.parameterTypes.single()
                addInstructionsWithLabels(0, """
                    move-object/from16 v3, p1
                    instance-of v2, v3, ${searchDropdown.type}
                    if-eqz v2, :normalDropdown
                    check-cast v3, ${searchDropdown.type}
                    iget-object v3, v3, $dropdownOptions
                    invoke-interface { v3 }, Ljava/util/List;->iterator()Ljava/util/Iterator;
                    move-result-object v3
                    const/4 v5, 0x0
                    :nextOption
                    invoke-interface { v3 }, Ljava/util/Iterator;->hasNext()Z
                    move-result v2
                    if-eqz v2, :allOptionsChecked
                    invoke-interface { v3 }, Ljava/util/Iterator;->next()Ljava/lang/Object;
                    move-result-object v4
                    check-cast v4, $optionType
                    invoke-static { v4 }, $parser
                    move-result-object v4
                    if-eqz v4, :normalDropdown
                    const/4 v5, 0x1
                    goto :nextOption
                    :allOptionsChecked
                    if-eqz v5, :normalDropdown
                    invoke-static {}, $EXTENSION->isEnabled()Z
                    move-result v2
                    if-eqz v2, :normalDropdown
                    const/4 v2, 0x0
                    return-object v2
                    :normalDropdown
                    nop
                """)
            }
        }

        // A blocked community is already loaded when Reddit records a recent visit.
        // Guard that single native write using its own over18 metadata.
        Fingerprint(
            definingClass = "Lcom/reddit/screens/pager/v2/SubredditPagerViewModel\$setUpSubreddit\$3;",
            name = "invokeSuspend", returnType = "Ljava/lang/Object;",
            filters = listOf(methodCall(
                parameters = listOf("Lcom/reddit/domain/model/Subreddit;", "Lkotlin/coroutines/jvm/internal/ContinuationImpl;"),
                returnType = "Ljava/lang/Object;"
            ))
        ).instructionMatches.single().getMethodCalled().apply {
            val register = findFreeRegister(0)
            addInstructionsWithLabels(0, """
                invoke-static/range { p1 .. p1 }, $EXTENSION->skipRecentSubreddit(Lcom/reddit/domain/model/Subreddit;)Z
                move-result v$register
                if-eqz v$register, :recordRecentVisit
                const/4 v$register, 0x0
                return-object v$register
                :recordRecentVisit
                nop
            """)
        }

        // Both the drawer and full history screen read lists saved before activation.
        // Filter only those lists; ordinary community lists keep their native behavior.
        listOf(
            "Lcom/reddit/screens/drawer/community/CommunityDrawerPresenter\$refreshRecentlyVisited\$1;",
            "Lcom/reddit/screens/drawer/community/recentlyvisited/RecentlyVisitedPresenter\$loadRecentlyVisited\$1;"
        ).forEach { owner ->
            Fingerprint(definingClass = owner, name = "invokeSuspend", returnType = "Ljava/lang/Object;")
                .method.apply {
                    val index = findInstructionIndicesReversedOrThrow(Opcode.CHECK_CAST).single {
                        getInstruction<ReferenceInstruction>(it).reference.toString() == "Ljava/util/List;"
                    }
                    val register = getInstruction<OneRegisterInstruction>(index).registerA
                    addInstructionsWithLabels(index + 1, """
                        invoke-static/range { v$register .. v$register }, $EXTENSION->visibleRecentSubreddits(Ljava/util/List;)Ljava/util/List;
                        move-result-object v$register
                    """)
                }
        }

        setExtensionIsPatchIncluded(EXTENSION)
    }
}
