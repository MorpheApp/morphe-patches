/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4867
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.filter

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.packageMetadata
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.p0Register
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.reddit.layout.flair.FeedElementProcessorFingerprint
import app.morphe.patches.reddit.layout.flair.LinkConstructorFingerprint
import app.morphe.patches.reddit.layout.flair.PostsToLoadFingerprint
import app.morphe.patches.reddit.misc.settings.settingsPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.setExtensionIsPatchIncluded
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

private const val EXTENSION = "Lapp/morphe/extension/reddit/patches/ContentFilterPatch;"
private const val NODE = $$"Lapp/morphe/extension/reddit/patches/ContentFilterPatch$Node;"
internal val contentFilterCompatibility = Compatibility(
    name = "Reddit", packageName = "com.reddit.frontpage", apkFileType = ApkFileType.APKM,
    targets = listOf(AppTarget(version = "2026.45.0", minSdk = 32))
)

// Every owner is resolved from its diagnostic string, never from an obfuscated class name.
private fun model(name: String) = Fingerprint(
    name = "toString", parameters = emptyList(), returnType = "Ljava/lang/String;",
    strings = listOf(name)
)

private fun Fingerprint.requireUnique(label: String): Fingerprint = apply {
    val count = matchAll().size
    check(count == 1) { "Expected one content-filter $label, found $count" }
}

private val listingGetter = Fingerprint(
    definingClass = "Lcom/reddit/domain/model/listing/Listing;", name = "getChildren",
    parameters = emptyList(), returnType = "Ljava/util/List;",
    filters = listOf(fieldAccess(opcode = Opcode.IGET_OBJECT,
        smali = "Lcom/reddit/domain/model/listing/Listing;->children:Ljava/util/List;"))
)

private data class Model(
    val diagnostic: String,
    val textLabels: List<String> = emptyList(),
    val communityLabel: String? = null,
    val flairLabel: String? = null,
    val listLabel: String? = null,
    val post: Boolean = false,
    val richTextLabels: List<String> = emptyList()
)

private val models = listOf(
    Model("FeedPostSection(linkId=", listLabel = ", sections=", post = true),
    Model("PostPreviewFeedElement(linkId=", post = true),
    Model("PostTitleElement(linkId=", listOf(", title=", ", translatedTitle=")),
    Model("PreviewTextElement(linkId=", listOf(", text=", ", translatedText=")),
    Model("RichTextElement(linkId=", richTextLabels = listOf(", rtJsonText=")),
    Model("MetadataHeaderElement(linkId=", communityLabel = ", details="),
    Model("TitleWithThumbnailElement(linkId="),
    Model("TitleWithThumbnailCollapsedElement(linkId="),
    Model("ClassicPostElement(linkId=", listOf(", title=", ", translatedTitle="), ", subredditName="),
    Model("PostFlairsElement(linkId=", listLabel = ", flairs="),
    Model("PostFlairFeedPresentationModel(text=", communityLabel = ", subredditName=",
        flairLabel = "PostFlairFeedPresentationModel(text="),
    Model("FeedPostTitleSection(data="),
    Model("PreviewTextSection(data="),
    Model("RichTextSection(data="),
    Model("MetadataHeaderSection(data="),
    Model("TitleWithThumbnailCollapsedSection(data="),
    Model("PostTitleWithThumbnailSection(linkId=", listOf(", title=", ", previewText=")),
    Model("ClassicPostSection(linkId="),
    Model("PostFlairsSection(feedElement=")
)

internal val contentFilterHooks = patch(description = "Installs direct Reddit content-filter model bridges.") {
    compatibleWith(contentFilterCompatibility)
    dependsOn(settingsPatch)
    execute {
        check(packageMetadata.versionName == "2026.45.0") { "Content filters require Reddit 2026.45.0" }
        // Compile-only Link stubs must match the actual input ABI too.
        listOf("getTitle", "getBody", "getSelftext", "getSubreddit", "getKindWithId", "getLinkFlairText").forEach {
            Fingerprint(definingClass = "Lcom/reddit/domain/model/Link;", name = it,
                parameters = emptyList(), returnType = "Ljava/lang/String;").requireUnique(it).originalMethod
        }
        val linkConstructor = LinkConstructorFingerprint.requireUnique("Link constructor").method
        val listing = listingGetter.requireUnique("listing getter").method
        val processor = FeedElementProcessorFingerprint.requireUnique("feed processor").method
        val secondaryFetch = PostsToLoadFingerprint.requireUnique("secondary fetch selector")
        val resolved = models.map { it to model(it.diagnostic).requireUnique("model ${it.diagnostic}") }
        val types = resolved.map { it.second.originalClassDef.type }.toSet() + "Lcom/reddit/domain/model/Link;"
        // Resolve and validate the entire graph before changing any app class.
        val contracts = resolved.map { (spec, fingerprint) ->
            val owner = fingerprint.originalClassDef
            val bindings = modelFields(fingerprint.originalMethod)
            check(owner.methods.none { it.name.startsWith("patch_filter") }) { "Content filters already applied" }
            fun field(label: String): FieldReference = checkNotNull(bindings[label]) { "Missing field $label in ${owner.type}" }.also {
                check(it.definingClass == owner.type && it.type == "Ljava/lang/String;") {
                    "Unexpected content-filter field for ${spec.diagnostic}: $it"
                }
            }
            val children = owner.fields.filter {
                !AccessFlags.STATIC.isSet(it.accessFlags) && it.type in types
            } + listOfNotNull(spec.listLabel?.let { checkNotNull(bindings[it]) { "Missing list field $it in ${owner.type}" } })
            check(children.isNotEmpty() || spec.textLabels.isNotEmpty() || spec.richTextLabels.isNotEmpty() || spec.flairLabel != null || spec.communityLabel != null) {
                "Missing children for ${spec.diagnostic}"
            }
            Triple(fingerprint, spec, listOf(
                children, (spec.textLabels + spec.richTextLabels).map(::field),
                listOfNotNull(spec.communityLabel?.let(::field)),
                listOfNotNull(spec.flairLabel?.let(::field)),
                if (spec.post) listOf(field(spec.diagnostic)) else emptyList()
            ))
        }
        contracts.forEach { (fingerprint, spec, fields) ->
            val owner = fingerprint.classDef
            owner.interfaces.add(NODE)
            fields.forEachIndexed { index, selected ->
                val array = index < 2
                val returnType = if (array) "[Ljava/lang/Object;" else "Ljava/lang/String;"
                val method = ImmutableMethod(owner.type,
                    listOf("patch_filterChildren", "patch_filterText", "patch_filterCommunity", "patch_filterFlair", "patch_filterId")[index],
                    emptyList(), returnType, AccessFlags.PUBLIC.value, emptySet(), emptySet(),
                    MutableMethodImplementation(4)).toMutable()
                val body = if (array) buildString {
                    appendLine("const/16 v0, ${selected.size}")
                    appendLine("new-array v0, v0, [Ljava/lang/Object;")
                    selected.forEachIndexed { n, field ->
                        appendLine("iget-object v1, p0, $field")
                        if (index == 1 && n >= spec.textLabels.size) {
                            appendLine("invoke-static { v1 }, $EXTENSION->visibleRichText(Ljava/lang/String;)Ljava/lang/String;")
                            appendLine("move-result-object v1")
                        }
                        appendLine("const/16 v2, $n")
                        appendLine("aput-object v1, v0, v2")
                    }
                    appendLine("return-object v0")
                } else if (selected.isEmpty()) "const/4 v0, 0\nreturn-object v0"
                    else "iget-object v0, p0, ${selected.single()}\nreturn-object v0"
                method.addInstructions(0, body)
                owner.methods.add(method)
            }
        }
        // Observe native full-post results, including the home feed's secondary fetch.
        linkConstructor.apply {
            findInstructionIndicesReversedOrThrow(Opcode.RETURN_VOID).forEach {
                addInstructions(it, "invoke-static/range { p0 .. p0 }, $EXTENSION->rememberLink(Lcom/reddit/domain/model/Link;)V")
            }
        }
        listing.apply {
            findInstructionIndicesReversedOrThrow(Opcode.RETURN_OBJECT).forEach { index ->
                val r = (implementation!!.instructions[index] as OneRegisterInstruction).registerA
                addInstructions(index, """
                    invoke-static/range { v$r .. v$r }, $EXTENSION->filter(Ljava/util/List;)Ljava/util/List;
                    move-result-object v$r
                """)
            }
        }
        processor.apply {
            val r = p0Register + 2
            addInstructions(0, """
                invoke-static/range { v$r .. v$r }, $EXTENSION->filter(Ljava/util/List;)Ljava/util/List;
                move-result-object v$r
            """)
        }
        // Both independent filters need the full native post data, even when home-flair
        // display is absent. This shared hook is installed once when both are selected.
        val fetchMatch = secondaryFetch.instructionMatches.last()
        val fetchRegister = fetchMatch.getInstruction<OneRegisterInstruction>().registerA
        secondaryFetch.method.addInstructionsAtControlFlowLabel(fetchMatch.index, """
            invoke-static { v$fetchRegister }, $EXTENSION->isSecondaryFetchRemoved(Z)Z
            move-result v$fetchRegister
        """)
    }
}

internal fun registerContentFilter(extension: String) {
    Fingerprint(definingClass = EXTENSION, name = "registerFilters", parameters = emptyList(), returnType = "V")
        .method.addInstructions(0, "invoke-static { }, $extension->register()V")
    setExtensionIsPatchIncluded(extension)
}
