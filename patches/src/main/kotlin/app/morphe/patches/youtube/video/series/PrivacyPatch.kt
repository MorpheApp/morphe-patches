/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3114
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.video.series

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.util.findFieldFromToString
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

private const val PRIVACY = "${SERIES_TRACKER_EXTENSION_PREFIX}RecordingPrivacy;"
private const val SOURCE = "${SERIES_TRACKER_EXTENSION_PREFIX}RecordingPrivacy\$Source;"
private const val IDENTITY = "${SERIES_TRACKER_EXTENSION_PREFIX}RecordingPrivacy\$Identity;"

/** Resolve the current-account providers, never an arbitrary identity getter or request header. */
internal data class NativeAccountContract(val type: String, val id: String, val incognito: String)

internal fun BytecodePatchContext.wirePrivacy(): NativeAccountContract {
    val identity = AccountIdentityFingerprint.originalClassDef
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
    val idGetter = getters(diagnostic.findFieldFromToString("AccountIdentity{getId="))
        .single()
    val signedOut = SignedOutIdentityFingerprint.originalClassDef
    // Two accessors read the same field on a signed-in identity. On a pseudonymous identity,
    // one means unauthenticated (true) and the actual incognito accessor is false.
    val incognitoGetter = getters(diagnostic.findFieldFromToString(", isIncognito="))
        .single { candidate ->
            SignedOutIncognitoFingerprint(candidate).matchOrNull(signedOut) != null
        }
    val contract = identity.interfaces.single { type ->
        classDefByOrNull(type)?.let {
            IdentityInterfaceMethodFingerprint(idGetter).matchOrNull(it)
        } != null
    }
    // There are two current-account providers. Either one can be the active provider.
    val providers = CurrentAccountProviderFingerprint.matchAll()
        .map { it.originalClassDef }
        .distinctBy { it.type }
    providers.forEach { provider ->
        val currentMatch = CurrentAccountGetterFingerprint(contract).match(provider)
        val current = currentMatch.originalMethod
        val mutable = currentMatch.classDef
        mutable.interfaces.add(SOURCE)
        val bridge = ImmutableMethod(
            provider.type,
            "patch_seriesTrackerIdentity",
            null,
            IDENTITY,
            AccessFlags.PUBLIC.value,
            null,
            null,
            MutableMethodImplementation(5),
        ).toMutable()

        bridge.addInstructions(
            0,
            """
                invoke-virtual {p0}, $current
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
