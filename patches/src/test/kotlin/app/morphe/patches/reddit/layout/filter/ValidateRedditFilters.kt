/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.filter

import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.apk.ApkUtils.applyTo
import app.morphe.patcher.dex.SdkDexVerifier
import app.morphe.patcher.dex.DexVerifier
import app.morphe.patcher.dex.DexVerificationException
import app.morphe.patcher.dex.NoOpDexVerifier
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.patch.classDefForEach
import app.morphe.patcher.patch.PatchLoader
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import kotlinx.coroutines.runBlocking
import java.io.File

/** Apply each mode in its own JVM, like a new Desktop patching session. */
fun main(args: Array<String>) {
    require(args.size == 4) { "Usage: <original APK> <selection> <output directory> <bundle>" }
    var input = File(args[0]).canonicalFile
    val output = File(args[2]).canonicalFile.apply { mkdirs() }
    val rejection = args[1].takeIf { it.startsWith("reject-") }?.removePrefix("reject-")
    if (rejection != null) input = invalidFilterInput(input, output, rejection)
    val loaded = PatchLoader.Jar(setOf(File(args[3])))
    val filterNames = setOf("Keyword filters", "Flair filters")
    val requestedNames = when (args[1]) {
        "keywords" -> listOf("Keyword filters")
        "flairs" -> listOf("Flair filters")
        "both" -> filterNames.toList()
        "combined" -> filterNames.toList() + listOf("Show flairs in home feed", "Hide ads")
        "keywords-combined" -> listOf("Keyword filters", "Show flairs in home feed", "Hide ads")
        "flairs-combined" -> listOf("Flair filters", "Show flairs in home feed", "Hide ads")
        "reject-missing", "reject-ambiguous", "reject-already" -> loaded.mapNotNull { it.name }.filter { it in filterNames }.distinct()
        else -> error("Unknown mode ${args[1]}")
    }
    val selected = requestedNames.map { name -> loaded.single { it.name == name &&
        it.compatibility?.any { target -> target.packageName == "com.reddit.frontpage" } == true } }.toSet()
    println("Loaded ${selected.map { it.name }} from ${args[3]}")
    val audit = patch {
        dependsOn(*selected.toTypedArray())
        execute {
            val classes = mutableListOf<com.android.tools.smali.dexlib2.iface.ClassDef>()
            classDefForEach { classes.add(it) }
            val node = $$"Lapp/morphe/extension/reddit/patches/ContentFilterPatch$Node;"
            val native = classes.filter { node in it.interfaces }
            check(native.size == 19) { "Expected 19 native model bridges, found ${native.size}" }
            native.forEach { owner ->
                check(owner.methods.count { it.name.startsWith("patch_filter") } == 5)
            }
            // Independently reviewed 2645001 DEX roles; production resolves these from diagnostics.
            val roles = mapOf(
                "Leuj;->patch_filterId" to setOf("a"),
                "Leuj;->patch_filterChildren" to setOf("b"),
                "Lp150;->patch_filterId" to setOf("e"),
                "Lui50;->patch_filterText" to setOf("i", "j"),
                "Lnf60;->patch_filterText" to setOf("i", "l"),
                "Lskc0;->patch_filterText" to setOf("i"),
                "Lioc0;->patch_filterChildren" to setOf("a"),
                "Ld4w;->patch_filterCommunity" to setOf("m"),
                "Lqy8;->patch_filterCommunity" to setOf("k"),
                "Lqy8;->patch_filterText" to setOf("q", "x"),
                "Lsc40;->patch_filterCommunity" to setOf("d"),
                "Lsc40;->patch_filterFlair" to setOf("a"),
                "Lue40;->patch_filterChildren" to setOf("i")
            )
            roles.forEach { (role, expected) ->
                val (owner, name) = role.split("->")
                val actual = classDefBy(owner).methods.single { it.name == name }.implementation!!.instructions
                    .mapNotNull { ((it as? ReferenceInstruction)?.reference as? FieldReference)?.name }.toSet()
                check(actual == expected) { "$role: expected $expected, found $actual" }
            }
            val richTextGetter = classDefBy("Lskc0;").methods.single { it.name == "patch_filterText" }
            check(richTextGetter.implementation!!.instructions.count {
                val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
                ref?.definingClass == "Lapp/morphe/extension/reddit/patches/ContentFilterPatch;" && ref.name == "visibleRichText"
            } == 1) { "Rich-text body must be converted to visible text at the native bridge" }
            check(classDefBy("Lsc40;").methods.single { it.name == "patch_filterText" }.implementation!!.instructions.none {
                (it as? ReferenceInstruction)?.reference is FieldReference
            }) { "Flair fields must never enter keyword input" }
            val runtime = classes.filter { it.type.contains("/reddit/patches/") &&
                (it.type.contains("ContentFilterPatch") || it.type.contains("KeywordFilterPatch") || it.type.contains("FlairFilterPatch")) }
            check(runtime.isNotEmpty())
            runtime.forEach { owner -> owner.methods.forEach { method ->
                method.implementation?.instructions?.forEach { instruction ->
                    val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                    check(reference?.definingClass?.startsWith("Ljava/lang/reflect/") != true)
                    check(!(reference?.definingClass == "Ljava/lang/Class;" && reference.name in
                        setOf("forName", "getMethod", "getDeclaredMethod", "getDeclaredField", "getDeclaredFields")))
                }
            } }
            // Inspect the actual return constant changed by each independently selected patch.
            for ((name, enabled) in listOf("KeywordFilterPatch" to ("Keyword filters" in requestedNames),
                "FlairFilterPatch" to ("Flair filters" in requestedNames))) {
                val owner = classDefByOrNull("Lapp/morphe/extension/reddit/patches/$name;")
                if (owner == null) { check(!enabled); continue }
                val method = owner.methods.single { it.name == "isPatchIncluded" }
                val literal = method.implementation!!.instructions.first() as com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
                check(literal.narrowLiteral == if (enabled) 1 else 0) { "$name selection flag mismatch" }
            }
            val registration = classDefBy("Lapp/morphe/extension/reddit/patches/ContentFilterPatch;")
                .methods.single { it.name == "registerFilters" }.implementation!!.instructions
                .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
                .filter { it.name == "register" }.map { it.definingClass.substringAfterLast('/').removeSuffix(";") }.toSet()
            check(registration == requestedNames.filter { it in filterNames }.map { if (it == "Keyword filters") "KeywordFilterPatch" else "FlairFilterPatch" }.toSet())
            println("PASS: native bridges, selection flags and no-reflection runtime audit")
        }
    }
    val sdk = File(System.getenv("ANDROID_HOME") ?: error("ANDROID_HOME required"))
    // Stock Reddit contains unresolved optional AppsFlyer/re2j references. In the combined
    // delivery check, measure the original first and reject every *new* hierarchy issue.
    // Per-DEX dexdump/D8 errors are never suppressed. Independent modes still serialize
    // and reload every output DEX; the combined mode checks the complete shared code.
    fun issues(message: String) = message.lineSequence().filter { it.trim().matches(Regex("\\d+\\. \\[.*")) }
        .map { it.trim().replace(Regex("^\\d+\\. "), "")
            .replace(Regex("\\(in classes[^)]*\\)|\\(classes[^)]*\\)"), "(DEX)") }.toSet()
    val verifier = if (args[1].endsWith("combined")) {
        val delegate = SdkDexVerifier(sdk)
        val stockDex = File(output, "stock-dex").apply { mkdirs() }
        java.util.zip.ZipFile(input).use { zip ->
            zip.entries().asSequence().filter { it.name.matches(Regex("classes\\d*\\.dex")) }.forEach { entry ->
                zip.getInputStream(entry).use { stream -> File(stockDex, entry.name).outputStream().use { stream.copyTo(it) } }
            }
        }
        val baseline = try {
            delegate.verifyDexDirectory(stockDex)
            emptySet()
        } catch (error: DexVerificationException) {
            check(error.message.orEmpty().startsWith("Cross-DEX class hierarchy verification")) { error }
            issues(error.message.orEmpty()).also { check(it.isNotEmpty()); println("Stock hierarchy issues: ${it.size}") }
        }
        object : DexVerifier {
            override fun verifyDexFile(dexFile: File) = delegate.verifyDexFile(dexFile)
            override fun verifyDexDirectory(dexDir: File) {
                try { delegate.verifyDexDirectory(dexDir) }
                catch (error: DexVerificationException) {
                    val current = issues(error.message.orEmpty())
                    check(error.message.orEmpty().startsWith("Cross-DEX class hierarchy verification") &&
                        current.isNotEmpty() && current == baseline) { error }
                    println("PASS: SDK DEX checks; hierarchy issues exactly match the stock baseline (${baseline.size})")
                }
            }
            override fun verifyApkFile(apkFile: File) = delegate.verifyApkFile(apkFile)
        }
    } else NoOpDexVerifier
    Patcher(PatcherConfig(input, File(output, "work-${args[1]}"), verifier = verifier, validate = true)).use { patcher ->
        patcher += setOf(audit)
        var rejected: Throwable? = null
        runBlocking { patcher().collect { result ->
            result.exception?.let { if (rejection == null) throw it else rejected = it }
            if (result.exception == null) println("Applied: ${result.patch.name ?: result.patch.description}")
        } }
        if (rejection != null) {
            val failure = checkNotNull(rejected) { "Invalid $rejection input was accepted" }
            val details = generateSequence(failure) { it.cause }.joinToString("\n") { it.message.orEmpty() }
            check(when (rejection) {
                "already" -> details.contains("already applied")
                "missing" -> details.contains("Failed to match the fingerprint") && details.contains("PostTitleElement(linkId=")
                "ambiguous" -> details.contains("Expected one content-filter model") && details.contains("found 2")
                else -> false
            }) { details }
            println("PASS: $rejection input rejected: $details")
            return
        }
        val apk = File(output, "reddit-2026.45.0-${args[1]}-unsigned.apk")
        patcher.get().applyTo(apk)
        java.util.zip.ZipFile(apk).use { zip ->
            zip.entries().asSequence().filter { it.name.endsWith(".dex") }.forEach { entry ->
                zip.getInputStream(entry).buffered().use { stream ->
                    DexBackedDexFile.fromInputStream(null, stream).classes.forEach { owner ->
                        owner.methods.forEach { method -> method.implementation?.instructions?.forEach { _ -> } }
                    }
                }
            }
        }
        println("PASS: ${args[1]} applied, all output DEX reloaded, APK rebuilt")
    }
}
