/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4867
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.reddit.layout.filter

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Build deliberately changed 2645001 inputs; never deliver these fixtures. */
internal fun invalidFilterInput(original: File, output: File, mode: String): File {
    ZipFile(original).use { zip ->
        val dex = zip.getInputStream(zip.getEntry("classes7.dex")).buffered().use {
            DexBackedDexFile.fromInputStream(null, it)
        }
        val owner = dex.classes.single { it.type == "Lui50;" }
        val diagnostic = owner.methods.single { it.name == "toString" }
        fun cloneMethod(method: Method, type: String = method.definingClass,
                        implementation: ImmutableMethodImplementation? = method.implementation?.let { ImmutableMethodImplementation.of(it) }) =
            ImmutableMethod(type, method.name, method.parameters, method.returnType, method.accessFlags,
                method.annotations, method.hiddenApiRestrictions, implementation)
        fun cloneClass(type: String, methods: Iterable<Method>): ClassDef = ImmutableClassDef(
            type, owner.accessFlags, owner.superclass, owner.interfaces, owner.sourceFile, owner.annotations, owner.fields, methods
        )
        val modified = when (mode) {
            "missing" -> {
                val implementation = diagnostic.implementation!!
                val instructions = implementation.instructions.map { instruction ->
                    val string = ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string
                    if (string == "PostTitleElement(linkId=") ImmutableInstruction21c(Opcode.CONST_STRING,
                        (instruction as OneRegisterInstruction).registerA, ImmutableStringReference("ChangedTitleElement(linkId="))
                    else instruction
                }
                val changed = cloneMethod(diagnostic, implementation = ImmutableMethodImplementation(
                    implementation.registerCount, instructions, implementation.tryBlocks, implementation.debugItems))
                dex.classes.map { if (it.type == owner.type) cloneClass(owner.type,
                    owner.methods.map { method -> if (method.name == "toString") changed else method }) else it }
            }
            "ambiguous" -> dex.classes + cloneClass("LFilterAmbiguous;", listOf(cloneMethod(diagnostic, "LFilterAmbiguous;")))
            "already" -> {
                val marker = ImmutableMethod(owner.type, "patch_filterId", emptyList(), "Ljava/lang/String;", 1,
                    emptySet(), emptySet(), ImmutableMethodImplementation(2,
                        listOf(ImmutableInstruction11n(Opcode.CONST_4, 0, 0), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)),
                        emptyList(), emptyList()))
                dex.classes.map { if (it.type == owner.type) cloneClass(owner.type, owner.methods + marker) else it }
            }
            else -> error("Unknown invalid-input mode: $mode")
        }
        val pool = DexPool(Opcodes.getDefault())
        modified.forEach(pool::internClass)
        val store = MemoryDataStore()
        pool.writeTo(store)
        val replacement = store.data.copyOf(store.size)
        val target = File(output, "invalid-$mode.apk")
        ZipOutputStream(target.outputStream().buffered()).use { out ->
            zip.entries().asSequence().filterNot { it.name.startsWith("META-INF/") }.forEach { entry ->
                out.putNextEntry(ZipEntry(entry.name))
                if (entry.name == "classes7.dex") out.write(replacement)
                else zip.getInputStream(entry).use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        return target
    }
}
