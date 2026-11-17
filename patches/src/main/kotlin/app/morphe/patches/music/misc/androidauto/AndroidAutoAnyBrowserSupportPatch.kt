/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://codeberg.org/JohnInNL/nl-morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.misc.androidauto

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.classDefForEach
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import app.morphe.patcher.util.addPatchInterface
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.util.function.Function

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/AndroidAutoAnyBrowserSupport;"
private const val ANY_REQUEST =
    $$"Lapp/morphe/extension/music/patches/AndroidAutoAnyBrowserSupport$Request;"
private const val BROWSER_SERVICE_CLASS =
    "Lcom/google/android/apps/youtube/music/mediabrowser/MusicBrowserService;"

/** The Android Auto patch of the app, whose library loaders this patch links to any backend. */
private const val ADAPTER = "Lapp/morphe/extension/music/patches/AndroidAutoPatch;"
private const val REQUEST = $$"Lapp/morphe/extension/music/patches/AndroidAutoPatch$BrowseRequest;"
private const val PREPARED_ITEMS = $$"Lapp/morphe/extension/music/patches/AndroidAutoPatch$PreparedItems;"
private const val LIBRARY_FOLDER = $$"Lapp/morphe/extension/music/patches/AndroidAutoPatch$LibraryFolder;"
private const val HOME_LOAD = $$"Lapp/morphe/extension/music/patches/AndroidAutoPatch$HomeLoad;"
private const val HOME_LOAD_MODE = $$"Lapp/morphe/extension/music/patches/AndroidAutoPatch$HomeLoad$Mode;"
private const val LOAD = $$"Lapp/morphe/extension/music/patches/AndroidAutoPatch$Load;"

private const val HOME_MEDIA_ID = "morphe:aa:any:home"
private const val LIBRARY_MEDIA_ID = "morphe:aa:any:library"
private const val STRING = "Ljava/lang/String;"

private const val ORIGINAL_TRUST = "patch_originalCallerPolicy"
private const val ORIGINAL_ELIGIBILITY = "patch_originalBrowserEligibility"
private const val BRIDGE = "patch_handleAnyBrowseRequest"

/**
 * Adds access to personal Home and Library using the app's existing account to any Android Auto
 * backend, such as DriveDeck, Gearslip, AARE or Flywheel. The Android Auto patch is enabled
 * alongside it, and every caller is verified by its real Android identity: only a caller that owns
 * its claimed package under the callback UID is admitted.
 */
@Suppress("unused")
val androidAutoAnyBrowserSupportPatch = patch(
    name = "Android Auto (Any) Music Browser Support",
    description = "Adds access to personal Home and Library using the app's existing account " +
        "to any Android Auto backend. This ensures full support with alternative Android Auto " +
        "backends such as DriveDeck, Gearslip, AARE and Flywheel. " +
        "Verifies the real Android caller identity.",
) {
    dependsOn(androidAutoPatch)

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        // Resolve the security boundary from the original caller check and the browser call graph.
        val candidates = Fingerprint(returnType = "Z", filters = listOf(string(BrowserDiscovery.UID_DIAGNOSTIC)))
            .matchAll().map { it.method }.toList()
        val layout = BrowserDiscovery.discover(
            candidates,
            classDefBy(BROWSER_SERVICE_CLASS),
            Function { classDefBy(it) },
        )
        val owner = mutableClassDefBy(layout.trust.definingClass)
        val trust = owner.methods.single { it.toString() == layout.trust.toString() }
        val eligible = owner.methods.single { it.toString() == layout.eligible.toString() }
        check(owner.methods.none { it.name in setOf(ORIGINAL_TRUST, ORIGINAL_ELIGIBILITY) }) {
            "Android Auto (Any) music browser support is already applied"
        }

        // Preserve both original implementations verbatim. Wrappers own their locals, so
        // changing R8 register allocations cannot shift parameters in the original code.
        fun wrap(method: MutableMethod, name: String, registers: Int, body: String) {
            val original = ImmutableMethod(method.definingClass, name, method.parameters,
                method.returnType, method.accessFlags, method.annotations, method.hiddenApiRestrictions,
                method.implementation).toMutable()
            val wrapper = ImmutableMethod(method.definingClass, method.name, method.parameters,
                method.returnType, method.accessFlags, method.annotations, method.hiddenApiRestrictions,
                MutableMethodImplementation(registers)).toMutable()
            wrapper.addInstructions(0, body)
            check(owner.methods.remove(method)) { "Browser method disappeared before wrapping" }
            owner.methods.add(original)
            owner.methods.add(wrapper)
        }
        fun invoke(method: MutableMethod) =
            if (AccessFlags.PRIVATE.isSet(method.accessFlags)) "invoke-direct" else "invoke-virtual"
        val type = owner.type
        val callerType = trust.parameterTypes[0]
        val clientType = trust.parameterTypes[1]

        // Any caller is admitted once it owns its claimed package under the callback UID.
        // Everything else, including a caller without a package to verify, keeps the original policy.
        wrap(trust, ORIGINAL_TRUST, 7, """
            if-eqz p1, :original_caller_policy
            if-eqz p2, :original_caller_policy
            iget-object v0, p0, ${layout.contextField}
            iget-object v1, p1, ${layout.callerPackage}
            invoke-static { v0, v1, p3 }, $EXTENSION_CLASS->decision(Landroid/content/Context;Ljava/lang/String;I)I
            move-result v0
            if-ltz v0, :original_caller_policy
            return v0
            :original_caller_policy
            ${invoke(trust)}/range { p0 .. p3 }, $type->$ORIGINAL_TRUST($callerType$clientType)Z
            move-result v0
            return v0
        """)
        // The caller was authenticated when it connected, so every client may browse.
        wrap(eligible, ORIGINAL_ELIGIBILITY, 3, """
            if-eqz p1, :original_browser_eligibility
            const/4 v0, 0x1
            return v0
            :original_browser_eligibility
            ${invoke(eligible)} { p0, p1 }, $type->$ORIGINAL_ELIGIBILITY($clientType)Z
            move-result v0
            return v0
        """)
    }

    finalize {
        // The Android Auto patch creates its BrowseRequest bridges during execute,
        // so link its phone-session loaders only now, after every patch executed.
        val adapters = mutableListOf<ClassDef>()
        classDefForEach { cls -> if (cls.type == ADAPTER) adapters.add(cls) }
        check(adapters.size == 1) { "The Android Auto patch must be enabled with this patch" }
        val adapter = mutableClassDefBy(adapters.single())
        check(adapter.methods.none { it.name == BRIDGE }) {
            "Android Auto (Any) music browser support is already applied"
        }

        fun method(type: String, name: String, params: List<String>, result: String) =
            classDefBy(type).methods.singleOrNull {
                it.name == name && it.parameterTypes.map(CharSequence::toString) == params &&
                    it.returnType == result && it.implementation != null
            } ?: error("Unsupported Android Auto library adapter: missing $type->$name")

        val handler = adapter.methods.singleOrNull {
            it.name == "handleBrowseRequest" && it.parameterTypes.map(CharSequence::toString) == listOf(REQUEST) &&
                it.returnType == "Z"
        } ?: error("Apply the Android Auto library enhancements before this patch")
        check(AccessFlags.STATIC.isSet(handler.accessFlags) && handler.implementation!!.registerCount >= 2)
        method(PREPARED_ITEMS, "<init>", emptyList(), "V")
        method(PREPARED_ITEMS, "add", listOf(STRING, STRING, STRING, STRING, "Z", STRING), "V")
        method(LIBRARY_FOLDER, "values", emptyList(), "[$LIBRARY_FOLDER")
        val folderItem = classDefBy(LIBRARY_FOLDER).methods.single {
            it.name == "mediaItem" && it.parameterTypes.isEmpty()
        }
        check(folderItem.returnType == "Landroid/support/v4/media/MediaBrowserCompat\$MediaItem;")
        check(classDefBy(LIBRARY_FOLDER).fields.map { it.name }
            .containsAll(listOf("PLAYLISTS", "SONGS", "ALBUMS", "ARTISTS", "LAST_PLAYED")))
        method(LOAD, "start", emptyList(), "V")
        // The constructor of the private HomeLoad loader class; the bridge lives in the same class,
        // so its visibility does not matter.
        val homeConstructor = classDefBy(HOME_LOAD).methods.singleOrNull {
            it.name == "<init>" && it.parameterTypes.size == 4 &&
                it.parameterTypes.map(CharSequence::toString) == listOf(REQUEST, HOME_LOAD_MODE, STRING, "Ljava/util/List;")
        } ?: error("Unsupported Android Auto library adapter: Home loader constructor changed")

        // Capture only the RemoteUserInfo supplied by the original service callback. Never use
        // the bundle's caller_package_name or a media ID as a substitute for caller identity.
        val infoType = Fingerprint(filters = listOf(string("MBS: getCurrentBrowserInfo() failed.")))
            .matchAll().map { it.method }.single { it.definingClass == BROWSER_SERVICE_CLASS }.returnType
        val infoPackage = classDefBy(infoType).methods.single {
            it.parameterTypes.isEmpty() && it.returnType == STRING && !AccessFlags.STATIC.isSet(it.accessFlags)
        }
        val requestClasses = mutableListOf<ClassDef>()
        classDefForEach { cls -> if (REQUEST in cls.interfaces) requestClasses.add(cls) }
        val request = mutableClassDefBy(requestClasses.single())
        val info = request.fields.single {
            it.type == infoType && !AccessFlags.STATIC.isSet(it.accessFlags)
        }
        request.addPatchInterface(
            ANY_REQUEST,
            "patch_getCallerPackage",
            """
                iget-object v0, p0, $info
                if-eqz v0, :missing_browser
                invoke-virtual { v0 }, $infoPackage
                move-result-object v0
                return-object v0
                :missing_browser
                const/4 v0, 0x0
                return-object v0
            """,
        )

        // The root answers with personal Home and Library, which the loaders of the Android Auto
        // patch fill using the app's existing account.
        val bridge = ImmutableMethod(
            ADAPTER, BRIDGE,
            listOf(ImmutableMethodParameter(REQUEST, emptySet(), null)), "Z",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, emptySet(), emptySet(),
            MutableMethodImplementation(9),
        ).toMutable()
        bridge.addInstructions(0, """
            invoke-interface { p0 }, $REQUEST->patch_getRequestedMediaId()Ljava/lang/String;
            move-result v0
            invoke-static { p0, v0 }, $EXTENSION_CLASS->route(Ljava/lang/Object;Ljava/lang/String;)I
            move-result v0
            const/4 v1, 0x1
            if-eq v0, v1, :root
            const/4 v1, 0x2
            if-eq v0, v1, :home
            const/4 v1, 0x3
            if-eq v0, v1, :library
            const/4 v0, 0x0
            return v0
            :root
            new-instance v0, $PREPARED_ITEMS
            invoke-direct { v0 }, $PREPARED_ITEMS-><init>()V
            const-string v1, "$HOME_MEDIA_ID"
            const-string v2, "Home"
            const-string v3, ""
            const-string v4, ""
            const/4 v5, 0x0
            const-string v6, ""
            invoke-virtual/range { v0 .. v6 }, $PREPARED_ITEMS->add($STRING$STRING$STRING${STRING}Z$STRING)V
            const-string v1, "$LIBRARY_MEDIA_ID"
            const-string v2, "Library"
            invoke-virtual/range { v0 .. v6 }, $PREPARED_ITEMS->add($STRING$STRING$STRING${STRING}Z$STRING)V
            goto :deliver
            :home
            new-instance v0, $HOME_LOAD
            move-object v1, p0
            sget-object v2, $HOME_LOAD_MODE->HOME:$HOME_LOAD_MODE
            const-string v3, ""
            invoke-static {}, Ljava/util/Collections;->emptyList()Ljava/util/List;
            move-result-object v4
            invoke-direct/range { v0 .. v4 }, $homeConstructor
            invoke-virtual { v0 }, $LOAD->start()V
            const/4 v0, 0x1
            return v0
            :library
            new-instance v0, $PREPARED_ITEMS
            invoke-direct { v0 }, $PREPARED_ITEMS-><init>()V
            invoke-static {}, $LIBRARY_FOLDER->values()[$LIBRARY_FOLDER
            move-result-object v1
            const/4 v2, 0x0
            array-length v3, v1
            :next_folder
            if-ge v2, v3, :deliver
            aget-object v4, v1, v2
            invoke-virtual { v4 }, Ljava/lang/Enum;->name()Ljava/lang/String;
            move-result-object v5
            const-string v6, "UPLOADED_"
            invoke-virtual { v5, v6 }, $STRING->startsWith($STRING)Z
            move-result v5
            if-nez v5, :skip_folder
            invoke-virtual { v4 }, $folderItem
            move-result-object v7
            invoke-virtual { v0, v7 }, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z
            :skip_folder
            add-int/lit8 v2, v2, 0x1
            goto :next_folder
            :deliver
            invoke-interface { p0, v0 }, $REQUEST->patch_deliverBrowseItems(Ljava/util/List;)V
            const/4 v0, 0x1
            return v0
        """)
        adapter.methods.add(bridge)
        handler.addInstructions(0, """
            invoke-static/range { p0 .. p0 }, $ADAPTER->$BRIDGE($REQUEST)Z
            move-result v0
            if-eqz v0, :original_browse_request
            return v0
            :original_browse_request
        """)
    }
}
