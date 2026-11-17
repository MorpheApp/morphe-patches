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

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.util.function.Function

internal data class BrowserLayout(
    val trust: Method, val eligible: Method, val browsable: Method, val root: Method,
    val contextField: FieldReference, val callerPackage: FieldReference,
    val clientPackage: FieldReference, val eligibilitySet: FieldReference,
)

/** Resolve the security boundary from its original UID check and browser call graph. */
internal object BrowserDiscovery {
    const val UID_DIAGNOSTIC = "isAllowlistedForMediaBrowser failed UID check. Package: %s, UID: %d"
    private const val CONTEXT = "Landroid/content/Context;"
    private const val STRING = "Ljava/lang/String;"
    private const val SET = "Ljava/util/Set;"
    private const val UID_LOOKUP = "Landroid/content/pm/PackageManager;->getPackagesForUid(I)[Ljava/lang/String;"

    private fun Method.params() = parameterTypes.map(CharSequence::toString)
    private fun MethodReference.params() = parameterTypes.map(CharSequence::toString)
    private fun Method.calls() = implementation?.instructions?.toList().orEmpty().mapNotNull {
        ((it as? ReferenceInstruction)?.reference as? MethodReference)
    }
    private fun <T> List<T>.one(label: String): T = singleOrNull()
        ?: error("Unsupported YouTube Music browser: expected one $label, found $size")
    private fun requireShape(ok: Boolean, label: String) {
        check(ok) { "Unsupported YouTube Music browser: $label" }
    }
    private fun resolveMethod(owner: ClassDef, ref: MethodReference) = owner.methods.filter {
        it.toString() == ref.toString() && it.implementation != null && !AccessFlags.STATIC.isSet(it.accessFlags)
    }.one("${ref.name} browser helper")

    private fun packageField(type: ClassDef): FieldReference {
        // The raw callback package is the String stored directly by the String constructor.
        // Do not assume its obfuscated field name or accidentally select a static log tag.
        requireShape(AccessFlags.PUBLIC.isSet(type.accessFlags), "package type is not public")
        val constructor = type.methods.filter { it.name == "<init>" && it.params() == listOf(STRING) }
            .one("${type.type} package constructor")
        val argument = constructor.implementation?.registerCount?.minus(1)
            ?: error("Unsupported YouTube Music browser: package constructor has no code")
        val field = constructor.implementation!!.instructions.mapNotNull { instruction ->
            val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
            val registers = instruction as? TwoRegisterInstruction
            field?.takeIf { instruction.opcode == Opcode.IPUT_OBJECT && it.definingClass == type.type &&
                it.type == STRING && registers?.registerA == argument && registers.registerB == argument - 1 }
        }.distinctBy(FieldReference::toString).one("${type.type} raw package field")
        val declaration = type.fields.filter { it.toString() == field.toString() }.one("package field declaration")
        requireShape(AccessFlags.PUBLIC.isSet(declaration.accessFlags) && !AccessFlags.STATIC.isSet(declaration.accessFlags),
            "expected an accessible package field for cross-class access")
        return field
    }

    @JvmStatic
    fun discover(candidates: List<Method>, browser: ClassDef, resolve: Function<String, ClassDef>): BrowserLayout {
        val trust = candidates.one("caller trust method")
        val params = trust.params()
        requireShape(trust.returnType == "Z" && params.size == 3 && params[0].startsWith("L") &&
            params[1].startsWith("L") && params[2] == "I" && trust.implementation != null &&
            !AccessFlags.STATIC.isSet(trust.accessFlags), "caller trust signature changed")
        val owner = resolve.apply(trust.definingClass)
        val lookup = trust.implementation!!.instructions.filter {
            ((it as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == UID_LOOKUP
        }.one("original UID ownership lookup")
        val registers = when (lookup) {
            is FiveRegisterInstruction -> listOf(lookup.registerC, lookup.registerD, lookup.registerE,
                lookup.registerF, lookup.registerG).take(lookup.registerCount)
            is RegisterRangeInstruction -> (lookup.startRegister until lookup.startRegister + lookup.registerCount).toList()
            else -> emptyList()
        }
        requireShape(registers.size == 2 && registers[1] == trust.implementation!!.registerCount - 1,
            "original ownership lookup does not consume the captured UID parameter")
        requireShape(trust.calls().any {
            it.toString() == "$CONTEXT->getPackageManager()Landroid/content/pm/PackageManager;"
        }, "missing PackageManager access")
        val context = trust.implementation!!.instructions.mapNotNull {
            ((it as? ReferenceInstruction)?.reference as? FieldReference)?.takeIf { field ->
                it.opcode == Opcode.IGET_OBJECT && field.definingClass == owner.type && field.type == CONTEXT
            }
        }.distinctBy(FieldReference::toString).one("PackageManager context field")
        requireShape(owner.fields.any { it.toString() == context.toString() && !AccessFlags.STATIC.isSet(it.accessFlags) },
            "context field is not an instance field")
        val caller = packageField(resolve.apply(params[0]))
        val client = packageField(resolve.apply(params[1]))
        val root = browser.methods.filter { method ->
            method.params() == listOf(STRING, "I", "Landroid/os/Bundle;") &&
                method.returnType.startsWith("L") && !AccessFlags.STATIC.isSet(method.accessFlags) &&
                method.calls().any { it.toString() == trust.toString() }
        }.one("browser root linked to caller trust")
        requireShape(root.calls().count { it.toString() == trust.toString() } == 2,
            "browser root trust-call contract changed")
        val browsableRef = root.calls().filter {
            it.definingClass == owner.type && it.returnType == "Z" && it.params() == listOf(params[1])
        }.distinctBy(MethodReference::toString).one("root browser eligibility entry")
        val browsable = resolveMethod(owner, browsableRef)
        val eligibleRef = browsable.calls().filter {
            it.definingClass == owner.type && it.returnType == "Z" && it.params() == listOf(params[1])
        }.distinctBy(MethodReference::toString).one("client membership helper")
        requireShape(eligibleRef.toString() != browsable.toString(), "recursive browser eligibility entry")
        val eligible = resolveMethod(owner, eligibleRef)
        requireShape(eligible.calls().count {
            it.toString() == "$SET->contains(Ljava/lang/Object;)Z"
        } == 1, "client membership check changed")
        val membership = eligible.implementation!!.instructions.mapNotNull {
            ((it as? ReferenceInstruction)?.reference as? FieldReference)?.takeIf { field ->
                it.opcode == Opcode.IGET_OBJECT && field.definingClass == owner.type && field.type == SET
            }
        }.distinctBy(FieldReference::toString).one("browser eligibility set")
        return BrowserLayout(trust, eligible, browsable, root, context, caller, client, membership)
    }
}
