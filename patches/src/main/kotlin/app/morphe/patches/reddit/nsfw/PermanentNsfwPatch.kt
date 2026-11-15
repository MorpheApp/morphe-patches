package app.morphe.patches.reddit.nsfw

import app.morphe.patcher.Fingerprint
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
    dependsOn(settingsPatch)

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

        setExtensionIsPatchIncluded(EXTENSION)
    }
}
