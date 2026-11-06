package app.morphe.patches.reddit.misc.archive

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Opt-in integration check against the final APK patched with all Reddit patches. */
class ArchivePatchCompatibilityTest {
    @Test
    fun `all-patches APK keeps flair in the native mapper and archive branches valid`() {
        val path = System.getProperty("redditArchiveAllPatchesApk")
        assumeTrue(!path.isNullOrBlank(), "Supply redditArchiveAllPatchesApk to check a patched Reddit APK")
        val dex = DexFileFactory.loadDexContainer(File(requireNotNull(path)), Opcodes.forApi(36))
        val classes = dex.dexEntryNames.flatMap { dex.getEntry(it)!!.dexFile.classes }
        val archive = "Lapp/morphe/extension/reddit/patches/archive/"
        val gate = "${archive}FeedGateSection;"
        val flair = "Lapp/morphe/extension/reddit/patches/ShowFlairsInHomeFeedPatch;"
        val prepare = classes.single { it.type == gate }.methods.single { it.name == "prepare" }
        assertTrue(prepare.calls().any { it.definingClass == gate && it.name == "group" })
        assertTrue(prepare.calls().none { it.definingClass == flair }, "Flair must not hook the archive helper")

        val mapper = classes.filterNot { it.type.startsWith("Lapp/morphe/") }
            .flatMap { it.methods }.single { method ->
                method.calls().any { it.definingClass == gate && it.name == "prepare" }
            }
        val calls = mapper.calls()
        val gateCalls = calls.withIndex().filter { it.value.definingClass == gate && it.value.name == "prepare" }
        val flairCalls = calls.withIndex().filter { it.value.definingClass == flair && it.value.name == "showHomeFlair" }
        assertEquals(1, gateCalls.size)
        assertEquals(1, flairCalls.size)
        assertTrue(gateCalls.single().index < flairCalls.single().index,
            "Flair must receive the final section returned by the archive gate")

        classes.filter { it.type.startsWith(archive) }.flatMap { it.methods }.forEach { method ->
            val instructions = method.implementation?.instructions?.toList() ?: return@forEach
            val addresses = instructions.runningFold(0) { pc, instruction -> pc + instruction.codeUnits }
            val starts = addresses.dropLast(1).toSet()
            instructions.forEachIndexed { index, instruction ->
                if (instruction is OffsetInstruction) assertTrue(
                    addresses[index] + instruction.codeOffset in starts,
                    "Branch must target an instruction boundary in $method"
                )
            }
        }
    }

    private fun Method.calls(): List<MethodReference> = implementation?.instructions?.mapNotNull {
        (it as? ReferenceInstruction)?.reference as? MethodReference
    }.orEmpty()
}
