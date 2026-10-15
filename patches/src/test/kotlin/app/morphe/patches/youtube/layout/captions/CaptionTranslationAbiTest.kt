package app.morphe.patches.youtube.layout.captions

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.reference.Reference
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.*
import com.android.tools.smali.dexlib2.immutable.instruction.*
import com.android.tools.smali.dexlib2.immutable.reference.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File

/** Synthetic shapes exercise field and abstract-getter models without redistributing YouTube code. */
class CaptionTranslationAbiTest {
    private val trackType = "Lfixture/Track;"
    private val builderType = "Lfixture/Builder;"
    private val string = "Ljava/lang/String;"
    private val labelType = "Ljava/lang/CharSequence;"
    private val public = AccessFlags.PUBLIC.value
    private val language = ImmutableFieldReference(trackType, "language", string)
    private val vss = ImmutableFieldReference(trackType, "vss", string)
    private val url = ImmutableFieldReference(trackType, "url", string)
    private val label = ImmutableFieldReference(trackType, "label", labelType)
    private fun getter(name: String, type: String) = ImmutableMethodReference(trackType, name, emptyList(), type)

    private fun method(owner: String, name: String, params: List<String>, type: String,
                       code: List<Instruction>, flags: Int = public) = ImmutableMethod(owner, name,
        params.map { ImmutableMethodParameter(it, emptySet(), null) }, type, flags, emptySet(), emptySet(),
        ImmutableMethodImplementation(8, code, emptyList(), emptyList()))

    private fun read(reference: Reference, register: Int = 0): List<Instruction> = when (reference) {
        is ImmutableFieldReference -> listOf(ImmutableInstruction22c(Opcode.IGET_OBJECT, register, 7, reference))
        else -> listOf(ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 7, 0, 0, 0, 0, reference),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, register))
    }

    private fun fixture(getters: Boolean = false, ambiguous: Boolean = false,
                        builderFlags: Int = public, labelFlags: Int = public,
                        defaultPackage: Boolean = false): CaptionTranslationAbi {
        val trackType = if (defaultPackage) "LTrack;" else this.trackType
        val builderType = if (defaultPackage) "LBuilder;" else this.builderType
        val language = ImmutableFieldReference(trackType, "language", string)
        val vss = ImmutableFieldReference(trackType, "vss", string)
        val url = ImmutableFieldReference(trackType, "url", string)
        val label = ImmutableFieldReference(trackType, "label", labelType)
        fun getter(name: String, type: String) = ImmutableMethodReference(trackType, name, emptyList(), type)
        val reads = if (getters) listOf(getter("language", string), getter("vss", string), getter("url", string), getter("label", labelType))
            else listOf(language, vss, url, label)
        val factory = method(trackType, "builder", emptyList(), builderType,
            listOf(ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)), public or AccessFlags.STATIC.value)
        val toString = method(trackType, "toString", emptyList(), string, read(reads[3]) + ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0))
        val track = ImmutableClassDef(trackType, public, "Ljava/lang/Object;", emptyList(), null, emptySet(), emptyList(), listOf(factory, toString))
        val copyCode = reads.flatMapIndexed { i, reference ->
            read(reference) + listOf(ImmutableInstruction10x(Opcode.NOP), ImmutableInstruction22c(Opcode.IPUT_OBJECT, 0, 6,
                ImmutableFieldReference(builderType, "f$i", if (i == 3) labelType else string)))
        } + ImmutableInstruction10x(Opcode.RETURN_VOID)
        val copy = method(builderType, "<init>", listOf(trackType), "V", copyCode)
        val setters = (0..3).map { i -> method(builderType, "set$i", listOf(if (i == 3) labelType else string), "V",
            listOf(ImmutableInstruction22c(Opcode.IPUT_OBJECT, 7, 6, ImmutableFieldReference(builderType, "f$i", if (i == 3) labelType else string)),
                ImmutableInstruction10x(Opcode.RETURN_VOID))) }
        val build = method(builderType, "build", emptyList(), trackType, listOf(ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)))
        val methods = listOf(copy, build) + setters + if (ambiguous) listOf(method(builderType, "other", listOf(string), "V", setters[0].implementation!!.instructions.toList())) else emptyList()
        val builder = ImmutableClassDef(builderType, builderFlags, "Ljava/lang/Object;", emptyList(), null, emptySet(),
            listOf(ImmutableField(builderType, "f3", labelType, labelFlags, null, emptySet(), emptySet())), methods)
        val append = ImmutableMethodReference("Ljava/lang/StringBuilder;", "append", listOf(string), "Ljava/lang/StringBuilder;")
        val translated = method("Lfixture/Model;", "translated", emptyList(), "Ljava/util/List;",
            read(reads[2], 4) + listOf(ImmutableInstruction10x(Opcode.NOP),
                ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 3, 4, 0, 0, 0, append),
                ImmutableInstruction21c(Opcode.CONST_STRING, 5, ImmutableStringReference("&tlang=")),
                ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 3, 5, 0, 0, 0, append),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)))
        return resolveCaptionTranslationAbi(track, translated, reads[0], reads[1]) { type ->
            when (type) { trackType -> track; builderType -> builder; else -> null }
        }
    }

    @Test fun `field models resolve through nop instructions and semantic string append`() {
        val abi = fixture()
        assertEquals(url, abi.url)
        assertEquals(label, abi.label)
        assertEquals("set0", abi.languageSetter.name)
        assertEquals("set1", abi.vssSetter.name)
        assertEquals("set2", abi.urlSetter.name)
        assertEquals("f3", abi.labelField.name)
    }

    @Test fun `abstract getter models use the same copy data flow`() {
        val abi = fixture(getters = true)
        assertEquals(getter("url", string), abi.url)
        assertEquals(getter("label", labelType), abi.label)
        assertEquals("set0", abi.languageSetter.name)
    }

    @Test fun `ambiguous builder setters fail before any bytecode is changed`() {
        val error = assertThrows(IllegalStateException::class.java) { fixture(ambiguous = true) }
        assertTrue(error.message!!.contains("language setter"), error.message)
    }

    @Test fun `abstract builders and final or static label fields are rejected`() {
        assertThrows(IllegalStateException::class.java) { fixture(builderFlags = public or AccessFlags.ABSTRACT.value) }
        assertThrows(IllegalStateException::class.java) { fixture(labelFlags = public or AccessFlags.FINAL.value) }
        assertThrows(IllegalStateException::class.java) { fixture(labelFlags = public or AccessFlags.STATIC.value) }
        assertThrows(IllegalStateException::class.java) { fixture(builderFlags = AccessFlags.PRIVATE.value) }
    }

    @Test fun `package private default package builders remain accessible to their tracks`() {
        assertEquals("LBuilder;", fixture(builderFlags = 0, defaultPackage = true).builder.type)
    }

    @Test fun `supplied clean APK resolves the complete translation bridge`() {
        val path = System.getenv("YOUTUBE_CAPTIONS_APK")
        assumeTrue(!path.isNullOrBlank(), "Set YOUTUBE_CAPTIONS_APK to inspect a clean supported YouTube APK")
        val container = DexFileFactory.loadDexContainer(File(path!!), Opcodes.getDefault())
        val classes = container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }.associateBy { it.type }
        fun Method.references() = implementation?.instructions?.toList().orEmpty().mapNotNull { (it as? ReferenceInstruction)?.reference }
        fun Method.contains(text: String) = references().any { (it as? StringReference)?.string == text }
        val track = classes.values.single { candidate -> candidate.methods.any {
            it.returnType == "Z" && it.contains("DISABLE_CAPTIONS_OPTION")
        } }
        fun Reference.isStringAccessor() = when (this) {
            is FieldReference -> definingClass == track.type && type == string
            is MethodReference -> definingClass == track.type && parameterTypes.isEmpty() && returnType == string
            else -> false
        }
        val language = track.methods.single { it.returnType == "Z" && it.contains("DISABLE_CAPTIONS_OPTION") }
            .references().filter { it.isStringAccessor() }.distinct().single()
        val vss = track.methods.single { it.returnType == "Z" && it.contains("t") }
            .references().filter { it.isStringAccessor() }.distinct().single()
        val translated = classes.values.flatMap { it.methods }.single {
            it.parameterTypes.isEmpty() && it.returnType == "Ljava/util/List;" && it.contains("&tlang=")
        }
        val abi = resolveCaptionTranslationAbi(track, translated, language, vss, classes::get)
        assertEquals(track.type, abi.copy.parameterTypes.single())
        assertEquals(track.type, abi.build.returnType)
        assertNotEquals(abi.languageSetter, abi.urlSetter)
        println("Resolved caption translation bridge: ${track.type}, ${abi.builder.type}, ${abi.url}")
    }
}
