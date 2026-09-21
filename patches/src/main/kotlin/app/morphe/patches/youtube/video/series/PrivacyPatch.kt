package app.morphe.patches.youtube.video.series

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation

private const val PRIVACY = "${OUR_PREFIX}RecordingPrivacy;"
private const val SOURCE = "${OUR_PREFIX}RecordingPrivacy\$Source;"
private const val IDENTITY = "${OUR_PREFIX}RecordingPrivacy\$Identity;"

/** Resolve the current-account providers, never an arbitrary identity getter or request header. */
internal data class NativeAccountContract(val type: String, val id: String, val incognito: String)

internal fun BytecodePatchContext.wirePrivacy(): NativeAccountContract {
    val identity = AccountIdentityFingerprint.matchAll(1..1).single().originalClassDef
    val diagnostic =
        identity.methods.single { it.name == "toString" }.implementation!!.instructions.toList()
    fun fieldAfter(text: String): FieldReference {
        val start = diagnostic.indexOfFirst {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == text
        }
        return diagnostic
            .drop(start + 1)
            .mapNotNull { (it as? ReferenceInstruction)?.reference as? FieldReference }
            .first { it.definingClass == identity.type }
    }
    fun getters(field: FieldReference): List<Method> =
        identity.methods
            .filter { method ->
                method.parameterTypes.isEmpty() &&
                    method.returnType == field.type &&
                    method.name != "toString" &&
                    method.implementation?.instructions?.any {
                        ((it as? ReferenceInstruction)?.reference as? FieldReference)?.toString() ==
                            field.toString()
                    } == true
            }
            .filter { method ->
                identity.interfaces.any { type ->
                    classDefByOrNull(type)?.methods?.any { it.signature() == method.signature() } ==
                        true
                }
            }
    val idGetter =
        getters(fieldAfter("AccountIdentity{getId=")).singleOrNull()
            ?: throw PatchException("Series Tracker: account ID accessor is ambiguous")
    val signedOut = SignedOutIdentityFingerprint.matchAll(1..1).single().originalClassDef
    // Two accessors read the same field on a signed-in identity. On a pseudonymous identity,
    // one means unauthenticated (true) and the actual incognito accessor is false.
    val incognitoGetter =
        getters(fieldAfter(", isIncognito=")).singleOrNull { candidate ->
            val instructions =
                signedOut.methods
                    .singleOrNull { it.signature() == candidate.signature() }
                    ?.implementation
                    ?.instructions
                    ?.toList()
                    .orEmpty()
            instructions.size == 2 &&
                instructions[0].opcode == Opcode.CONST_4 &&
                (instructions[0] as NarrowLiteralInstruction).narrowLiteral == 0 &&
                instructions[1].opcode == Opcode.RETURN
        }
            ?: throw PatchException(
                "Series Tracker: cannot distinguish signed-out and incognito identities"
            )
    val contract =
        identity.interfaces.single { type ->
            classDefByOrNull(type)?.methods?.any { it.signature() == idGetter.signature() } == true
        }
    val providers =
        CurrentAccountProviderFingerprint.matchAll()
            .map { it.originalClassDef }
            .distinctBy { it.type }
    if (
        providers.size != 2 ||
            providers.any { c ->
                c.fields.none { it.type == "Landroid/content/SharedPreferences;" }
            }
    ) {
        throw PatchException("Series Tracker: expected two native current-account providers")
    }
    providers.forEach { provider ->
        val current =
            provider.methods.singleOrNull { method ->
                method.parameterTypes.isEmpty() &&
                    method.returnType == contract &&
                    AccessFlags.PUBLIC.isSet(method.accessFlags) &&
                    method.implementation != null
            } ?: throw PatchException("Series Tracker: current-account accessor changed")
        val mutable = mutableClassDefBy(provider.type)
        mutable.interfaces.add(SOURCE)
        val bridge =
            ImmutableMethod(
                    provider.type,
                    "seriesTrackerIdentity",
                    emptyList(),
                    IDENTITY,
                    AccessFlags.PUBLIC.value,
                    emptySet(),
                    emptySet(),
                    ImmutableMethodImplementation(5, emptyList(), emptyList(), emptyList()),
                )
                .toMutable()
        bridge.addInstructions(
            0,
            """
            invoke-virtual {p0}, ${provider.type}->${current.signature()}
            move-result-object v0
            if-eqz v0, :unknown
            invoke-interface {v0}, $contract->${idGetter.signature()}
            move-result-object v1
            invoke-interface {v0}, $contract->${incognitoGetter.signature()}
            move-result v2
            new-instance v3, $IDENTITY
            invoke-direct {v3, v1, v2}, $IDENTITY-><init>(Ljava/lang/String;Z)V
            return-object v3
            :unknown
            const/4 v0, 0x0
            return-object v0
        """
                .trimIndent(),
        )
        mutable.methods.add(bridge)
        // Capture only when YouTube actually uses the current-account getter. Constructor order
        // does not establish which provider is active. attach() never calls back into the provider.
        mutable.methods
            .single { it.signature() == current.signature() }
            .addInstructions(
                0,
                "invoke-static/range {p0 .. p0}, $PRIVACY->attach($SOURCE)V",
            )
    }
    return NativeAccountContract(contract, idGetter.signature(), incognitoGetter.signature())
}
