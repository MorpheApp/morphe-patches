package app.morphe.patches.reddit.nsfw

import app.morphe.patcher.Fingerprint
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

private const val EXTENSION = "Lapp/morphe/extension/reddit/patches/PermanentNsfwPatch;"

// Stable preference keys identify the classes; their obfuscated names are never used.
private object AdultViewingItemFingerprint : Fingerprint(
    name = "<init>", filters = listOf(string("key_pref_over18"))
)

private object AdultBlurItemFingerprint : Fingerprint(
    name = "<init>", filters = listOf(string("key_pref_blur_nsfw"))
)

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

        // The 2026.40.0 and 2026.45.0 settings view models include a row only when this set contains
        // the active session. Returning an empty set removes the row for every session
        // before its Compose content or click handler is created.
        listOf(AdultViewingItemFingerprint, AdultBlurItemFingerprint).forEach { item ->
            Fingerprint(
                classFingerprint = item,
                parameters = listOf(),
                returnType = "Ljava/util/Set;"
            ).method.cloneParameters().apply {
                val register = p0Register
                addInstructionsWithLabels(
                    1,
                    """
                        invoke-static {}, $EXTENSION->isEnabled()Z
                        move-result v$register
                        if-eqz v$register, :original
                        invoke-static {}, Ljava/util/Collections;->emptySet()Ljava/util/Set;
                        move-result-object v$register
                        return-object v$register
                        :original
                        nop
                    """
                )
            }
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
            "nsfw_dialog_under18_submessage" to "disabledMessageResource"
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
