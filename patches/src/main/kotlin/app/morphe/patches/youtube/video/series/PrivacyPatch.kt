package app.morphe.patches.youtube.video.series

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.util.findFieldFromToString
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation

private const val PRIVACY = "${OUR_PREFIX}RecordingPrivacy;"
private const val SOURCE = "${OUR_PREFIX}RecordingPrivacy\$Source;"
private const val IDENTITY = "${OUR_PREFIX}RecordingPrivacy\$Identity;"

/** Resolve the current-account providers, never an arbitrary identity getter or request header. */
internal data class NativeAccountContract(val type: String, val id: String, val incognito: String)

internal fun BytecodePatchContext.wirePrivacy(): NativeAccountContract {
    val identity = AccountIdentityFingerprint.matchAll(1..1).single().originalClassDef
    val diagnostic = AccountIdentityFingerprint.originalMethod
    fun getters(field: FieldReference): List<Method> =
        IdentityFieldGetterFingerprint(field)
            .matchAll(identity)
            .map { it.originalMethod }
            .filter { method ->
                identity.interfaces.any { type ->
                    classDefByOrNull(type)?.let {
                        IdentityInterfaceMethodFingerprint(method).matchOrNull(it)
                    } != null
                }
            }
    val idGetter =
        getters(diagnostic.findFieldFromToString("AccountIdentity{getId=")).singleOrNull()
            ?: throw PatchException("Series Tracker: account ID accessor is ambiguous")
    val signedOut = SignedOutIdentityFingerprint.matchAll(1..1).single().originalClassDef
    // Two accessors read the same field on a signed-in identity. On a pseudonymous identity,
    // one means unauthenticated (true) and the actual incognito accessor is false.
    val incognitoGetter =
        getters(diagnostic.findFieldFromToString(", isIncognito=")).singleOrNull { candidate ->
            SignedOutIncognitoFingerprint(candidate).matchOrNull(signedOut) != null
        }
            ?: throw PatchException(
                "Series Tracker: cannot distinguish signed-out and incognito identities"
            )
    val contract =
        identity.interfaces.single { type ->
            classDefByOrNull(type)?.let {
                IdentityInterfaceMethodFingerprint(idGetter).matchOrNull(it)
            } != null
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
        val currentMatch =
            CurrentAccountGetterFingerprint(contract).matchAll(provider, 1..1).single()
        val current = currentMatch.originalMethod
        val mutable = currentMatch.classDef
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
        currentMatch.method.addInstructions(
            0,
            "invoke-static/range {p0 .. p0}, $PRIVACY->attach($SOURCE)V",
        )
    }
    return NativeAccountContract(contract, idGetter.signature(), incognitoGetter.signature())
}
