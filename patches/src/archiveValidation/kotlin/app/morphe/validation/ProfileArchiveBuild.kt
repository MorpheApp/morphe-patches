package app.morphe.validation

import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.apk.ApkMerger
import app.morphe.patcher.apk.ApkUtils.applyTo
import app.morphe.patcher.dex.SdkDexVerifier
import app.morphe.patcher.dex.DexVerifier
import app.morphe.patcher.dex.DexVerificationException
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.patch.loadPatchesFromJar
import app.morphe.patches.reddit.misc.archive.profileArchivePatch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import java.lang.reflect.InvocationTargetException
import java.security.MessageDigest
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes

fun main(args: Array<String>) {
    val input = File(args[0]).canonicalFile
    val output = File(args[1]).canonicalFile
    require(input.isFile && input != output)
    output.parentFile.mkdirs()
    val workspace = Files.createTempDirectory(output.parentFile.toPath(), "archive-verify-").toFile()
    val apk = if (input.extension == "apkm") File(workspace, "merged.apk").also {
        ApkMerger().merge(input, it)
    } else input
    val probeDex = args.getOrNull(2)?.takeIf { it.isNotEmpty() }?.let(::File)
    val bundle = args.getOrNull(3)?.takeIf { it.isNotEmpty() }?.let(::File)
    val checkSourceConflict = args.getOrNull(4) == "true"
    val selected = if (bundle == null) profileArchivePatch else {
        val patches = loadPatchesFromJar(setOf(bundle))
        println("Loaded ${patches.size} patches from ${bundle.name}")
        patches.single { it.name == "Archived public profile history" }
    }
    val probePatch = bytecodePatch {
        dependsOn(selected)
        if (probeDex != null) {
            extendWith { probeDex.inputStream() }
            dependsOn(resourcePatch {
                execute {
                    document("AndroidManifest.xml").use { doc ->
                        val node = doc.createElement("instrumentation")
                        node.setAttribute("android:name", "app.morphe.test.ArchiveProbe")
                        node.setAttribute("android:targetPackage", "com.reddit.frontpage")
                        doc.documentElement.appendChild(node)
                        val activity = doc.createElement("activity")
                        activity.setAttribute("android:name", "app.morphe.test.ArchiveProbe${'$'}ProbeActivity")
                        activity.setAttribute("android:exported", "false")
                        doc.getElementsByTagName("application").item(0).appendChild(activity)
                    }
                }
            })
        }
        execute {
            if (probeDex == null) return@execute
            installNativeProbe()
        }
    }
    // A second source has separate fingerprint instances. Confirm why replaying the
    // upstream signature dependency fails after the first copy has changed its target.
    val sourceConflictCheck = bytecodePatch(name = "Verify duplicate-source signature conflict") {
        dependsOn(selected)
        execute {
            val signatureApplied = Fingerprint(
                superclass = "Lapp/morphe/extension/reddit/patches/SpoofSignaturePatch;",
                name = "attachBaseContext", returnType = "V",
                parameters = listOf("Landroid/content/Context;")
            ).classDef
            val duplicate = Fingerprint(
                superclass = "Landroid/app/Application;",
                name = "attachBaseContext", returnType = "V",
                parameters = listOf("Landroid/content/Context;")
            )
            val failure = runCatching { duplicate.match() }.exceptionOrNull()
            check(failure is PatchException && failure.message.orEmpty().contains("Failed to match the fingerprint")) {
                "Expected the second source's signature fingerprint to fail; got $failure"
            }
            println("Duplicate-source failure reproduced: ${signatureApplied.type} already extends ${signatureApplied.superclass}")
        }
    }
    val sdk = File(System.getenv("ANDROID_HOME") ?: error("Set ANDROID_HOME"))
    val sdkVersion = File(sdk, "build-tools").listFiles()!!.filter { it.isDirectory }.maxBy { it.name }.name
    val verifier = baselineVerifier(apk, workspace, SdkDexVerifier(sdk, sdkVersion),
        File(output.parentFile, "verified-dex-$sdkVersion"))
    Patcher(PatcherConfig(apk, File(workspace, "patch"), verifier = verifier,
        targetPatcherVersion = if (bundle == null) "2.0.0" else null, validate = true)).use { patcher ->
        patcher += when {
            probeDex != null -> setOf(probePatch)
            checkSourceConflict -> setOf(sourceConflictCheck)
            else -> setOf(selected)
        }
        runBlocking { patcher().collect { result ->
            result.exception?.let { throw it }
            println("Applied: ${result.patch.name}")
        } }
        patcher.get().applyTo(output)
    }
    auditArchiveDex(output, probeDex != null)
    println("Verified unsigned APK: $output (test fixture: ${probeDex != null})")
}

private fun auditArchiveDex(apk: File, probe: Boolean) {
    val archive = "Lapp/morphe/extension/reddit/patches/archive/"
    val container = DexFileFactory.loadDexContainer(apk, Opcodes.forApi(36))
    val classes = container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }.associateBy { it.type }
    check("${archive}ArchiveFeedView;" !in classes) { "Legacy nested scroller is still packaged" }
    val section = classes.getValue("${archive}ArchiveSection;")
    val sectionInterface = classes.getValue(section.interfaces.single())
    check(sectionInterface.methods.all { signature -> section.methods.any {
        it.name == signature.name && it.parameterTypes == signature.parameterTypes && it.returnType == signature.returnType
    } }) { "Native section interface is incomplete" }
    val modelBridge = classes.getValue("${archive}ProfileArchivePatch;").methods.single { it.name == "sectionModel" }
    val wrapperChild = modelBridge.implementation!!.instructions.mapNotNull {
        if (it.opcode == Opcode.IGET_OBJECT) (it as ReferenceInstruction).reference as? FieldReference else null
    }.single { it.type == sectionInterface.type }
    check(classes.getValue(wrapperChild.definingClass).interfaces.any {
        it == sectionInterface.type || sectionInterface.type in classes.getValue(it).interfaces
    }) {
        "Native hidden-history bridge must recognize the QSF section wrapper"
    }
    val controller = classes.getValue("${archive}ArchiveController;")
    val observer = classes.getValue(controller.interfaces.single())
    check(observer.methods.all { signature -> controller.methods.any { it.name == signature.name && it.parameterTypes.isEmpty() } }) {
        "Native controller lifecycle is incomplete"
    }
    val hookCalls = mutableMapOf<String, Int>()
    classes.values.filter { !it.type.startsWith("Lapp/morphe/") }.forEach { owner ->
        owner.methods.forEach { method -> method.implementation?.instructions?.forEach {
            val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
            if (ref != null && ref.definingClass.startsWith(archive)) {
                val key = ref.definingClass.substringAfterLast('/') + ref.name
                hookCalls[key] = (hookCalls[key] ?: 0) + 1
            }
        } }
    }
    fun hostCalls(name: String) = hookCalls["ProfileArchivePatch;$name"] ?: 0
    check(hostCalls("decorateHost") == 1 && hostCalls("mapVisible") == 1) { "Expected one native feed hook and one visibility hook" }
    check(hostCalls("bindHost") == 2 && hostCalls("captureRequest") == 2 && hostCalls("stableKey") == 1) {
        "Missing profile context, refresh or final-key hooks"
    }
    check(sectionInterface.type in classes.getValue("${archive}ArchiveNativeSection;").interfaces)
    check(sectionInterface.type in classes.getValue("${archive}RecoverySection;").interfaces)
    check(observer.type in classes.getValue("${archive}RecoveryBinding;").interfaces)
    check(hookCalls["RecoveryPatch;recover"] == 18 && hookCalls["RecoveryPatch;recoverProfile"] == 1 &&
        (hookCalls["RecoveryPatch;wrap"] ?: 0) >= 1) { "Missing feed, post, profile-preview or comment recovery hooks: $hookCalls" }
    val recovery = classes.getValue("${archive}RecoveryPatch;")
    recovery.methods.forEach { method -> method.implementation?.instructions?.forEach { ins ->
        val ref = (ins as? ReferenceInstruction)?.reference as? MethodReference
        if (ref != null && ref.definingClass.startsWith(archive)) check(classes.getValue(ref.definingClass).methods.any {
            it.name == ref.name && it.parameterTypes == ref.parameterTypes && it.returnType == ref.returnType
        }) { "Generated recovery call does not resolve: $ref in $method" }
    } }
    listOf("describe", "copy", "profileTarget", "convert", "render", "key", "richElements").forEach { name ->
        check(recovery.methods.single { it.name == name }.implementation!!.instructions.none { it.opcode == Opcode.THROW }) {
            "Unpatched recovery bridge: $name"
        }
    }
    check(classes.getValue("${archive}ArchiveReaderActivity;").superclass == "Landroid/app/Activity;")
    if (!probe) {
        check(classes.keys.none { it.startsWith("Lapp/morphe/test/") || it.startsWith("${archive}ArchiveFixture") || it.startsWith("${archive}ArchiveMixedFixture") || it.startsWith("${archive}RecoveryFixture") }) {
            "Test fixture leaked into release"
        }
    }
    println("Packaged DEX audit passed: section/lifecycle interfaces, QSF wrapper bridge, native hooks, no legacy scroller, fixture=$probe")
}

/** Stock Reddit contains references to optional, absent SDK modules. Accept only the exact
 * pre-existing findings; any new hierarchy, encoding, or duplicate-class issue still fails. */
private fun baselineVerifier(apk: File, workspace: File, sdk: SdkDexVerifier, cache: File): DexVerifier {
    val original = File(workspace, "baseline-dex").apply { mkdirs() }
    ZipFile(apk).use { zip ->
        zip.entries().asSequence().filter { it.name.matches(Regex("classes[0-9]*\\.dex")) }.forEach { entry ->
            zip.getInputStream(entry).use { input -> File(original, entry.name).outputStream().use(input::copyTo) }
        }
    }
    fun findings(error: Throwable): Set<String> {
        require(error is DexVerificationException && error.message.orEmpty().startsWith("Cross-DEX class hierarchy")) {
            "Unexpected verifier failure: $error"
        }
        return error.message!!.lineSequence().mapNotNull {
            Regex("^\\s+\\d+\\. (.+)$").find(it)?.groupValues?.get(1)
        }.map { it.replace(Regex("classes[0-9]*\\.dex"), "classes.dex") }.toSet().also {
            require(it.isNotEmpty())
        }
    }
    // The pinned patcher exposes this check only through a private method; reflection is confined
    // to this desktop validation harness. Runtime extension code never uses reflection.
    val hierarchy = SdkDexVerifier::class.java.getDeclaredMethod("verifyClassHierarchy", File::class.java)
        .apply { isAccessible = true }
    val baseline = try {
        hierarchy.invoke(sdk, original)
        emptySet()
    } catch (error: InvocationTargetException) { findings(error.targetException) }
    File(workspace, "baseline-findings.txt").writeText(baseline.sorted().joinToString("\n"))
    cache.mkdirs()
    return object : DexVerifier by sdk {
        override fun verifyDexDirectory(dexDir: File) {
            dexDir.listFiles()!!.filter { it.extension == "dex" }.forEach { dex ->
                val digest = MessageDigest.getInstance("SHA-256")
                dex.inputStream().use { stream ->
                    val buffer = ByteArray(65536)
                    while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
                }
                val marker = File(cache, digest.digest().joinToString("") { "%02x".format(it) } + ".ok")
                if (!marker.isFile) { sdk.verifyDexFile(dex); marker.writeText("dexdump and d8 passed\n") }
            }
            try { hierarchy.invoke(sdk, dexDir) }
            catch (error: InvocationTargetException) {
                val current = findings(error.targetException)
                val introduced = current - baseline
                check(introduced.isEmpty()) { "Patch introduced DEX issues:\n${introduced.joinToString("\n")}" }
                println("DEX encoding passed; ${current.size} unchanged stock optional-SDK findings")
            }
        }
    }
}
