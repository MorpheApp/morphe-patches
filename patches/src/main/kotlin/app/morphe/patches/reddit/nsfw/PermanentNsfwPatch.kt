package app.morphe.patches.reddit.nsfw

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.patch
import app.morphe.patcher.resourceLiteral
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.util.numberOfParameterRegistersLogical
import app.morphe.patcher.string
import app.morphe.patcher.util.cloneParameters
import app.morphe.patcher.util.p0Register
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.patches.reddit.shared.Constants.COMPATIBILITY_REDDIT
import app.morphe.util.setExtensionIsPatchIncluded
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode

private const val EXTENSION = "Lapp/morphe/extension/reddit/patches/PermanentNsfwPatch;"

private object OkHttpClientConstructorFingerprint : Fingerprint(
    definingClass = "Lokhttp3/OkHttpClient;",
    name = "<init>",
    returnType = "V",
    parameters = listOf("Lokhttp3/OkHttpClient\$Builder;")
)

// Stable preference keys identify the classes; their obfuscated names are never used.
private object AdultViewingItemFingerprint : Fingerprint(
    name = "<init>", filters = listOf(string("key_pref_over18"))
)

private object AdultBlurItemFingerprint : Fingerprint(
    name = "<init>", filters = listOf(string("key_pref_blur_nsfw"))
)

/** Experimental until the target APK's transports, schemas and native settings are verified. */
@Suppress("unused")
val permanentNsfwPatch = patch(
    name = "Permanently disable NSFW content (experimental)",
    description = "Adds an irreversible option to disable NSFW content, removes adult viewing controls after activation, " +
        "and blocks RedGIFs. Works with Reddit 2026.40.0 and 2026.45.0. Experimental: content coverage requires validation.",
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

        val communityName = Fingerprint(
            definingClass = "Lcom/reddit/screens/pager/v2/SubredditPagerV2Screen;",
            parameters = listOf(), returnType = "Ljava/lang/String;",
            filters = listOf(string("subreddit_prefixed_name"))
        ).method
        Fingerprint(
            definingClass = "Lcom/reddit/screens/pager/v2/SubredditPagerV2Screen;",
            parameters = listOf("Landroid/view/View;"), returnType = "V",
            filters = listOf(string("nsfwAlertDelegate"))
        ).method.cloneParameters().apply {
            findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach { index ->
                addInstructionsWithLabels(index, """
                    invoke-virtual/range { p0 .. p0 }, $communityName
                    move-result-object p0
                    invoke-static/range { p0 .. p1 }, Lapp/morphe/extension/reddit/patches/NsfwCommunityGate;->bind(Ljava/lang/String;Landroid/view/View;)V
                """)
            }
        }
        // Runs before Reddit's visited/account/incognito checks, including cached models.
        Fingerprint(
            definingClass = "Lcom/reddit/screens/pager/v2/SubredditPagerViewModel;",
            parameters = listOf("Lcom/reddit/screens/pager/v2/SubredditPagerViewModel;", "Lcom/reddit/domain/model/Subreddit;"),
            returnType = "V", filters = listOf(string("nsfwAlertDelegate"))
        ).method.cloneParameters().apply {
            addInstructionsWithLabels(2, """
                invoke-virtual/range { p1 .. p1 }, Lcom/reddit/domain/model/Subreddit;->getDisplayName()Ljava/lang/String;
                move-result-object p0
                invoke-virtual/range { p1 .. p1 }, Lcom/reddit/domain/model/Subreddit;->getOver18()Ljava/lang/Boolean;
                move-result-object p1
                invoke-static/range { p0 .. p1 }, Lapp/morphe/extension/reddit/patches/NsfwCommunityGate;->classify(Ljava/lang/String;Ljava/lang/Boolean;)V
            """)
        }

        // Exact signature required: fail patching instead of silently shipping without a hook.
        OkHttpClientConstructorFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p1 .. p1 }, $EXTENSION->install(Lokhttp3/OkHttpClient\$Builder;)V"
        )

        // The 2026.40.0 and 2026.45.0 settings view models includes a row only when this set contains
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

        // The inherited dialog-removal patch must not become an adult-content bypass.
        Fingerprint(
            definingClass = "Lapp/morphe/extension/reddit/patches/RemoveSubRedditDialogPatch;",
            name = "spoofHasBeenVisitedStatus", parameters = listOf("Z"), returnType = "Z"
        ).method.cloneParameters().apply {
            val register = p0Register
            addInstructionsWithLabels(1, """
                invoke-static {}, $EXTENSION->isEnabled()Z
                move-result v$register
                if-eqz v$register, :original
                const/4 v$register, 0x0
                return v$register
                :original
                nop
            """)
        }
        Fingerprint(
            definingClass = "Lapp/morphe/extension/reddit/patches/RemoveSubRedditDialogPatch;",
            name = "dismissNSFWDialog", parameters = listOf("Ljava/lang/Object;"), returnType = "V"
        ).method.cloneParameters().apply {
            val register = p0Register
            addInstructionsWithLabels(1, """
                invoke-static {}, $EXTENSION->isEnabled()Z
                move-result v$register
                if-eqz v$register, :original
                return-void
                :original
                nop
            """)
        }
        setExtensionIsPatchIncluded(EXTENSION)
    }
}
